package com.vadymsidorov.walky.senses

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import android.view.Surface
import com.vadymsidorov.walky.core.events.Percept
import com.vadymsidorov.walky.core.senses.Sense
import com.vadymsidorov.walky.core.telemetry.CameraFrameMessage
import com.vadymsidorov.walky.core.telemetry.CameraInfoMessage
import com.vadymsidorov.walky.core.telemetry.Telemetry
import com.vadymsidorov.walky.core.telemetry.Topics
import com.vadymsidorov.walky.core.telemetry.VideoFrame
import java.util.concurrent.Executor
import kotlin.math.abs

/**
 * Front camera video for the recording, posting no percepts. Camera2 renders straight into
 * the H.264 encoder's input surface, so frames never pass through app memory until encoded.
 * Every keyframe carries SPS/PPS and comes once a second, so any keyframe starts playback.
 *
 * `/camera/frame` carries the camera's sensor timestamps, which on a `realtime` timestamp source
 * are boottime, the clock of the rest of telemetry. The encoder's frame times are not: on the
 * Pixel 8 it puts them on the monotonic clock. The first encoded frame shows which clock it
 * is, and from then on the offset to boottime is added and recorded in `/camera/info`.
 */
class CameraSense(context: Context, telemetry: Telemetry) : Sense {
    override val name = "camera"

    private val context = context.applicationContext
    private val log = telemetry.logger("camera")
    private val video = telemetry.channel(Topics.CameraVideo)
    private val frames = telemetry.channel(Topics.CameraFrame)
    private val info = telemetry.channel(Topics.CameraInfo)
    private val cameras = context.getSystemService(CameraManager::class.java)

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    // Touched only on the camera thread.
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var encoder: MediaCodec? = null
    private var input: Surface? = null
    private var config: ByteArray? = null
    private var cameraInfo: CameraInfoMessage? = null
    /** Added to encoder frame times to get boottime; null until the first frame. */
    private var clockOffsetNs: Long? = null

