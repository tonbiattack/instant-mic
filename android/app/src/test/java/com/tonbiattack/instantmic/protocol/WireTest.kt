package com.tonbiattack.instantmic.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WireTest {

    @Test
    fun `auth message shape`() {
        val json = JSONObject(AuthMessage("dev-1", "Pixel", "tok").toJson())
        assertEquals("auth", json.getString("type"))
        assertEquals(1, json.getInt("protocol"))
        assertEquals("dev-1", json.getString("deviceId"))
        assertEquals("tok", json.getString("token"))
    }

    @Test
    fun `text message shape`() {
        val json = JSONObject(TextMessage("op-1", "こんにちは").toJson())
        assertEquals("text", json.getString("type"))
        assertEquals("op-1", json.getString("id"))
        assertEquals("こんにちは", json.getString("text"))
    }

    @Test
    fun `key message shape`() {
        val json = JSONObject(KeyMessage("op-2", "shift+enter").toJson())
        assertEquals("key", json.getString("type"))
        assertEquals("shift+enter", json.getString("key"))
    }

    @Test
    fun `ready parsing`() {
        val ready = ReadyMessage.parse(
            JSONObject("""{"type":"ready","protocol":1,"serverName":"PC","serverId":"s1","commandPrefix":"コマンド"}"""),
        )
        assertNotNull(ready)
        assertEquals("PC", ready!!.serverName)
        assertEquals("コマンド", ready.commandPrefix)

        assertNull(ReadyMessage.parse(JSONObject("""{"type":"ready","protocol":99}""")))
        assertNull(ReadyMessage.parse(JSONObject("""{"type":"ack"}""")))
    }

    @Test
    fun `ack parsing`() {
        val ack = AckMessage.parse(
            JSONObject("""{"type":"ack","id":"op-1","ok":true,"applied":"text","elapsedMs":12}"""),
        )
        assertNotNull(ack)
        assertTrue(ack!!.ok)
        assertEquals("text", ack.applied)
        assertEquals(12L, ack.elapsedMs)

        val failed = AckMessage.parse(
            JSONObject("""{"type":"ack","id":"op-2","ok":false,"error":"no focus"}"""),
        )
        assertEquals("no focus", failed!!.error)
    }

    @Test
    fun `error parsing`() {
        val error = ErrorMessage.parse(
            JSONObject("""{"type":"error","id":"op-1","code":"bad_key","message":"unsupported key"}"""),
        )
        assertNotNull(error)
        assertEquals("op-1", error!!.id)
        assertEquals("bad_key", error.code)
    }

    @Test
    fun `pong parsing`() {
        val pong = PongMessage.parse(JSONObject("""{"type":"pong","id":"p","sentAt":123}"""))
        assertEquals(123L, pong!!.sentAt)
    }

    @Test
    fun `operation ids are unique and safe`() {
        val ids = (1..1000).map { OperationIds.next() }
        assertEquals(ids.size, ids.toSet().size)
        for (id in ids) {
            assertTrue(id, id.matches(Regex("[a-z0-9-]{1,64}")))
        }
    }

    @Test
    fun `fingerprints normalize`() {
        val hex = "AA:BB:CC".lowercase()
        assertEquals("aabbcc", Fingerprints.normalize(hex))
        assertEquals("aabbcc", Fingerprints.normalize("aa bb cc"))
    }
}
