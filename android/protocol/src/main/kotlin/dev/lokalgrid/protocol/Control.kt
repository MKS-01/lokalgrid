package dev.lokalgrid.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Control frames — the *forward flow* (§7, Phase 02).
 *
 * Positions come down as binary track records; everything the client says back,
 * and every answer to it, is one JSON object per text frame. Chat is the first
 * user of this path: text up, an authoritative echo down, and a renderable
 * queue reason in between.
 *
 * JSON now, protobuf at Phase 05 (§6). Parsed by hand off a `JsonObject` rather
 * than by sealed-class polymorphism, so an unknown `type` from a newer node is
 * an `Unknown` we can render, never an exception that kills the socket.
 */
sealed interface NodeFrame {

    /** First frame on connect: who the node thinks you are, and its limits. */
    data class Hello(
        val proto: Int,
        val deviceId: Int,
        val recordBytes: Int,
        val hz: Double,
        val mode: String,
        val youId: Int,
        val youName: String,
        val cap: Int,
        val duty: Double,
        // What the node's position log holds right now, so a returning client can
        // tell whether its saved cursor still exists there before asking.
        val posOldest: Long,
        val posNewest: Long,
        val posHeld: Int,
    ) : NodeFrame

    /**
     * The node's answer to a position cursor: what it is about to send, stated
     * before it sends it. `lost` records aged out of the log before this client
     * came back — a gap the UI must show rather than draw straight through.
     */
    data class Backlog(
        val from: Long,
        val to: Long,
        val count: Int,
        val lost: Int,
        val reason: String?,
        val oldest: Long,
        val newest: Long,
        val held: Int,
    ) : NodeFrame

    /** Progress while catching up: bounded chunks, interleaved with live traffic. */
    data class BacklogChunk(val cursor: Long, val remaining: Int) : NodeFrame

    /** You are current. The cursor here is authoritative — adopt it, don't count. */
    data class BacklogDone(val cursor: Long, val live: Boolean) : NodeFrame

    /** Everyone attached to the node right now. Drives Clients, and the names in queue reasons. */
    data class Roster(val clients: List<RosterEntry>, val cap: Int) : NodeFrame

    /**
     * A message on the one shared channel. `seq` is node-assigned — the node is
     * authoritative about what exists (§3). `msgId` is echoed back only to the
     * sender's own message, so a client can reconcile its optimistic bubble.
     */
    data class Chat(
        val seq: Long,
        val from: Int,
        val name: String,
        val text: String,
        val epoch: Long,
        val lane: Int,
        val msgId: String?,
    ) : NodeFrame

    /** Airtime queue state for one of your messages: a reason, never a spinner (§6). */
    data class Queued(
        val msgId: String,
        val seq: Long?,
        val reason: String,
        val etaMs: Long,
        val ahead: Int,
        val lane: Int,
        val airtimeMs: Int,
    ) : NodeFrame

    /** It actually went out over the link. */
    data class Relayed(val msgId: String, val airtimeMs: Int, val lane: Int) : NodeFrame

    /** Where another client says it is. Positions decimate by distance (§3). */
    data class Peer(
        val id: Int,
        val name: String,
        val latE7: Int,
        val lonE7: Int,
        val hd: Int,          // HDOP ×10, 0 = unknown
        val epoch: Long,
        val ageS: Long,
        val movedM: Int,
    ) : NodeFrame

    /** Your position was inside the decimation radius. A skip, with its reason. */
    data class PeerSkip(val reason: String, val movedM: Int) : NodeFrame

    /** The config in force on the node, plus which keys it refuses to make settable. */
    data class Config(
        val values: Map<String, String>,
        val locked: Map<String, String>,   // key -> why it is not a setting
        val editable: List<EditableSetting>,
    ) : NodeFrame

    /** Result of an explicit write: what landed, and why the rest did not. */
    data class ConfigResult(
        val applied: Map<String, String>,
        val refused: List<RefusedSetting>,
    ) : NodeFrame

