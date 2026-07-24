package dev.lokalgrid.app.net

import dev.lokalgrid.protocol.BadRecord
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
 * Talks to the Phase 00 mock node over a WebSocket. Each binary frame is one
 * 32-byte track record (§4); we decode it with the *shared* protocol module, so
 * the app exercises the same codec the golden vectors pin down. This is the
 * SoftAP/WebSocket path only — the BLE path is developed against real hardware
 * in Phase 03 and cannot be mocked (§6).
 */
class NodeClient(private val url: String) {

    sealed interface Event {
        data class Hello(val text: String) : Event
        data class Fix(val record: TrackRecord) : Event
        data class Dropped(val reason: String) : Event   // bad frame, surfaced not swallowed
        data class Status(val connected: Boolean, val detail: String) : Event
    }

    private val client = OkHttpClient.Builder().build()

    /** Cold flow: collecting connects; cancelling closes the socket. */
    fun events(): Flow<Event> = callbackFlow {
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                trySend(Event.Status(true, "connected to $url"))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                trySend(Event.Hello(text))
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
                trySend(Event.Status(false, "closing: $reason"))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                trySend(Event.Status(false, "error: ${t.message}"))
            }
        }

        val socket = client.newWebSocket(Request.Builder().url(url).build(), listener)
        awaitClose { socket.close(1000, "collector gone") }
    }
}
