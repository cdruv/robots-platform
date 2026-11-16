package com.vadymsidorov.walky.telemetry

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Session recordings in the app's external files (`adb pull
 * /sdcard/Android/data/<package>/files/recordings`). The oldest files are deleted once
 * all of them together pass [capBytes]. Names sort by start time.
 */
class Recordings(context: Context, private val capBytes: Long = 20L shl 30) {
    private val dir = context.getExternalFilesDir("recordings") ?: File(context.filesDir, "recordings")

    /** Creates the file for a session starting now, after making room for it. */
    fun create(session: String, nowMs: Long): FileOutputStream {
        dir.mkdirs()
        prune()
        val stamp = STAMP.format(Instant.ofEpochMilli(nowMs))
        return FileOutputStream(File(dir, "walky-$stamp-$session.mcap"))
    }

    private fun prune() {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".mcap") }.orEmpty().sortedBy { it.name }
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= capBytes) break
            total -= file.length()
            file.delete()
        }
    }

    private companion object {
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    }
}
