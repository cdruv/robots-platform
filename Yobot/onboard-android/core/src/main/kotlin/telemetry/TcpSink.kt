package com.vadymsidorov.yobot.core.telemetry

import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

/**
 * Streams JSON lines to a host listener (e.g. `nc -l 5555`). Reconnects with capped
 * exponential backoff; events arriving while disconnected are dropped and counted.
 */
class TcpSink(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Int = 2_000,
    private val minBackoffMs: Long = 500,
    private val maxBackoffMs: Long = 30_000,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : TelemetrySink {
    override val name = "tcp"

    private val dropped = AtomicLong(0)
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private var backoffMs = minBackoffMs
    private var nextAttemptAtMs = 0L

    override fun write(batch: List<TelemetryEvent>) {
        val out = writer ?: connect() ?: run {
            dropped.addAndGet(batch.size.toLong())
            return
        }
        try {
            for (event in batch) {
                out.write(event.toJsonLine())
                out.write("\n")
            }
            out.flush()
        } catch (e: IOException) {
            dropped.addAndGet(batch.size.toLong())
            disconnect()
        }
    }

    override fun takeDropped(): Long = dropped.getAndSet(0)

    override fun close() = disconnect()

    private fun connect(): BufferedWriter? {
        val now = nowMs()
        if (now < nextAttemptAtMs) return null
        return try {
            val s = Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(host, port), connectTimeoutMs)
            }
            socket = s
            backoffMs = minBackoffMs
            BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)).also { writer = it }
        } catch (e: IOException) {
            nextAttemptAtMs = now + backoffMs
            backoffMs = (backoffMs * 2).coerceAtMost(maxBackoffMs)
            null
        }
    }

    private fun disconnect() {
        runCatching { socket?.close() }
        socket = null
        writer = null
        nextAttemptAtMs = nowMs() + backoffMs
    }
}
