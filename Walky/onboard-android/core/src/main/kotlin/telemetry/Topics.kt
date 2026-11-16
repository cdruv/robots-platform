package com.vadymsidorov.walky.core.telemetry

import com.vadymsidorov.walky.core.output.FaceState
import com.vadymsidorov.walky.core.output.Reaction
import com.vadymsidorov.walky.core.senses.HearingStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Every telemetry channel: topic, schema, rate and payload type. JSON topics carry the
 * payload classes below as JSON; the schemas are JSON Schema and must match them. All times
 * are nanoseconds on one clock, boottime on the phone (`SystemClock.elapsedRealtimeNanos`),
 * which is also each message's MCAP log time.
 *
 * Changing a payload: change its schema here too. Adding a topic: add it to [all]; ids follow
 * the order of [all], so append rather than insert.
 */
object Topics {
    /** Percepts, logs and `telemetry/dropped`: [TelemetryEvent] as before MCAP. */
    val Events = Topic.json("/events", TelemetryEvent.serializer(), rate = "on event", schema = """
        {"type":"object","required":["seq","tsWallMs","tsMonoNs","source","kind","payload"],"properties":{
          "seq":{"type":"integer","description":"per session, from 1"},
          "tsWallMs":{"type":"integer","description":"Unix time, ms"},
          "tsMonoNs":{"type":"integer","description":"telemetry clock, ns"},
          "source":{"type":"string"},
          "kind":{"type":"string","description":"log.info|log.warn|log.error for logs, else the event type"},
          "payload":{}}}
    """)

    /** Live stream only: the answer to a client's ping, to that client alone. */
    val Pong = Topic.json("/link/pong", PongMessage.serializer(), rate = "per ping", schema = """
        {"type":"object","required":["ping"],"properties":{"ping":{"type":"integer"}}}
    """)

    // IMU: every sensor event, in batches of at most 20 ms per sensor.
    val ImuAccel = imu("/imu/accel", "TYPE_ACCELEROMETER, m/s², with gravity", "~400 Hz")
    val ImuGyro = imu("/imu/gyro", "TYPE_GYROSCOPE, rad/s", "~400 Hz")
    val ImuRotation = imu("/imu/rotation", "TYPE_GAME_ROTATION_VECTOR, unit quaternion x y z w", "~200 Hz")
    val ImuGravity = imu("/imu/gravity", "TYPE_GRAVITY, m/s², fused", "~50 Hz")
    val ImuLinear = imu("/imu/linear", "TYPE_LINEAR_ACCELERATION, m/s², fused", "~50 Hz")

    val ImuAccuracy = Topic.json("/imu/accuracy", ImuAccuracyChange.serializer(), rate = "on change", schema = """
        {"type":"object","required":["sensor","accuracy"],"properties":{
          "sensor":{"type":"string","description":"topic of the sensor"},
          "accuracy":{"type":"integer","description":"SensorManager.SENSOR_STATUS_*"}}}
    """)

    val ImuInfo = Topic.json("/imu/info", ImuInfoMessage.serializer(), rate = "at start, then with measured rates", latched = true, schema = """
        {"type":"object","required":["sensors"],"properties":{"sensors":{"type":"array","items":{"type":"object","properties":{
          "topic":{"type":"string"},"type":{"type":"integer"},"name":{"type":"string"},"vendor":{"type":"string"},
          "version":{"type":"integer"},"maxRange":{"type":"number"},"resolution":{"type":"number"},
          "minDelayUs":{"type":"integer"},"maxDelayUs":{"type":"integer"},"fifoMax":{"type":"integer"},
          "fifoReserved":{"type":"integer"},"requestedPeriodUs":{"type":"integer"},
          "axes":{"type":"string"},"units":{"type":"string"},
          "measuredHz":{"type":"number","description":"absent until measured"}}}}}}
    """)

    val CameraVideo = Topic<VideoFrame>(
        name = "/camera/video",
        schemaName = "foxglove.CompressedVideo",
        schemaEncoding = "protobuf",
        schema = resource("/foxglove/CompressedVideo.fds"),
        messageEncoding = "protobuf",
        rate = "60 fps",
        encode = { it.encode() },
        keyframe = { it.keyframe },
    )

