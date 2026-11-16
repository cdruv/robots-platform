package com.vadymsidorov.walky.telemetry

import android.util.Log
import com.vadymsidorov.walky.core.telemetry.TelemetryEvent
import com.vadymsidorov.walky.core.telemetry.TelemetrySink
import com.vadymsidorov.walky.core.telemetry.isLog

/** One compact line per log event under the `walky` tag: `adb logcat -s walky`. */
class LogcatSink(private val maxPayloadChars: Int = 600) : TelemetrySink {
    override val name = "logcat"

    override fun write(batch: List<TelemetryEvent>) {
        for (event in batch) {
            if (!event.isLog) continue
            val payload = event.payload.toString().let {
                if (it.length > maxPayloadChars) it.take(maxPayloadChars) + "…" else it
            }
            val line = "#${event.seq} ${event.source}/${event.kind} $payload"
            when (event.kind) {
                "log.error" -> Log.e(TAG, line)
                "log.warn" -> Log.w(TAG, line)
                else -> Log.i(TAG, line)
            }
        }
    }

    companion object {
        const val TAG = "walky"
    }
}
