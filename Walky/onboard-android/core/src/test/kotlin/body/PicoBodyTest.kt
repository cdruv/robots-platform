package com.vadymsidorov.walky.core.body

import com.vadymsidorov.walky.core.output.LegTargets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class PicoBodyTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val logger = RecordingLogger()
    private val picos = mutableListOf<FakePico>()

    private fun pico(heartbeat: Boolean = true) = FakePico(heartbeat).also { picos += it }

    private fun connected(pico: FakePico): PicoBody {
        val body = PicoBody(pico::connect, scope, logger).also { it.start() }
        eventually(what = "link up") { body.state.value.pico }
        return body
    }

    @After
    fun tearDown() {
        scope.cancel()
        picos.forEach { it.close() }
    }

    @Test
    fun publishesThePicoStateAsTheFirmwareWritesIt() {
        val pico = pico(heartbeat = false)
        val body = PicoBody(pico::connect, scope, logger).also { it.start() }
        assertEquals(BodyLink(BodyLink.Phase.Connecting), body.state.value)
        eventually { pico.connections == 1 }
        pico.state(mode = "active", trim = "[2, -1]", saved = "[2, 0]", stored = true,
            error = """{"n": 4, "msg": "Unknown command"}""")

        val link = eventually { body.state.value.takeIf { it.pico != null } }
        assertEquals(BodyLink.Phase.Connected, link.phase)
        assertEquals(
            PicoState(v = 1, board = "e66118604b", mode = PicoState.Mode.Active, target = listOf(12.5f, -30f),
                pulses = listOf(1638, 1830), trim = listOf(2, -1), saved = listOf(2, 0), stored = true,
                slew = 360, n = link.pico!!.n, error = PicoError(4, "Unknown command"), upMs = link.pico!!.upMs),
            link.pico,
        )
        assertTrue(logger.lines.toString(), logger.lines.any { it.startsWith("info Pico link up") })
        assertTrue(logger.lines.toString(), logger.lines.any { it == "warn Pico: Unknown command" })
    }

    @Test
    fun eachCommandIsOneLineInTheFirmwareFormatAndNothingIsResent() {
        val pico = pico()
        val body = connected(pico)
        body.drive(LegTargets(12.5f, -30f))
        val legs = pico.next()!!
        body.setTrim(2, -18)
        body.saveTrim()
        body.limp()

        val sent = listOf(legs) + List(3) { pico.next()!! }
        assertEquals(
            listOf("""{"t":"legs","l":12.5,"r":-30.0}""", """{"t":"trim","steps":[2,-18]}""", """{"t":"save"}""", """{"t":"limp"}"""),
            sent.map { line -> JsonObject(line.filterKeys { it != "n" }).toString() },
        )
        val numbers = sent.map { it["n"].toString().toLong() }
        assertEquals("n counts up within the connection", numbers.sorted().distinct(), numbers)
        assertNull("a target is sent once; holding a pose is the driver's job", pico.next(timeoutMs = 400))
    }

    @Test
    fun targetsThatAreNotFiniteAreNotSent() {
        val pico = pico()
        val body = connected(pico)
        body.drive(LegTargets(Float.NaN, 0f))
        body.drive(LegTargets(0f, Float.POSITIVE_INFINITY))
        body.drive(LegTargets(1f, 2f))
        assertEquals("1.0", pico.next()!!["l"].toString())
    }

    @Test
    fun silenceFromThePicoForASecondCountsAsLinkDownAndItReconnects() {
        val pico = pico(heartbeat = false)
        val body = PicoBody(pico::connect, scope, logger).also { it.start() }
        eventually { pico.connections == 1 }
        pico.state()
        eventually { body.state.value.pico }
        val silentSince = System.nanoTime()
        eventually(what = "link down") { body.state.value == BodyLink() }
        val elapsedMs = (System.nanoTime() - silentSince) / 1_000_000
        assertTrue("went down after $elapsedMs ms", elapsedMs in 800..2_000)
        assertTrue(logger.lines.toString(), logger.lines.any { it.startsWith("warn Pico link down: no state") })
        eventually(what = "reconnect") { pico.connections == 2 }
    }

    @Test
    fun commandsGivenWhileTheLinkIsDownAreNotReplayedAfterReconnect() {
        val pico = pico()
        val body = connected(pico)
        pico.dropClient()
        eventually(what = "link down") { body.state.value.pico == null }
        body.drive(LegTargets(45f, 45f))
        body.saveTrim()
        eventually(what = "reconnect") { pico.connections == 2 && body.state.value.pico != null }
        body.limp()
        assertEquals("\"limp\"", pico.next()!!["t"].toString())
    }

    @Test
    fun roundTripTimeComesFromThePing() {
        val pico = pico()
        val body = connected(pico)
        val rtt = eventually { body.state.value.rttMs }
        assertTrue("rtt $rtt ms", rtt in 0..500)
    }

    @Test
    fun connectFailuresAreRetriedAndLoggedOnce() {
        val pico = pico()
        val attempts = AtomicInteger()
        val body = PicoBody({
            if (attempts.incrementAndGet() <= 3) throw IOException("no route")
            pico.connect()
        }, scope, logger).also { it.start() }
        eventually(what = "link up") { body.state.value.pico }
        assertEquals(1, logger.lines.count { it.startsWith("warn Pico connect failed") })
    }

    @Test
    fun aPicoSpeakingAnotherProtocolVersionIsNotTreatedAsConnected() {
        val pico = pico(heartbeat = false)
        val body = PicoBody(pico::connect, scope, logger).also { it.start() }
        eventually { pico.connections == 1 }
        pico.state(version = 2)
        eventually { logger.lines.any { "body v2" in it } }
        assertNull(body.state.value.pico)
    }

    @Test
    fun closeDropsTheSocketAndStaysDown() {
        val pico = pico()
        val body = connected(pico)
        body.close()
        eventually(what = "link down") { body.state.value == BodyLink() }
        Thread.sleep(600)
        assertEquals(1, pico.connections)
    }

    @Test
    fun outboxKeepsOnlyTheNewestTargetAndALimpDiscardsIt() {
        val outbox = BodyOutbox()
        val first = BodyCommand.Drive(LegTargets(1f, 1f))
        val second = BodyCommand.Drive(LegTargets(2f, 2f))
        outbox.offer(first)
        outbox.offer(BodyCommand.Trim(1, 1))
        outbox.offer(second)
        assertEquals(listOf(BodyCommand.Trim(1, 1), second, null), List(3) { outbox.poll() })

        outbox.offer(first)
        outbox.offer(BodyCommand.Limp)
        assertEquals(listOf(BodyCommand.Limp, null), List(2) { outbox.poll() })

        outbox.offer(BodyCommand.Limp)
        outbox.offer(second)
        assertEquals(listOf(BodyCommand.Limp, second, null), List(3) { outbox.poll() })
    }
}