    val CameraFrame = Topic.json("/camera/frame", CameraFrameMessage.serializer(), rate = "per frame", schema = """
        {"type":"object","required":["frameNumber","sensorTimestampNs"],"properties":{
          "frameNumber":{"type":"integer"},
          "sensorTimestampNs":{"type":"integer","description":"SENSOR_TIMESTAMP on the telemetry clock: start of exposure of the first row"},
          "exposureNs":{"type":"integer"},"iso":{"type":"integer"},"frameDurationNs":{"type":"integer"},
          "focusDistance":{"type":"number","description":"diopters"},"rollingShutterSkewNs":{"type":"integer"}}}
    """)

    val CameraInfo = Topic.json("/camera/info", CameraInfoMessage.serializer(), rate = "at start", latched = true, schema = """
        {"type":"object","properties":{
          "cameraId":{"type":"string"},"facing":{"type":"string"},"width":{"type":"integer"},"height":{"type":"integer"},
          "fpsRange":{"type":"array","items":{"type":"integer"}},"bitrate":{"type":"integer"},"keyframeIntervalS":{"type":"integer"},
          "intrinsics":{"type":"array","items":{"type":"number"},"description":"LENS_INTRINSIC_CALIBRATION fx fy cx cy s, active-array pixels"},
          "distortion":{"type":"array","items":{"type":"number"},"description":"LENS_DISTORTION k1 k2 k3 p1 p2"},
          "poseRotation":{"type":"array","items":{"type":"number"}},"poseTranslation":{"type":"array","items":{"type":"number"}},
          "poseReference":{"type":"integer"},"sensorOrientation":{"type":"integer"},
          "activeArray":{"type":"array","items":{"type":"integer"}},"pixelArray":{"type":"array","items":{"type":"integer"}},
          "timestampSource":{"type":"string","description":"the camera's SENSOR_INFO_TIMESTAMP_SOURCE: realtime or unknown"},
          "timestampOffsetNs":{"type":"integer","description":"added to the encoder's frame times to put /camera/video on the boottime clock; already applied to its messages"}}}
    """)

    val FaceStateTopic = Topic.json("/face/state", FaceState.serializer(), rate = "on change", latched = true, schema = """
        {"type":"object","properties":{
          "expression":{"enum":["Idle","Happy","Thinking","Alert","Sleep"]},
          "mouth":{"enum":["Idle","Listening","Speaking","Processing"]},
          "mouthLevel":{"type":"number","minimum":0,"maximum":1}}}
    """)

    val FaceReaction = Topic.json("/face/reaction", FaceReactionMessage.serializer(), rate = "on reaction", schema = """
        {"type":"object","required":["reaction"],"properties":{"reaction":{"enum":["Blink","DoubleBlink","Glance","Startle"]}}}
    """)

    /** Each line the phone sends the Pico, as sent; the protocol is in `firmware/pico/src/body.py`. */
    val BodyCommand = Topic.json("/body/command", JsonElement.serializer(), rate = "per command", schema = """
        {"type":"object","required":["t","n"],"properties":{"t":{"enum":["legs","limp","trim","save"]},"n":{"type":"integer"},
          "l":{"type":"number"},"r":{"type":"number"},"steps":{"type":"array","items":{"type":"integer"}}}}
    """)

    /** Each state line the Pico sends, as received. */
    val BodyState = Topic.json("/body/state", JsonElement.serializer(), rate = "~10 Hz", latched = true, schema = """
        {"type":"object","description":"the Pico's state line, defined in firmware/pico/src/body.py","properties":{
          "board":{"type":"string"},"mode":{"enum":["limp","active"]},"target":{"type":"array","items":{"type":"number"}},
          "pulses":{"type":"array","items":{"type":"integer"}},"trim":{"type":"array","items":{"type":"integer"}},
          "saved":{"type":"array","items":{"type":"integer"}},"stored":{"type":"boolean"},"n":{"type":"integer"},"up_ms":{"type":"integer"}}}
    """)

    val BodyLink = Topic.json("/body/link", BodyLinkMessage.serializer(), rate = "on change", latched = true, schema = """
        {"type":"object","required":["phase"],"properties":{
          "phase":{"type":"string","description":"connected, or what the phone waits for: unpaired, declined, searching, connecting"},
          "rttMs":{"type":"integer"}}}
    """)

