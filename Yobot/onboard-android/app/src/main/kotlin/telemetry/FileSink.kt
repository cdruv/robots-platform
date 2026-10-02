package com.vadymsidorov.yobot.telemetry

import com.vadymsidorov.yobot.core.telemetry.TelemetryEvent
import com.vadymsidorov.yobot.core.telemetry.TelemetrySink
import com.vadymsidorov.yobot.core.telemetry.toJsonLine
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

/**
 * JSON lines in [dir], rotated at [maxBytes] keeping the newest [keepFiles].
 * Pull with `adb pull /sdcard/Android/data/com.vadymsidorov.yobot/files/telemetry`.
 */
class FileSink(
    private val dir: File,
    private val maxBytes: Long = 5L * 1024 * 1024,
    private val keepFiles: Int = 5,
) : TelemetrySink {
    override val name = "file"

    private var writer: BufferedWriter? = null
    private var written = 0L

    override fun write(batch: List<TelemetryEvent>) {
        val out = writer ?: open()
        for (event in batch) {
            val line = event.toJsonLine()
            out.write(line)
            out.write("\n")
            written += line.length + 1
        }
        out.flush()
        if (written >= maxBytes) close()
    }

    override fun close() {
        runCatching { writer?.close() }
        writer = null
    }

    private fun open(): BufferedWriter {
        dir.mkdirs()
        val file = File(dir, "telemetry-${System.currentTimeMillis()}.jsonl")
        written = 0
        prune()
        return BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8)).also { writer = it }
    }

    private fun prune() {
        val files = dir.listFiles { f -> f.name.startsWith("telemetry-") && f.name.endsWith(".jsonl") } ?: return
        files.sortedByDescending { it.name }.drop(keepFiles - 1).forEach { it.delete() }
    }
}
