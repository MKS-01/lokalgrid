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

    /**
     * Which connection attempt owns [gatt], [control] and [mtu].
     *
     * The caller's retry loop cancels a flow and starts the next attempt without
     * waiting for the old one to finish tearing down, so two `events()` bodies
     * overlap for a moment — and Android keeps delivering the old
     * `BluetoothGattCallback` for as long as its `BluetoothGatt` is open. Without
     * a token the loser writes over the winner: a stale `onMtuChanged` reports an
     * MTU this link never agreed to, and a stale teardown clears the live
     * characteristic, after which every `send` returns false with nothing visibly
     * wrong on the wire.
     */
    private val session = java.util.concurrent.atomic.AtomicLong(0)

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

        // Claimed before connectGatt, so no callback can arrive unowned.
        val token = session.incrementAndGet()
        fun mine() = session.get() == token

        // A fresh link negotiates its own MTU. Carrying the last one over would
        // have the UI quoting a number this connection never agreed to.
        mtu = 23

        // Control frames arrive fragmented when they exceed one notification —
        // this phone negotiates 256, and `stats` and `config` are both larger.
        // One leading byte says whether more follows. Bytes are joined and
        // decoded once at the end, never per fragment: a UTF-8 sequence can be
        // split across two of them. Per connection, so a dropped link cannot
        // weld half a frame onto the start of the next session.
        val controlBuf = java.io.ByteArrayOutputStream()

        val assembler = ChunkAssembler { record ->
            try {
                trySend(NodeClient.Event.Fix(TrackRecord.decode(record)))
            } catch (e: BadRecord) {
                trySend(NodeClient.Event.Dropped(e.message ?: "bad record"))
            }
        }

        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (!mine()) return
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    trySend(NodeClient.Event.Status(false, "connected over ble, discovering"))
                    g.discoverServices()
                } else {
                    trySend(NodeClient.Event.Status(false, "ble link dropped (status $status)"))
                    close()
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (!mine()) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    // Discovery is the one step with no retry above it: if it
                    // failed, nothing later in this callback chain will ever fire.
                    trySend(NodeClient.Event.Status(false, "service discovery failed (status $status)"))
                    close()
                    return
                }
                // §5, and it is worth the line: the default connection interval
                // gives 5–8 KB/s where the tuned one gives 15–25 on the S25. The
                // backlog after an hour away is ~200 KB of records.
                runCatching { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
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
                //
                // If the request is refused outright, onMtuChanged never fires —
                // and subscribing only from there would leave the link open, silent
                // and un-retried forever, which is the failure this whole path
                // exists to avoid. Carry on at the default MTU instead and say so.
                if (!g.requestMtu(WANT_MTU)) {
                    trySend(NodeClient.Event.Status(false, "mtu request refused — subscribing at $mtu"))
                    subscribe(g, CHR_CONTROL)
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
                if (!mine()) return
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
                val chr = g.getService(SERVICE)?.getCharacteristic(which)
                val cccd = chr?.getDescriptor(CCCD)
                if (chr == null || cccd == null) {
                    // A service that is missing a characteristic, or a
                    // characteristic with no CCCD, is not this node's service —
                    // say which piece is absent rather than waiting on a
                    // notification that can never arrive.
                    trySend(NodeClient.Event.Status(false, "no notifications on ${which.toString().takeLast(4)}"))
                    close()
                    return
                }
                g.setCharacteristicNotification(chr, true)
                val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                        BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        g.writeDescriptor(cccd)
                    }
                }
                if (!ok) {
                    trySend(NodeClient.Event.Status(false, "subscribe to ${which.toString().takeLast(4)} was refused"))
                    close()
                }
            }

            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                if (!mine()) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    // Without both subscriptions the node will not admit us, so a
                    // failed CCCD write is the end of this attempt — ending the
                    // flow hands it to the caller's backoff instead of hanging.
                    trySend(
                        NodeClient.Event.Status(
                            false,
                            "could not subscribe to ${d.characteristic.uuid.toString().takeLast(4)} (status $status)",
                        )
                    )
                    close()
                    return
                }
                when (d.characteristic.uuid) {
                    CHR_CONTROL -> subscribe(g, CHR_DATA)
                    // Both streams are live, which is exactly what the node waits
                    // for before it joins us to the session and sends `hello`. The
                    // cursors go up in reply to that (§3 — the client states what
                    // it has, the node answers), not from here.
                    CHR_DATA -> trySend(NodeClient.Event.Status(true, "ble · mtu $mtu"))
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
                if (!mine()) return
                when (uuid) {
                    CHR_CONTROL -> {
                        if (value.isEmpty()) return
                        controlBuf.write(value, 1, value.size - 1)
                        if (controlBuf.size() > 16_384) {
                            // Nothing this protocol sends is remotely this big, so
                            // a buffer this large means fragments are being joined
                            // that do not belong together. Say so and start clean
                            // rather than hand the decoder a growing mess.
                            controlBuf.reset()
                            trySend(NodeClient.Event.Dropped("control frame ran past 16 KB — buffer reset"))
                        } else if (value[0].toInt() == 0) {   // 0 = last fragment
                            val json = controlBuf.toByteArray().decodeToString()
                            controlBuf.reset()
                            trySend(NodeClient.Event.Frame(Control.decode(json)))
                        }
                    }
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
            // Clear only if these still refer to *this* link. The retry loop
            // cancels the old flow and launches the next attempt without joining
            // it, so this block can run after a newer connection has already
            // installed itself — and blindly nulling would leave the live link
            // with no `control` characteristic, so every frame the app sent
            // afterwards returned false with nothing wrong on the wire.
            if (mine()) {
                control = null
                gatt = null
            }
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