    /** Airtime accounting — the Clients tab's meters, from the node not a guess. */
    data class Stats(
        val uptimeS: Long,
        val queueDepth: Int,
        val airtimeMs: Long,
        val dutyActualPct: Double,
        val dutyUsedPct: Double,
        val clients: List<ClientStat>,
        // The position log restated, so a long-lived client never renders a
        // connect-time snapshot beside a cursor that has since moved on.
        val posOldest: Long,
        val posNewest: Long,
        val posHeld: Int,
        /**
         * The node's *own* fix state. Without this, a node standing still and a
         * node that has stopped seeing satellites look identical from here — the
         * dot sits in the same place either way, and only one of them is fine.
         * `gnssAgeS` is -1 when the node has never had a fix.
         */
        val gnssSource: String = "unknown",
        val gnssAgeS: Long = -1,
        val gnssSats: Int = 0,
        val gnssHdop: Int = 0,
    ) : NodeFrame

    /** Admission control said no, with something worth showing. Never silent (§3). */
    data class Rejected(val scope: String, val msgId: String?, val reason: String) : NodeFrame

    /** A frame from a node newer than this app. Surfaced, not swallowed. */
    data class Unknown(val type: String, val raw: String) : NodeFrame

    /** The text was not JSON at all. */
    data class Malformed(val raw: String, val error: String) : NodeFrame
}

/** `transport` is `wifi`, `ble`, or `ghost` — a synthetic peer from the mock,
 *  which the UI must label as such rather than pass off as a real client. */
data class RosterEntry(val id: Int, val name: String, val transport: String) {
    val isGhost: Boolean get() = transport == "ghost"
}

data class EditableSetting(val key: String, val type: String, val min: Int?, val max: Int?, val note: String)

data class RefusedSetting(val key: String, val reason: String)

data class ClientStat(val id: Int, val name: String, val airtimeMs: Long, val messages: Int, val sharePct: Int)

/** Frames this client sends up. Serialised by hand — four shapes, no schema yet. */
sealed interface ClientFrame {
    fun toJson(): String

    /** Post to the shared channel. `msgId` is the client's own id for its pending bubble. */
    data class Send(val msgId: String, val text: String, val lane: Int = Lane.MESSAGE) : ClientFrame {
        override fun toJson() =
            """{"type":"send","msgId":${quote(msgId)},"text":${quote(text)},"lane":$lane}"""
    }

    data class Name(val name: String) : ClientFrame {
        override fun toJson() = """{"type":"name","name":${quote(name)}}"""
    }

    /** Share where *you* are. The node decides whether it is far enough to matter. */
    data class Pos(val latE7: Int, val lonE7: Int, val hd: Int, val epoch: Long) : ClientFrame {
        override fun toJson() =
            """{"type":"pos","latE7":$latE7,"lonE7":$lonE7,"hd":$hd,"epoch":$epoch}"""
    }

    /**
     * An explicit config write. Staged locally, sent only when the user says so
     * (§6) — never a silent reconfigure mid-edit.
     */
    data class ConfigSet(val patch: Map<String, String>) : ClientFrame {
        override fun toJson(): String {
            val body = patch.entries.joinToString(",") { (k, v) ->
                // Numbers go as numbers so the node's integer validation is real.
                val value = if (v.toLongOrNull() != null) v else quote(v)
                "${quote(k)}:$value"
            }
            return """{"type":"config","patch":{$body}}"""
        }
    }

    /**
     * "I have chat up to `seq` and positions up to `posSeq`" — ask for the delta,
     * never let the node infer it (§3). The two streams advance independently, so
     * they carry separate cursors in one frame.
     */
    data class Cursor(val seq: Long, val posSeq: Long = 0) : ClientFrame {
        override fun toJson() = """{"type":"cursor","seq":$seq,"posSeq":$posSeq}"""
    }

