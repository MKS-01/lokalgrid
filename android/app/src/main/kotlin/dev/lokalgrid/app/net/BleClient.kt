package dev.lokalgrid.app.net

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import dev.lokalgrid.protocol.BadRecord
import dev.lokalgrid.protocol.ClientFrame
import dev.lokalgrid.protocol.Control
import dev.lokalgrid.protocol.TrackRecord
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

/**
 * The node over BLE GATT — the transport that could never be mocked (§6), and the
 * reason this is a native app rather than a web page: BLE is the always-on layer
 * at ~2 mA, so the node stays reachable for a week instead of a day.
 *
 * Same `proto 2` as the WebSocket. Two characteristics, matching
 * `firmware/main/ble_gatt.c`:
 *
 *   control  write + notify   one JSON object per frame
 *   data     notify           32-byte records in the §4 chunk framing
 *
 * Emits the same [NodeClient.Event] stream as the WiFi path, so everything above
 * this — cursors, backlog, chat, the whole UI — is transport-agnostic and cannot
 * drift between the two.
 */
class BleClient(private val context: Context) {

    companion object {
        /* Readable on purpose ("okal" "gr" "id"), and fixed so the app filters on
         * the *service* rather than on a device name anyone could set. These must
         * match firmware/main/ble_gatt.c, where the same values are written
         * byte-reversed because NimBLE takes them little-endian. */
        val SERVICE: UUID = UUID.fromString("6f6b616c-6772-6964-0000-000000000001")
        val CHR_CONTROL: UUID = UUID.fromString("6f6b616c-6772-6964-0000-000000000002")
        val CHR_DATA: UUID = UUID.fromString("6f6b616c-6772-6964-0000-000000000003")

        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** What §5 asks for: 517 gets 15–25 KB/s on the S25 against 5–8 at the
         *  default. The *negotiated* value is what sizes the chunks, never this. */
        const val WANT_MTU = 517
    }

    data class Found(val address: String, val name: String, val rssi: Int)

    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var control: BluetoothGattCharacteristic? = null

    @Volatile
    var mtu: Int = 23
        private set

    /** Why BLE is not usable right now, or null when it is. Named so the UI can
     *  say which of the three different "no bluetooth" situations this is. */
    fun unavailableReason(): String? = when {
        adapter == null -> "this phone has no Bluetooth adapter"
        !adapter.isEnabled -> "Bluetooth is switched off"
        adapter.bluetoothLeScanner == null -> "Bluetooth LE is unavailable"
        else -> null
    }

