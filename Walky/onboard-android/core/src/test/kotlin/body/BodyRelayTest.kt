package com.vadymsidorov.walky.core.body

import com.vadymsidorov.walky.core.telemetry.WalkyJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class BodyRelayTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pico = FakePico()
    private val body = PicoBody(pico::connect, scope, RecordingLogger())
    private val pairings = CopyOnWriteArrayList<Pair<String, String>>()
    private val sent = CopyOnWriteArrayList<Pair<Long, JsonObject>>()
    private val relay = BodyRelay(body, { ssid, password -> pairings += ssid to password },
        { sent += System.nanoTime() / 1_000_000 to it }, scope)

    private fun line(text: String) = relay.onLine(WalkyJson.parseToJsonElement(text).jsonObject)

    private fun sentToPico() = JsonObject(pico.next()!!.filterKeys { it != "n" }).toString()

    @After
    fun tearDown() {
        scope.cancel()
        pico.close()
    }

    @Test
    fun relayLinesBecomeBodyCommands() {
        body.start()
        eventually(what = "link up") { body.state.value.pico }
        line("""{"link":"body","legs":[10,-5.5]}""")
        assertEquals("""{"t":"legs","l":10.0,"r":-5.5}""", sentToPico())
        line("""{"link":"body","trim":[3,-18]}""")
        assertEquals("""{"t":"trim","steps":[3,-18]}""", sentToPico())
        line("""{"link":"body","save":true}""")
        assertEquals("""{"t":"save"}""", sentToPico())
        line("""{"link":"body","limp":true}""")
        assertEquals("""{"t":"limp"}""", sentToPico())
    }

    @Test
    fun linesThatDoNotFitAreIgnored() {
        body.start()
        eventually(what = "link up") { body.state.value.pico }
        for (bad in listOf(
            """{"link":"body","legs":[10]}""", """{"link":"body","legs":["10","5"]}""", """{"link":"body","legs":10}""",
            """{"link":"body","trim":[1.5,0]}""", """{"link":"body","limp":false}""", """{"link":"body","save":"yes"}""",
            """{"link":"drive","legs":[1,2]}""", """{"legs":[1,2]}""", """{"link":"pair","ssid":"Walky-1"}""",
            """{"link":"pair","ssid":"","password":"secret-secret-secret"}""", """{"link":"pair","ssid":5,"password":true}""",
        )) line(bad)
        line("""{"link":"body","limp":true}""")
        assertEquals("the first command to arrive is the valid one", """{"t":"limp"}""", sentToPico())
        assertTrue(pairings.isEmpty())
    }

    @Test
    fun pairLineHandsOverTheCredentials() {
        line("""{"link":"pair","ssid":"Walky-617264","password":"0123456789abcdef01234567"}""")
        assertEquals(listOf("Walky-617264" to "0123456789abcdef01234567"), pairings.toList())
    }

    @Test
    fun reportsTheLinkOnChangeAtMostTenTimesASecondAndAtLeastOnce() {
        relay.start()
        // Not started yet, so nothing changes: only the once-a-second repeat goes out.
        val waiting = eventually { sent.firstOrNull()?.second }
        assertEquals("""{"link":"body","phase":"connecting"}""", waiting.toString())
        eventually(what = "repeat") { sent.size >= 2 }
        val gap = sent[1].first - sent[0].first
        assertTrue("repeat after $gap ms", gap in 900..1_500)

        body.start()
        val connected = eventually { sent.lastOrNull()?.second?.takeIf { "pico" in it } }
        assertEquals("\"connected\"", connected["phase"].toString())
        assertEquals("e66118604b", WalkyJson.decodeFromJsonElement(PicoState.serializer(), connected["pico"]!!).board)
        assertFalse("explicit nulls are left out", "error" in connected["pico"]!!.jsonObject)

        // The Pico's state changes 10 times a second; Relay must not get more than that.
        val from = sent.size
        Thread.sleep(1_000)
        val times = sent.drop(from).map { it.first }
        assertTrue("${times.size} lines in a second", times.size in 5..11)
        assertTrue(times.zipWithNext { a, b -> b - a }.all { it >= 95 })
        assertNull(sent.firstOrNull { "seq" in it.second })
    }
}
