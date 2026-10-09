package com.vadymsidorov.walky.ui

import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

/** Sample only while the overlay and activity are visible; PSS collection stays off the UI thread. */
@Composable
internal fun ResourceTickers(window: Window) {
    var reading by remember { mutableStateOf("APP CPU — · MEM — · GPU — ms/frame") }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, window) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // The listener and sampler both run on main; no per-frame Compose state updates.
            var gpuNanos = 0L
            var gpuFrames = 0L
            val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                val duration = metrics.getMetric(FrameMetrics.GPU_DURATION)
                if (duration >= 0 && metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) != 1L) {
                    gpuNanos += duration
                    gpuFrames++
                }
            }
            window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper()))
            try {
                var previousTime = SystemClock.elapsedRealtime()
                var previousCpu = Process.getElapsedCpuTime()
                while (true) {
                    delay(1_000)
                    val now = SystemClock.elapsedRealtime()
                    val cpu = Process.getElapsedCpuTime()
                    val percent = 100.0 * (cpu - previousCpu).coerceAtLeast(0) / (now - previousTime).coerceAtLeast(1)
                    val gpu = if (gpuFrames > 0) {
                        String.format(Locale.US, "%.1f ms/frame", gpuNanos.toDouble() / gpuFrames / 1_000_000)
                    } else {
                        "— ms/frame"
                    }
                    gpuNanos = 0L
                    gpuFrames = 0L
                    val memory = withContext(Dispatchers.Default) { Debug.getPss() / 1024 }
                    reading = String.format(Locale.US, "APP CPU %.0f%% · MEM %d MB · GPU %s", percent, memory, gpu)
                    previousTime = now
                    previousCpu = cpu
                }
            } finally {
                window.removeOnFrameMetricsAvailableListener(listener)
            }
        }
    }
    BasicText(reading, style = TextStyle(color = Color(0xFF5FE3FF), fontFamily = FontFamily.Monospace, fontSize = 11.sp))
}
