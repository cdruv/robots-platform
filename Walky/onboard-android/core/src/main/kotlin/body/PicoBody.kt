package com.vadymsidorov.walky.core.body

import com.vadymsidorov.walky.core.output.LegTargets
import com.vadymsidorov.walky.core.output.Legs
import com.vadymsidorov.walky.core.telemetry.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * The phone's connection to the Pico: keeps one socket open, publishes the Pico's state and
 * sends commands. [connect] suspends until it can return a connected socket, or throws; it is
 * called again, with backoff, whenever the link is down.
 *
 * Commands given while the link is down are dropped, never replayed after a reconnect.
 */
class PicoBody(
    private val connect: suspend () -> Socket,
    private val scope: CoroutineScope,
    private val logger: Logger,
    private val monoMs: () -> Long = { System.nanoTime() / 1_000_000 },
) : Legs {
    private val current = MutableStateFlow(BodyLink())
    override val state: StateFlow<BodyLink> = current.asStateFlow()

    private val outbox = BodyOutbox()
    private var job: Job? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var pingN = 0L
    @Volatile private var pingAtMs = 0L

    @Synchronized
    fun start() {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) { run() }
    }

    override fun drive(targets: LegTargets) {
        if (targets.leftDeg.isFinite() && targets.rightDeg.isFinite()) offer(BodyCommand.Drive(targets))
    }

    override fun limp() = offer(BodyCommand.Limp)

    /** Tries a trim without storing it: half-degree steps, -18..18 each. */
    fun setTrim(left: Int, right: Int) = offer(BodyCommand.Trim(left, right))

    /** Stores the trim being tried. It has worked when the state shows `stored` and `saved == trim`. */
    fun saveTrim() = offer(BodyCommand.Save)

    /** Ends the link for good. The Pico lets the legs go limp when the socket closes. */
    @Synchronized
    fun close() {
        job?.cancel()
        runCatching { socket?.close() }
    }

    private fun offer(command: BodyCommand) {
        if (socket != null) outbox.offer(command)
    }

    private suspend fun run() {
        var failures = 0
        while (true) {
            val socket = try {
                connect()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (failures++ == 0) logger.warn("Pico connect failed; retrying", e)
                delay((CONNECT_BACKOFF_MS shl minOf(failures - 1, 3)).toLong())
                continue
            }
            failures = 0
            outbox.clear()
            this.socket = socket
            val reason = try {
                currentCoroutineContext().ensureActive()
                serve(socket)
            } finally {
                this.socket = null
                runCatching { socket.close() }
                current.value = BodyLink()
            }
            logger.warn("Pico link down: $reason")
            delay(CONNECT_BACKOFF_MS.toLong())
        }
    }

    /** Runs one connection until it fails; returns why. */
    private suspend fun serve(socket: Socket): String = coroutineScope {
        val helpers = launch {
            launch { write(socket) }
            while (true) {
                outbox.offer(BodyCommand.Ping)
                delay(PING_INTERVAL_MS)
            }
        }
        try {
            read(socket)
        } catch (e: SocketTimeoutException) {
            "no state for $STATE_TIMEOUT_MS ms"
        } catch (e: IOException) {
            e.message ?: e.toString()
        } catch (e: IllegalArgumentException) {
            // SerializationException: a state line this build cannot read.
            "unreadable state: ${e.message}"
        } finally {
            helpers.cancel()
            // Also frees a writer blocked on a full socket, which cancellation cannot reach.
            runCatching { socket.close() }
        }
    }

    private fun read(socket: Socket): Nothing {
        socket.tcpNoDelay = true
        // Android reports a lost Wi-Fi network only after 8.5-10 s; the 10 Hz state is the heartbeat.
        socket.soTimeout = STATE_TIMEOUT_MS
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        var rttMs: Int? = null
        var error: PicoError? = null
        while (true) {
            val line = reader.readLine() ?: throw IOException("closed by the Pico")
            val pico = BodyProtocol.decodeState(line) ?: continue
            if (pico.v != BodyProtocol.VERSION) throw IOException("Pico speaks body v${pico.v}, this app v${BodyProtocol.VERSION}")
            val sent = pingN
            if (sent != 0L && pico.n != null && pico.n >= sent) {
                rttMs = (monoMs() - pingAtMs).toInt()
                pingN = 0
            }
            if (current.value.pico == null) {
                logger.info("Pico link up: board ${pico.board}, trim ${pico.trim}${if (pico.stored) "" else " (not calibrated)"}")
            }
            if (pico.error != null && pico.error != error) logger.warn("Pico: ${pico.error.msg}")
            error = pico.error
            current.value = BodyLink(BodyLink.Phase.Connected, pico, rttMs)
        }
    }

    private suspend fun write(socket: Socket) {
        var n = 0L
        try {
            val out = socket.getOutputStream()
            val lines = StringBuilder()
            while (true) {
                outbox.await()
                while (true) {
                    val command = outbox.poll() ?: break
                    n++
                    if (command == BodyCommand.Ping) {
                        pingAtMs = monoMs()
                        pingN = n
                    }
                    lines.append(BodyProtocol.encode(command, n)).append('\n')
                }
                out.write(lines.toString().toByteArray(Charsets.UTF_8))
                out.flush()
                lines.setLength(0)
            }
        } catch (_: IOException) {
            // The reader reports the failure; closing makes sure it sees one.
            runCatching { socket.close() }
        }
    }

    private companion object {
        const val STATE_TIMEOUT_MS = 1_000
        const val PING_INTERVAL_MS = 1_000L
        const val CONNECT_BACKOFF_MS = 250
    }
}

/**
 * What is waiting to be sent. Leg targets share one slot, newest wins; everything else is
 * sent in order, ahead of the slot. A limp empties the slot, so no older target follows it.
 */
internal class BodyOutbox {
    private val commands = ArrayDeque<BodyCommand>()
    private var drive: BodyCommand.Drive? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    fun offer(command: BodyCommand) {
        synchronized(this) {
            if (command is BodyCommand.Drive) {
                drive = command
            } else {
                if (command == BodyCommand.Limp) drive = null
                if (commands.size == CAPACITY) commands.removeFirst()
                commands.addLast(command)
            }
        }
        wake.trySend(Unit)
    }

    @Synchronized
    fun poll(): BodyCommand? = commands.removeFirstOrNull() ?: drive.also { drive = null }

    @Synchronized
    fun clear() {
        commands.clear()
        drive = null
    }

    /** Suspends until something may be waiting. */
    suspend fun await() = wake.receive()

    private companion object {
        const val CAPACITY = 32
    }
}
