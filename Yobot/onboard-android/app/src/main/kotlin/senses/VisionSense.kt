package com.vadymsidorov.yobot.senses

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import com.vadymsidorov.yobot.core.events.Percept
import com.vadymsidorov.yobot.core.events.VisionSummary
import com.vadymsidorov.yobot.core.senses.Sense
import com.vadymsidorov.yobot.core.telemetry.Logger
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Front camera summaries: brightness and frame-to-frame change at ≤2 Hz. Frames stay on the
 * device; the latest JPEG thumbnail is kept in [latestFrame] for a future vision path.
 * Binds to the lifecycle given in [attach], so the camera follows the Activity.
 */
class VisionSense(private val context: Context, private val log: Logger) : Sense {
    override val name = "vision"

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "yobot-vision") }
    private var owner: LifecycleOwner? = null
    private var provider: ProcessCameraProvider? = null
    private var post: ((Percept) -> Unit)? = null

    /** Latest front-camera JPEG, refreshed about once per second. */
    val latestFrame = AtomicReference<ByteArray?>(null)

    // Analyzer-thread state.
    private var previous = ByteArray(0)
    private var current = ByteArray(0)
    private var frameId = 0L
    private var fps = 0f
    private var lastFrameMs = 0L
    private var lastPostMs = 0L
    private var lastThumbMs = 0L

    fun attach(owner: LifecycleOwner) {
        this.owner = owner
    }

    override fun start(post: (Percept) -> Unit) {
        val owner = owner ?: run {
            log.warn("vision not started: no lifecycle owner attached")
            return
        }
        if (!context.hasPermission(Manifest.permission.CAMERA)) {
            log.warn("vision disabled: CAMERA not granted")
            return
        }
        this.post = post
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val p = future.get().also { provider = it }
                if (!p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    log.warn("vision disabled: no front camera")
                    return@addListener
                }
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis())
                log.info("vision bound to front camera")
            } catch (e: Exception) {
                log.error("vision failed to bind", e)
            }
        }, context.mainExecutor)
    }

    override fun stop() {
        post = null
        context.mainExecutor.execute { provider?.unbindAll() }
    }

    private fun analysis(): ImageAnalysis = ImageAnalysis.Builder()
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(Size(320, 240), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                )
                .build(),
        )
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
        .build()
        .also { it.setAnalyzer(executor, ::analyze) }

    private fun analyze(image: ImageProxy) {
        image.use {
            val now = SystemClock.elapsedRealtime()
            if (lastFrameMs > 0) fps += (1000f / (now - lastFrameMs).coerceAtLeast(1) - fps) * 0.1f
            lastFrameMs = now
            frameId++

            val (brightness, change) = sampleLuma(it)
            if (now - lastThumbMs >= THUMB_PERIOD_MS) {
                lastThumbMs = now
                latestFrame.set(thumbnail(it))
            }
            if (now - lastPostMs >= POST_PERIOD_MS) {
                lastPostMs = now
                post?.invoke(VisionSummary(motionScore = change, brightness = brightness, fps = fps, frameId = frameId))
            }
        }
    }

    /** Mean luma and mean absolute change versus the previous frame on a sparse grid, both 0..1. */
    private fun sampleLuma(image: ImageProxy): Pair<Float, Float> {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val cols = image.width / GRID_STEP
        val rows = image.height / GRID_STEP
        if (current.size != cols * rows) {
            current = ByteArray(cols * rows)
            previous = ByteArray(cols * rows)
        }
        var sum = 0L
        var diff = 0L
        var i = 0
        for (row in 0 until rows) {
            val base = row * GRID_STEP * plane.rowStride
            for (col in 0 until cols) {
                val v = buffer.get(base + col * GRID_STEP * plane.pixelStride)
                current[i] = v
                sum += v.toInt() and 0xFF
                diff += abs((v.toInt() and 0xFF) - (previous[i].toInt() and 0xFF))
                i++
            }
        }
        val first = frameId == 1L
        current = previous.also { previous = current }
        val n = (cols * rows).coerceAtLeast(1)
        val brightness = sum / (n * 255f)
        val change = if (first) 0f else (diff / (n * 255f) * MOTION_GAIN).coerceAtMost(1f)
        return brightness to change
    }

    private fun thumbnail(image: ImageProxy): ByteArray? = runCatching {
        val bitmap = image.toBitmap()
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }.getOrNull()

    private companion object {
        const val GRID_STEP = 4
        const val MOTION_GAIN = 6f
        const val POST_PERIOD_MS = 500L
        const val THUMB_PERIOD_MS = 1_000L
    }
}
