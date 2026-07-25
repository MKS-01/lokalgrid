package dev.lokalgrid.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frame strings below are copied verbatim from what `mock-node/src/server.js`
 * emits — the same cross-implementation check the golden vectors give the binary
 * codec, applied to the control path. If the mock changes shape, this fails here
 * rather than as a blank Chat tab on a phone.
 */
class ControlTest {

    @Test
    fun `hello carries who the node thinks you are`() {
        val f = Control.decode(
            """{"type":"hello","proto":2,"deviceId":41000,"recordBytes":32,"hz":1,"mode":"synthetic",
               "you":{"id":0,"name":"bravo"},"cap":9,"duty":0.01}"""
        )
        f as NodeFrame.Hello
        assertEquals(2, f.proto)
        assertEquals(0, f.youId)
        assertEquals("bravo", f.youName)
        assertEquals(9, f.cap)
        assertEquals(0.01, f.duty, 1e-9)
        assertEquals(TrackRecord.BYTES, f.recordBytes)
    }

    @Test
    fun `roster lists everyone on the node`() {
        val f = Control.decode(
            """{"type":"roster","clients":[{"id":0,"name":"bravo","transport":"wifi"},
               {"id":1,"name":"alpha","transport":"wifi"}],"cap":9}"""
        )
        f as NodeFrame.Roster
        assertEquals(2, f.clients.size)
        assertEquals(RosterEntry(1, "alpha", "wifi"), f.clients[1])
    }

    @Test
    fun `chat echo carries the node-assigned seq and the sender's own msgId`() {
        val f = Control.decode(
            """{"type":"chat","seq":7,"from":1,"name":"alpha","text":"water source dry",
               "epoch":1800000000,"lane":2,"msgId":"m-42"}"""
        )
        f as NodeFrame.Chat
        assertEquals(7L, f.seq)
        assertEquals("alpha", f.name)
        assertEquals("water source dry", f.text)
        assertEquals("m-42", f.msgId)
        assertEquals(1_800_000_000L, f.epoch)
    }

    @Test
    fun `a chat from someone else has no msgId, and that is not an error`() {
        val f = Control.decode(
            """{"type":"chat","seq":8,"from":0,"name":"bravo","text":"copy","epoch":1800000001,"lane":2}"""
        ) as NodeFrame.Chat
        assertNull(f.msgId)
    }

    @Test
    fun `queued frame is a reason, not a spinner`() {
        val f = Control.decode(
            """{"type":"queued","msgId":"m-42","seq":7,"reason":"queued 56 s, bravo ahead of you",
               "etaMs":55800,"ahead":1,"lane":2,"airtimeMs":558}"""
        )
        f as NodeFrame.Queued
        assertEquals("queued 56 s, bravo ahead of you", f.reason)
        assertEquals(55_800L, f.etaMs)
        assertEquals(1, f.ahead)
        assertEquals(558, f.airtimeMs)
    }

    @Test
    fun `rejection names its scope so the UI can put it in the right place`() {
        val f = Control.decode(
            """{"type":"rejected","scope":"connection","reason":"node full — 9 of 9 clients connected"}"""
        )
        f as NodeFrame.Rejected
        assertEquals("connection", f.scope)
        assertNull(f.msgId)
        assertTrue(f.reason.startsWith("node full"))
    }

    @Test
    fun `an unknown frame from a newer node is rendered, not thrown`() {
        val f = Control.decode("""{"type":"telemetry","watts":3}""")
        f as NodeFrame.Unknown
        assertEquals("telemetry", f.type)
    }

    @Test
    fun `garbage never kills the socket`() {
        val f = Control.decode("not json at all")
        assertTrue(f is NodeFrame.Malformed)
    }