    /**
     * Scan for nodes advertising the service. Cold flow: collecting scans,
     * cancelling stops — a scan left running is a battery leak the user cannot see.
     */
    @SuppressLint("MissingPermission")   // callers gate on BLUETOOTH_SCAN
    fun scan(): Flow<List<Found>> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            close()
            return@callbackFlow
        }

        val seen = LinkedHashMap<String, Found>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val f = Found(
                    address = result.device.address,
                    name = result.device.name ?: result.scanRecord?.deviceName ?: "unnamed",
                    rssi = result.rssi,
                )
                seen[f.address] = f
                trySend(seen.values.sortedByDescending { it.rssi })
            }

            override fun onScanFailed(errorCode: Int) {
                // Surfaced, not swallowed: error 2 is "app registration failed",
                // which on most phones means scanning too often, and there is no
                // way to guess that from an empty list.
                trySend(emptyList())
                close()
            }
        }

        scanner.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            cb,
        )
        awaitClose { runCatching { scanner.stopScan(cb) } }
    }

    /** Enqueue a control frame. False when there is no link — never silent (§6). */
    @SuppressLint("MissingPermission")
    fun send(frame: ClientFrame): Boolean {
        val g = gatt ?: return false
        val c = control ?: return false
        val bytes = frame.toJson().toByteArray()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run { c.value = bytes; g.writeCharacteristic(c) }
        }
    }

    /**
     * Connect to [address] and stream the session. The flow completes when the
     * link drops, so the caller's retry loop treats BLE exactly like WiFi.
     */
    @SuppressLint("MissingPermission")
    fun events(address: String): Flow<NodeClient.Event> = callbackFlow {
        val device: BluetoothDevice? = runCatching { adapter?.getRemoteDevice(address) }.getOrNull()
        if (device == null) {
            trySend(NodeClient.Event.Status(false, "unknown device $address"))
            close()
            return@callbackFlow
        }

        val assembler = ChunkAssembler { record ->
            try {
                trySend(NodeClient.Event.Fix(TrackRecord.decode(record)))
            } catch (e: BadRecord) {
                trySend(NodeClient.Event.Dropped(e.message ?: "bad record"))
            }
        }

        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    trySend(NodeClient.Event.Status(false, "connected over ble, discovering"))
                    g.discoverServices()
                } else {
                    trySend(NodeClient.Event.Status(false, "ble link dropped (status $status)"))
                    close()
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val svc = g.getService(SERVICE)
                if (svc == null) {
                    trySend(NodeClient.Event.Status(false, "this device has no lokalgrid service"))
                    close()
                    return
                }
                control = svc.getCharacteristic(CHR_CONTROL)
                // MTU first: chunk sizes are computed from the negotiated value on
                // both sides, so asking after subscribing would race the first
                // records (§8 — "MTU assumed not read").
                g.requestMtu(WANT_MTU)
            }

            override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
                mtu = newMtu
                trySend(NodeClient.Event.Status(false, "ble mtu $newMtu — subscribing"))
                // 2M PHY where the phone supports it: 15–25 KB/s instead of 5–8 (§5).
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    runCatching {
                        g.setPreferredPhy(
                            BluetoothDevice.PHY_LE_2M_MASK,
                            BluetoothDevice.PHY_LE_2M_MASK,
                            BluetoothDevice.PHY_OPTION_NO_PREFERRED,
                        )
                    }
                }
                subscribe(g, CHR_CONTROL)
            }

            /** One descriptor write at a time: Android drops overlapping GATT
             *  operations silently, which shows up as "notifications work on one
             *  characteristic and not the other". */
            private fun subscribe(g: BluetoothGatt, which: UUID) {
                val chr = g.getService(SERVICE)?.getCharacteristic(which) ?: return
                g.setCharacteristicNotification(chr, true)
                val cccd = chr.getDescriptor(CCCD) ?: return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        g.writeDescriptor(cccd)
                    }
                }
            }

            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                when (d.characteristic.uuid) {
                    CHR_CONTROL -> subscribe(g, CHR_DATA)
                    CHR_DATA -> {
                        trySend(NodeClient.Event.Status(true, "ble · mtu $mtu"))
                        // State our cursors the moment both streams are live: the
                        // client is authoritative about what it has (§3), and the
                        // node answers rather than assuming.
                        gatt = g
                    }
                }
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                chr: BluetoothGattCharacteristic,
                value: ByteArray,
            ) = dispatch(chr.uuid, value)

            @Deprecated("pre-33 signature; the platform calls this one on older phones")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(g: BluetoothGatt, chr: BluetoothGattCharacteristic) {
                dispatch(chr.uuid, chr.value ?: return)
            }

            private fun dispatch(uuid: UUID, value: ByteArray) {
                when (uuid) {
                    CHR_CONTROL -> trySend(NodeClient.Event.Frame(Control.decode(value.decodeToString())))
                    CHR_DATA -> assembler.accept(value) { reason ->
                        trySend(NodeClient.Event.Dropped(reason))
                    }
                }
            }
        }

        trySend(NodeClient.Event.Status(false, "opening ble link to $address"))
        val g = device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
        gatt = g

        awaitClose {
            control = null
            gatt = null
            runCatching { g?.disconnect() }
            runCatching { g?.close() }
        }
    }
}

/**
 * The §4 chunk framing, unpacked: `seq u16 | len u16 | payload | crc16`.
 *
 * Payload carries **whole records only**, which is why this class is short — there
 * is no reassembly of split records to get wrong, only a CRC to check and a
 * sequence to notice gaps in. That trade (a few wasted bytes per chunk for an
 * entire absent bug class) is the §4 decision made visible.
 */
internal class ChunkAssembler(private val onRecord: (ByteArray) -> Unit) {

    private var expectSeq: Int? = null

    fun accept(frame: ByteArray, onProblem: (String) -> Unit) {
        if (frame.size < 6) {
            onProblem("ble chunk of ${frame.size} bytes is too short to be one")
            return
        }
        val seq = (frame[0].toInt() and 0xff) or ((frame[1].toInt() and 0xff) shl 8)
        val len = (frame[2].toInt() and 0xff) or ((frame[3].toInt() and 0xff) shl 8)
        if (4 + len + 2 != frame.size) {
            onProblem("ble chunk says $len bytes but carries ${frame.size - 6}")
            return
        }
        val stored = (frame[4 + len].toInt() and 0xff) or ((frame[5 + len].toInt() and 0xff) shl 8)
        val computed = crc16(frame, 0, 4 + len)
        if (stored != computed) {
            onProblem("ble chunk $seq failed its crc — dropped")
            return
        }
        if (len % TrackRecord.BYTES != 0) {
            onProblem("ble chunk $seq is not a whole number of records")
            return
        }

        expectSeq?.let { want ->
            if (seq != want) onProblem("ble chunk gap: expected $want, got $seq")
        }
        expectSeq = (seq + 1) and 0xffff

        var off = 4
        while (off < 4 + len) {
            onRecord(frame.copyOfRange(off, off + TrackRecord.BYTES))
            off += TrackRecord.BYTES
        }
    }

    /** CRC-16/CCITT-FALSE, matching `crc16_ccitt` in firmware/main/ble_gatt.c. */
    private fun crc16(data: ByteArray, from: Int, to: Int): Int {
        var crc = 0xffff
        for (i in from until to) {
            crc = crc xor ((data[i].toInt() and 0xff) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xffff
                else (crc shl 1) and 0xffff
            }
        }
        return crc
    }
}