    @Synchronized
    override fun start(post: (Percept) -> Unit) {
        if (thread != null) return
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            log.warn("camera not started: CAMERA not granted")
            return
        }
        val t = HandlerThread("camera").apply { start() }
        val h = Handler(t.looper)
        thread = t
        handler = h
        h.post { runCatching { open(h) }.onFailure { failed("camera failed to start", it) } }
    }

    @Synchronized
    override fun stop() {
        val t = thread ?: return
        val h = handler!!
        thread = null
        handler = null
        // Runs after any pending open, so nothing it creates outlives the stop. The thread stays
        // a while for a camera that opens late, so its callback still runs and closes it.
        h.post { release() }
        h.postDelayed({ t.quitSafely() }, LINGER_MS)
    }

    /** Asks the encoder for a keyframe now, for a viewer that joined or skipped frames. Any thread. */
    fun requestKeyframe() {
        val h = synchronized(this) { handler } ?: return
        h.post {
            runCatching { encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
        }
    }

    private fun current(h: Handler) = synchronized(this) { handler === h }

    @SuppressLint("MissingPermission") // Checked in start.
    private fun open(h: Handler) {
        if (!current(h)) return
        val id = cameras.cameraIdList.firstOrNull {
            cameras.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        } ?: return log.error("camera not started: no front camera")
        val chars = cameras.getCameraCharacteristics(id)
        val fps = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
            .filter { it.upper <= FPS }
            .maxWith(compareBy<Range<Int>> { it.upper }.thenBy { it.lower })

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps.upper)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEYFRAME_INTERVAL_S)
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder = codec
        codec.setCallback(EncoderCallback(codec), h)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        input = surface
        codec.start()

        cameraInfo = info(id, chars, fps).also(info::write)
        cameras.openCamera(id, HandlerExecutor(h), object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (!current(h)) return camera.close()
                device = camera
                runCatching { capture(camera, surface, fps, h) }.onFailure { failed("camera session failed", it) }
            }

            override fun onDisconnected(camera: CameraDevice) {
                log.warn("camera disconnected")
                camera.close()
                if (device === camera) device = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                log.error("camera error $error")
                camera.close()
                if (device === camera) device = null
            }
        })
    }

    private fun capture(camera: CameraDevice, surface: Surface, fps: Range<Int>, h: Handler) {
        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(surface)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)
        }.build()
        val outputs = listOf(OutputConfiguration(surface))
        camera.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, HandlerExecutor(h),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (!current(h)) return s.close()
                    session = s
                    s.setRepeatingRequest(request, FrameCallback(), h)
                    log.info("camera $WIDTH×$HEIGHT at ${fps.lower}-${fps.upper} fps, ${BITRATE / 1_000_000} Mbps")
                }

                override fun onConfigureFailed(s: CameraCaptureSession) = log.error("camera session configuration failed")
            }))
    }

    private fun release() {
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        runCatching { input?.release() }
        session = null
        device = null
        encoder = null
        input = null
        config = null
        cameraInfo = null
        clockOffsetNs = null
    }

    /** Whichever of boottime and the monotonic clock [frameNs] is nearer to is the encoder's. */
    private fun clockOffset(frameNs: Long): Long = clockOffsetNs ?: run {
        val boot = SystemClock.elapsedRealtimeNanos()
        val mono = System.nanoTime()
        val offset = if (abs(frameNs - boot) <= abs(frameNs - mono)) 0L else boot - mono
        clockOffsetNs = offset
        cameraInfo?.copy(timestampOffsetNs = offset)?.let(info::write)
        offset
    }

    private fun failed(message: String, error: Throwable) {
        log.error(message, error)
        release()
    }

    private inner class EncoderCallback(private val codec: MediaCodec) : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (codec !== encoder) return
            val buffer = codec.getOutputBuffer(index)
            if (buffer != null && info.size > 0) {
                val data = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(data)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    config = data
                } else {
                    val keyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    // Should the encoder ignore KEY_PREPEND_HEADER_TO_SYNC_FRAMES, add the headers here.
                    val frame = if (keyframe && !startsWithSps(data)) config?.plus(data) ?: data else data
                    val timeNs = info.presentationTimeUs * 1_000 + clockOffset(info.presentationTimeUs * 1_000)
                    video.write(timeNs, VideoFrame(timeNs, frame, keyframe, FRAME_ID))
                }
            }
            codec.releaseOutputBuffer(index, false)
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            if (codec === this.codec && codec === encoder) failed("video encoder failed", e)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
    }

    private inner class FrameCallback : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            frames.write(timestamp, CameraFrameMessage(
                frameNumber = result.frameNumber,
                sensorTimestampNs = timestamp,
                exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
                frameDurationNs = result.get(CaptureResult.SENSOR_FRAME_DURATION),
                focusDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                rollingShutterSkewNs = result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW),
            ))
        }
    }

    private fun info(id: String, chars: CameraCharacteristics, fps: Range<Int>): CameraInfoMessage {
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val pixels = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        return CameraInfoMessage(
            cameraId = id,
            facing = "front",
            width = WIDTH,
            height = HEIGHT,
            fpsRange = listOf(fps.lower, fps.upper),
            bitrate = BITRATE,
            keyframeIntervalS = KEYFRAME_INTERVAL_S,
            intrinsics = chars.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)?.toList(),
            distortion = chars.get(CameraCharacteristics.LENS_DISTORTION)?.toList(),
            poseRotation = chars.get(CameraCharacteristics.LENS_POSE_ROTATION)?.toList(),
            poseTranslation = chars.get(CameraCharacteristics.LENS_POSE_TRANSLATION)?.toList(),
            poseReference = chars.get(CameraCharacteristics.LENS_POSE_REFERENCE),
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION),
            activeArray = active?.let { listOf(it.left, it.top, it.right, it.bottom) },
            pixelArray = pixels?.let { listOf(it.width, it.height) },
            timestampSource = when (chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)) {
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> "realtime"
                else -> "unknown"
            },
        )
    }

    private class HandlerExecutor(private val handler: Handler) : Executor {
        override fun execute(command: Runnable) {
            handler.post(command)
        }
    }

    private companion object {
        const val WIDTH = 1920
        const val HEIGHT = 1080
        const val FPS = 60
        const val BITRATE = 10_000_000
        const val KEYFRAME_INTERVAL_S = 1
        const val FRAME_ID = "camera_front"
        const val LINGER_MS = 3_000L

        /** Whether the first Annex B NAL unit is an SPS (type 7). */
        fun startsWithSps(data: ByteArray): Boolean {
            val start = when {
                data.size > 4 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte() && data[3] == 1.toByte() -> 4
                data.size > 3 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 1.toByte() -> 3
                else -> return false
            }
            return data[start].toInt() and 0x1F == 7
        }
    }
}