    @Test
    fun `outbound frames escape text that would otherwise break the frame`() {
        val json = ClientFrame.Send("m-1", "he said \"north\"\nthen left", Lane.EMERGENCY).toJson()
        assertEquals(
            """{"type":"send","msgId":"m-1","text":"he said \"north\"\nthen left","lane":0}""",
            json,
        )
        // …and it survives a round-trip through the same parser the node uses.
        val back = Control.decode(json.replace("\"type\":\"send\"", "\"type\":\"chat\""))
        assertEquals("he said \"north\"\nthen left", (back as NodeFrame.Chat).text)
    }

    @Test
    fun `a peer frame carries its age and its uncertainty, not just a point`() {
        val f = Control.decode(
            """{"type":"peer","id":2,"name":"charlie","latE7":221018771,"lonE7":821912030,
               "hd":18,"epoch":1800000000,"ageS":420,"movedM":88}"""
        )
        f as NodeFrame.Peer
        assertEquals(2, f.id)
        assertEquals(221_018_771, f.latE7)
        assertEquals(18, f.hd)
        assertEquals(420L, f.ageS)
        assertEquals(88, f.movedM)
    }

    @Test
    fun `a decimated position comes back as a skip with its distance`() {
        val f = Control.decode(
            """{"type":"peerSkip","reason":"12 m from your last shared position — decimating below 50 m","movedM":12}"""
        )
        f as NodeFrame.PeerSkip
        assertEquals(12, f.movedM)
        assertTrue(f.reason.contains("decimating"))
    }

    @Test
    fun `config carries what is editable and what is locked, with the reason`() {
        val f = Control.decode(
            """{"type":"config","values":{"nodeName":"lokalgrid","decimationM":50,"dutyPct":1},
               "locked":{"dutyPct":"enforced in firmware, not a setting"},
               "editable":{"decimationM":{"type":"int","min":10,"max":500,"note":"decimate by distance"}}}"""
        )
        f as NodeFrame.Config
        assertEquals("50", f.values["decimationM"])
        assertTrue(f.locked.getValue("dutyPct").contains("enforced in firmware"))
        val edit = f.editable.single()
        assertEquals("decimationM", edit.key)
        assertEquals(10, edit.min)
        assertEquals(500, edit.max)
    }

    @Test
    fun `a partial config write reports both halves`() {
        val f = Control.decode(
            """{"type":"configResult","applied":{"decimationM":120},
               "refused":[{"key":"dutyPct","reason":"enforced in firmware"}]}"""
        )
        f as NodeFrame.ConfigResult
        assertEquals("120", f.applied["decimationM"])
        assertEquals("dutyPct", f.refused.single().key)
    }

    @Test
    fun `stats attribute airtime per client`() {
        val f = Control.decode(
            """{"type":"stats","uptimeS":600,"queueDepth":3,"airtimeMs":1200,"dutyActualPct":0.2,
               "dutyUsedPct":20,"clients":[{"id":0,"name":"alpha","airtimeMs":900,"messages":4,"sharePct":75}]}"""
        )
        f as NodeFrame.Stats
        assertEquals(3, f.queueDepth)
        assertEquals(0.2, f.dutyActualPct, 1e-9)
        assertEquals(75, f.clients.single().sharePct)
    }

    @Test
    fun `outbound position and config frames match what the node parses`() {
        assertEquals(
            """{"type":"pos","latE7":221018771,"lonE7":821912030,"hd":18,"epoch":1800000000}""",
            ClientFrame.Pos(221_018_771, 821_912_030, 18, 1_800_000_000).toJson(),
        )
        // Numbers stay numbers so the node's integer validation is real, strings quote.
        assertEquals(
            """{"type":"config","patch":{"decimationM":120,"nodeName":"ridge node"}}""",
            ClientFrame.ConfigSet(linkedMapOf("decimationM" to "120", "nodeName" to "ridge node")).toJson(),
        )
    }

    @Test
    fun `cursor and reset are the smallest frames on the wire`() {
        assertEquals("""{"type":"cursor","seq":12}""", ClientFrame.Cursor(12).toJson())
        assertEquals("""{"type":"reset"}""", ClientFrame.Reset.toJson())
    }
}