    data object Reset : ClientFrame {
        override fun toJson() = """{"type":"reset"}"""
    }
}

/** Priority lanes (§3). Chat rides lane 2; lane 0 pre-empts everything. */
object Lane {
    const val EMERGENCY = 0
    const val POSITION = 1
    const val MESSAGE = 2
    const val BULK = 3
}

object Control {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Never throws: a bad frame becomes a renderable [NodeFrame.Malformed]. */
    fun decode(text: String): NodeFrame = try {
        parse(json.parseToJsonElement(text).jsonObject, text)
    } catch (e: Exception) {
        NodeFrame.Malformed(text.take(120), e.message ?: "unparseable frame")
    }

    private fun parse(o: JsonObject, raw: String): NodeFrame = when (val t = o.str("type")) {
        "hello" -> {
            val you = o["you"]?.jsonObject
            NodeFrame.Hello(
                proto = o.int("proto") ?: 0,
                deviceId = o.int("deviceId") ?: 0,
                recordBytes = o.int("recordBytes") ?: TrackRecord.BYTES,
                hz = o.dbl("hz") ?: 1.0,
                mode = o.str("mode") ?: "unknown",
                youId = you?.int("id") ?: -1,
                youName = you?.str("name") ?: "you",
                cap = o.int("cap") ?: 9,
                duty = o.dbl("duty") ?: 0.01,
                posOldest = o.lng("posOldest") ?: 0,
                posNewest = o.lng("posNewest") ?: 0,
                posHeld = o.int("posHeld") ?: 0,
            )
        }

        "backlog" -> NodeFrame.Backlog(
            from = o.lng("from") ?: 0,
            to = o.lng("to") ?: 0,
            count = o.int("count") ?: 0,
            lost = o.int("lost") ?: 0,
            reason = o.str("reason"),
            oldest = o.lng("oldest") ?: 0,
            newest = o.lng("newest") ?: 0,
            held = o.int("held") ?: 0,
        )

        "backlogChunk" -> NodeFrame.BacklogChunk(
            cursor = o.lng("cursor") ?: 0,
            remaining = o.int("remaining") ?: 0,
        )

        "backlogDone" -> NodeFrame.BacklogDone(
            cursor = o.lng("cursor") ?: 0,
            live = o["live"]?.jsonPrimitive?.contentOrNull() == "true",
        )
        "roster" -> NodeFrame.Roster(
            clients = (o["clients"]?.jsonArray ?: emptyList()).map {
                val c = it.jsonObject
                RosterEntry(c.int("id") ?: -1, c.str("name") ?: "?", c.str("transport") ?: "wifi")
            },
            cap = o.int("cap") ?: 9,
        )
        "chat" -> NodeFrame.Chat(
            seq = o.lng("seq") ?: 0,
            from = o.int("from") ?: -1,
            name = o.str("name") ?: "?",
            text = o.str("text").orEmpty(),
            epoch = o.lng("epoch") ?: 0,
            lane = o.int("lane") ?: Lane.MESSAGE,
            msgId = o.str("msgId"),
        )
        "queued" -> NodeFrame.Queued(
            msgId = o.str("msgId").orEmpty(),
            seq = o.lng("seq"),
            reason = o.str("reason") ?: "queued",
            etaMs = o.lng("etaMs") ?: 0,
            ahead = o.int("ahead") ?: 0,
            lane = o.int("lane") ?: Lane.MESSAGE,
            airtimeMs = o.int("airtimeMs") ?: 0,
        )
        "relayed" -> NodeFrame.Relayed(
            msgId = o.str("msgId").orEmpty(),
            airtimeMs = o.int("airtimeMs") ?: 0,
            lane = o.int("lane") ?: Lane.MESSAGE,
        )
        "peer" -> NodeFrame.Peer(
            id = o.int("id") ?: -1,
            name = o.str("name") ?: "?",
            latE7 = o.int("latE7") ?: 0,
            lonE7 = o.int("lonE7") ?: 0,
            hd = o.int("hd") ?: 0,
            epoch = o.lng("epoch") ?: 0,
            ageS = o.lng("ageS") ?: 0,
            movedM = o.int("movedM") ?: 0,
        )

        "peerSkip" -> NodeFrame.PeerSkip(
            reason = o.str("reason") ?: "position skipped",
            movedM = o.int("movedM") ?: 0,
        )

        "config" -> NodeFrame.Config(
            values = (o["values"]?.jsonObject ?: emptyMap()).mapValues { (_, v) ->
                v.jsonPrimitive.contentOrNull().orEmpty()
            },
            locked = (o["locked"]?.jsonObject ?: emptyMap()).mapValues { (_, v) ->
                v.jsonPrimitive.contentOrNull().orEmpty()
            },
            editable = (o["editable"]?.jsonObject ?: emptyMap()).map { (k, v) ->
                val s = v.jsonObject
                EditableSetting(
                    key = k,
                    type = s.str("type") ?: "string",
                    min = s.int("min"),
                    max = s.int("max"),
                    note = s.str("note").orEmpty(),
                )
            },
        )

        "configResult" -> NodeFrame.ConfigResult(
            applied = (o["applied"]?.jsonObject ?: emptyMap()).mapValues { (_, v) ->
                v.jsonPrimitive.contentOrNull().orEmpty()
            },
            refused = (o["refused"]?.jsonArray ?: emptyList()).map {
                val r = it.jsonObject
                RefusedSetting(r.str("key").orEmpty(), r.str("reason").orEmpty())
            },
        )

        "stats" -> NodeFrame.Stats(
            uptimeS = o.lng("uptimeS") ?: 0,
            queueDepth = o.int("queueDepth") ?: 0,
            airtimeMs = o.lng("airtimeMs") ?: 0,
            dutyActualPct = o.dbl("dutyActualPct") ?: 0.0,
            dutyUsedPct = o.dbl("dutyUsedPct") ?: 0.0,
            clients = (o["clients"]?.jsonArray ?: emptyList()).map {
                val c = it.jsonObject
                ClientStat(
                    id = c.int("id") ?: -1,
                    name = c.str("name") ?: "?",
                    airtimeMs = c.lng("airtimeMs") ?: 0,
                    messages = c.int("messages") ?: 0,
                    sharePct = c.int("sharePct") ?: 0,
                )
            },
            posOldest = o.lng("posOldest") ?: 0,
            posNewest = o.lng("posNewest") ?: 0,
            posHeld = o.int("posHeld") ?: 0,
            gnssSource = o.str("gnssSource") ?: "unknown",
            gnssAgeS = o.lng("gnssAgeS") ?: -1,
            gnssSats = o.int("gnssSats") ?: 0,
            gnssHdop = o.int("gnssHdop") ?: 0,
        )

        "rejected" -> NodeFrame.Rejected(
            scope = o.str("scope") ?: "message",
            msgId = o.str("msgId"),
            reason = o.str("reason") ?: "refused, no reason given",
        )
        else -> NodeFrame.Unknown(t ?: "(no type)", raw.take(120))
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull()
    private fun JsonObject.int(k: String) = this[k]?.jsonPrimitive?.intOrNull
    private fun JsonObject.lng(k: String) = this[k]?.jsonPrimitive?.longOrNull
    private fun JsonObject.dbl(k: String) = this[k]?.jsonPrimitive?.doubleOrNull

    private fun JsonPrimitive.contentOrNull(): String? = if (this is JsonNull) null else content
}

private fun quote(s: String): String {
    val sb = StringBuilder(s.length + 2).append('"')
    for (c in s) when (c) {
        '"' -> sb.append("\\\"")
        '\\' -> sb.append("\\\\")
        '\n' -> sb.append("\\n")
        '\r' -> sb.append("\\r")
        '\t' -> sb.append("\\t")
        else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
    }
    return sb.append('"').toString()
}
