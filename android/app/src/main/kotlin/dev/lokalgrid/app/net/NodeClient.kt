package dev.lokalgrid.app.net

import dev.lokalgrid.protocol.BadRecord
import dev.lokalgrid.protocol.ClientFrame
import dev.lokalgrid.protocol.Control
import dev.lokalgrid.protocol.NodeFrame
import dev.lokalgrid.protocol.TrackRecord
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * Talks to the mock node over a WebSocket. Binary frames are 32-byte track
 * records (§4) decoded by the *shared* protocol module — the same codec the
 * golden vectors pin down. Text frames are control frames, and they run both
 * ways: this is the forward flow (chat up, echo + queue reason down).
 *
 * This is the SoftAP/WebSocket path only — the BLE path is developed against
 * real hardware in Phase 03 and cannot be mocked (§6).
 */
class NodeClient(private val url: String) {

    sealed interface Event {
        data class Frame(val frame: NodeFrame) : Event
        data class Fix(val record: TrackRecord) : Event
        data class Dropped(val reason: String) : Event   // bad frame, surfaced not swallowed
        data class Status(val connected: Boolean, val detail: String) : Event
    }

    private val client = OkHttpClient.Builder().build()

    @Volatile
    private var socket: WebSocket? = null

    /**
     * Enqueue a frame to the node. Returns false when the socket is gone or its
     * buffer is full — the caller must show that; it is never a silent no-op (§6).
     */
    fun send(frame: ClientFrame): Boolean = socket?.send(frame.toJson()) ?: false

    /** Cold flow: collecting connects; cancelling closes the socket. */
    fun events(): Flow<Event> = callbackFlow {
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                trySend(Event.Status(true, "connected to $url"))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                trySend(Event.Frame(Control.decode(text)))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    trySend(Event.Fix(TrackRecord.decode(bytes.toByteArray())))
                } catch (e: BadRecord) {
                    // Never silently drop — the UI names the failure (§6 UI rule).
                    trySend(Event.Dropped(e.message ?: "bad record"))
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                trySend(Event.Status(false, if (reason.isBlank()) "closed ($code)" else "closed: $reason"))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                trySend(Event.Status(false, "error: ${t.message}"))
            }
        }

        val ws = client.newWebSocket(Request.Builder().url(url).build(), listener)
        socket = ws
        awaitClose {
            socket = null
            ws.close(1000, "collector gone")
        }
    }
}