    val Hearing = Topic.json("/hearing/status", HearingStatus.serializer(), rate = "on change, ~10-20 Hz while listening", latched = true, schema = """
        {"type":"object","properties":{
          "state":{"enum":["Stopped","NoPermission","Starting","Listening","Speech","Muted","Error"]},
          "muted":{"type":"boolean"},"engine":{"type":"string"},"partial":{"type":"string"},"lastFinal":{"type":"string"},
          "lastError":{"type":"string"},"rmsDb":{"type":"number"},"sessions":{"type":"integer"}}}
    """)

    val all: List<Topic<*>> = listOf(
        Events, Pong,
        ImuAccel, ImuGyro, ImuRotation, ImuGravity, ImuLinear, ImuAccuracy, ImuInfo,
        CameraVideo, CameraFrame, CameraInfo,
        FaceStateTopic, FaceReaction,
        BodyCommand, BodyState, BodyLink,
        Hearing,
    )

    val catalog: Catalog by lazy { Catalog(all) }

    private fun imu(name: String, what: String, rate: String) = Topic.json(name, ImuBatch.serializer(), rate = rate, schema = """
        {"type":"object","description":"$what. Device axes: x right, y up, z out of the screen.",
         "required":["accuracy","t","x","y","z"],"properties":{
          "accuracy":{"type":"integer","description":"SensorManager.SENSOR_STATUS_* for every sample"},
          "t":{"type":"array","items":{"type":"integer"},"description":"SensorEvent.timestamp per sample, ns"},
          "x":{"type":"array","items":{"type":"number"}},"y":{"type":"array","items":{"type":"number"}},
          "z":{"type":"array","items":{"type":"number"}},"w":{"type":"array","items":{"type":"number"}}}}
    """)

    private fun resource(path: String): ByteArray =
        Topics::class.java.getResourceAsStream(path)?.use { it.readBytes() } ?: error("missing resource $path")
}

@Serializable
data class PongMessage(val ping: Long)

/** Samples of one sensor, one entry per event in each array. */
@Serializable
class ImuBatch(
    val accuracy: Int,
    val t: LongArray,
    val x: FloatArray,
    val y: FloatArray,
    val z: FloatArray,
    val w: FloatArray? = null,
)

@Serializable
data class ImuAccuracyChange(val sensor: String, val accuracy: Int)

@Serializable
data class ImuInfoMessage(val sensors: List<ImuSensorInfo>)

@Serializable
data class ImuSensorInfo(
    val topic: String,
    val type: Int,
    val name: String,
    val vendor: String,
    val version: Int,
    val maxRange: Float,
    val resolution: Float,
    val minDelayUs: Int,
    val maxDelayUs: Int,
    val fifoMax: Int,
    val fifoReserved: Int,
    val requestedPeriodUs: Int,
    val axes: String,
    val units: String,
    val measuredHz: Float? = null,
)

@Serializable
data class CameraFrameMessage(
    val frameNumber: Long,
    val sensorTimestampNs: Long,
    val exposureNs: Long? = null,
    val iso: Int? = null,
    val frameDurationNs: Long? = null,
    val focusDistance: Float? = null,
    val rollingShutterSkewNs: Long? = null,
)

@Serializable
data class CameraInfoMessage(
    val cameraId: String,
    val facing: String,
    val width: Int,
    val height: Int,
    val fpsRange: List<Int>,
    val bitrate: Int,
    val keyframeIntervalS: Int,
    val intrinsics: List<Float>? = null,
    val distortion: List<Float>? = null,
    val poseRotation: List<Float>? = null,
    val poseTranslation: List<Float>? = null,
    val poseReference: Int? = null,
    val sensorOrientation: Int? = null,
    val activeArray: List<Int>? = null,
    val pixelArray: List<Int>? = null,
    val timestampSource: String,
    val timestampOffsetNs: Long = 0,
)

@Serializable
data class FaceReactionMessage(val reaction: Reaction)

@Serializable
data class BodyLinkMessage(val phase: String, val rttMs: Int? = null)
