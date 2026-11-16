package com.vadymsidorov.walky.core.body

import com.vadymsidorov.walky.core.telemetry.BodyLinkMessage
import com.vadymsidorov.walky.core.telemetry.FakeTelemetry
import com.vadymsidorov.walky.core.telemetry.Topics
import com.vadymsidorov.walky.core.telemetry.WalkyJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class BodyRelayTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pico = FakePico()
    private val telemetry = FakeTelemetry()
    private val body = PicoBody(pico::connect, scope, telemetry)
    private val pairings = CopyOnWriteArrayList<Pair<String, String>>()
    private val relay = BodyRelay(body, { ssid, password -> pairings += ssid to password },
        telemetry.channel(Topics.BodyLink), scope)
    private val links get() = telemetry.values(Topics.BodyLink)

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
    fun publishesTheLinkOnlyWhenItsPhaseOrRoundTripChanges() {
        relay.start()
        assertEquals(BodyLinkMessage("connecting"), eventually { links.firstOrNull() })
        Thread.sleep(500)
        assertEquals("nothing changed, nothing repeated", 1, links.size)

        body.start()
        eventually(what = "connected") { links.any { it.phase == "connected" } }
        val rtt = eventually(what = "rtt") { links.lastOrNull()?.rttMs }
        assertTrue("rtt $rtt ms", rtt in 0..500)
        assertTrue("no repeats: $links", links.zipWithNext().none { (a, b) -> a == b })
        // The Pico's state lines go on their own channel, from PicoBody.
        assertTrue(telemetry.values(Topics.BodyState).isNotEmpty())
    }
}
