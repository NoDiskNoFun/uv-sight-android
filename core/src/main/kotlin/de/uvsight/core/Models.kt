package de.uvsight.core

import kotlinx.serialization.Serializable

/** A finished session as kept on the phone (mirrors the web app's archive entry). */
@Serializable
data class ArchiveSession(
    val key: String,
    val epoch: Long? = null,
    val id: Long? = null,
    /** End of the session as unix millis, null if unknown (then "before importedAt"). */
    val endedAt: Long? = null,
    val importedAt: Long,
    val ends: Int = 0,
    val shots: Int = 0,
    val scored: Int = 0,
    val avg: Double = 0.0,
    val x: Int = 0,
    val min: Int = 0,
    val invalidEnds: Int = 0,
    val invalidArrows: Int = 0,
    val mismatch: Boolean = false,
    val manual: Boolean = false,
    val hidden: Boolean = false,
    val sightId: String? = null,              // the sight this session came from (chip id), null = not known yet
    /** null = not known yet whether the sight has it. */
    val onSight: Boolean? = null,
) {
    val sortTime: Long get() = endedAt ?: importedAt
    val startedAt: Long? get() = endedAt?.let { it - min * 60_000L }
    val hasSightKey: Boolean get() = (epoch ?: 0) > 0 && (id ?: 0) > 0
}

/** One end of a session, kept per session key on the phone. */
@Serializable
data class EndDetail(
    val n: Int,
    val valid: Boolean,
    val arrows: Int,
    val sum: Int? = null,
    val x: Int? = null,
    val scores: List<String>? = null,      // "X", "10" ... "1", "M"
    val reason: String? = null,
    val dist: Int? = null,
    val distAuto: Boolean = false,
    val setup: Int? = null,
    val ang: Double? = null,
    val angSd: Double? = null,
    val angN: Int? = null,
    val cant: Double? = null,
    val cantMax: Double? = null,
    val canted: Int? = null,
    val cantN: Int? = null,
    val hits: List<Hit>? = null,           // arrow positions from photo scoring, face mm (x right, y up)
    val shots: List<ShotInfo>? = null,     // the sight's shots of this end
    val shotOf: List<Int>? = null,         // per hit: index of the shot it was matched to
    val shotConf: List<Double>? = null,    // per hit: confidence of that match (0..1)
    val shotSrc: List<String>? = null,     // per hit: "sure", "guess", "coin" (unresolved, random), "user" (set by hand)
)

@Serializable
data class CfgItem(
    val k: String,
    val v: Double,
    val def: Double,
    val min: Double,
    val max: Double,
    val dec: Int,
    val zero: Boolean,
    val unit: String,
    val d: String,
)

data class HelloInfo(val proto: Int, val fw: String, val name: String, val imu: Boolean, val log: Boolean,
                     val id: String? = null, val sightName: String? = null)

/**
 * A sight this phone has met: its chip id, the name the owner gave it, the Bluetooth address,
 * the log keys seen on it (sessions with these epochs belong to it) and the phone-side
 * preferences kept per sight.
 */
@Serializable
data class SightInfo(
    val id: String,
    val name: String = "",
    val color: Int = 0,                      // index into the app's badge palette
    val address: String? = null,
    val lastSeen: Long = 0,
    val epochs: List<Long> = emptyList(),
    val distM: Int? = null,
    val face: String? = null,
    val arrowMm: Double? = null,
) {
    val label: String get() = name.ifEmpty { "Sight " + id.takeLast(4) }
}

data class StatusInfo(
    val light: Int, val dark: Boolean, val vbat: Double, val pct: Int, val chg: String,
    val led: String, val duty: Int, val bright: Int, val mode: String, val lowbat: Boolean,
    val tilt: Double?, val session: Boolean, val awake: String, val rwarn: Boolean,
    val lastShotG: Double?, val lastShotClip: Boolean, val end: Int?, val endShots: Int?,
)

data class BatInfo(val light: Double, val rest: Double, val full: Int?, val src: String, val rows: Int,
                   val charges: Int, val idleMa: Double, val activeMa: Double, val ledK: Double, val cap: Int)

@Serializable
data class SessionInfo(
    val counter: Boolean, val active: Boolean,
    val epoch: Long? = null, val nextId: Long? = null, val dist: Int? = null, val distSrc: String? = null,
    val end: Int = 1, val endShots: Int = 0, val ends: Int = 0, val invalidEnds: Int = 0,
    val shots: Int = 0, val scored: Int = 0, val sum: Int = 0, val x: Int = 0, val avg: Double = 0.0,
    val min: Int = 0, val pending: Boolean = false,
) {
    val key: String? get() = if (active && epoch != null && nextId != null) "$epoch-$nextId" else null
}

data class LevelInfo(val mode: String, val style: String, val cal: Boolean, val angle: String,
                     val cantSignal: Boolean, val rangeSignal: Boolean)

data class RangeInfo(val setup: Int, val name: String, val state: String, val ends: Int, val dists: Int,
                     val min: Int?, val max: Int?, val speed: Double?, val kmh: Int?, val drag: Double?,
                     val dist: Int, val distSrc: String, val estimated: Boolean, val signal: Boolean,
                     val warn: String, val ok: Boolean)

data class SetupItem(val id: Int, val name: String, val deleted: Boolean, val state: String, val ends: Int,
                     val kmh: Int?, val min: Int?, val max: Int?)

data class SetupsInfo(val active: Int, val list: List<SetupItem>)

data class LogInfo(val ok: Boolean, val jedec: String, val count: Int, val capacity: Int, val last: Long,
                   val clock: Boolean, val migrated: Int, val error: String)

data class EndMsg(val n: Int, val valid: Boolean, val arrows: Int, val sum: Int?, val x: Int?, val avg: Double?,
                  val reason: String?, val dist: Int?, val distSrc: String?, val setup: Int?, val ang: Double?,
                  val angSd: Double?, val angN: Int?, val cant: Double?, val cantMax: Double?, val canted: Int?,
                  val cantN: Int?, val stored: Boolean, val shots: EndShots? = null)

/** One shot as the sight measured it: aiming angle, cant, bow rotation before the impact (deg), and the model's predicted position (cm from the end's mean). */
@Serializable
data class ShotInfo(val i: Int, val ang: Double? = null, val cant: Double? = null, val yaw: Double? = null, val roll: Double? = null,
                    val rate: Double? = null, val px: Double? = null, val py: Double? = null,
                    val t: Double? = null,        // seconds since the end's first shot
                    val hold: Double? = null,     // spread of the aiming angle while holding, deg
                    val holdMs: Int? = null,      // how long the aim stayed steady before the release
                    val drop: Double? = null)     // aiming angle in the last 150..400 ms minus the hold mean, deg (sinking < 0)

/** The aim trace of one shot: angle and cant over the ~1.9 s before the release (ms before the release, oldest first). */
data class AimTrace(val end: Int, val i: Int, val ms: List<Int>, val pitch: List<Double>, val cant: List<Double>)

/** The shots of an end with the model's spreads (cm): "shots" answer and part of the "end" message. */
data class EndShots(val end: Int, val n: Int, val dist: Int?, val sdx: Double, val sdy: Double, val modelN: Int, val matching: Boolean, val shots: List<ShotInfo>)

/** State of the sight's shot matching model for the active setup. */
data class ShotModelInfo(val setup: Int, val on: Boolean, val n: Int, val ky: Double, val sdy: Double, val kx: List<Double>, val sdx: Double)

data class ConfirmMsg(val end: Int, val counted: Int, val entered: Int, val split: Boolean)

data class SlotMsg(val seq: Long?, val epoch: Long?, val id: Long, val start: Long?, val ago: Int?, val ends: Int,
                   val shots: Int, val scored: Int, val avg: Double, val x: Int, val min: Int, val invalidEnds: Int,
                   val invalidArrows: Int, val mismatch: Boolean, val manual: Boolean)

data class EndRecMsg(val seq: Long?, val epoch: Long, val id: Long, val n: Int, val valid: Boolean, val arrows: Int,
                     val skipped: Boolean, val sum: Int?, val x: Int?, val dist: Int?, val auto: Boolean,
                     val setup: Int?, val ang: Double?, val angSd: Double?, val angN: Int?, val cant: Double?,
                     val cantMax: Double?, val canted: Int?, val cantN: Int?, val scores: String?)

data class CalMsg(val step: Int, val state: String, val text: String)

enum class View { STATUS, TRAINING, HISTORY, SETTINGS, CONSOLE }
enum class ConnState { OFF, CONNECTING, CONNECTED }

data class ConsoleLine(val text: String, val kind: String)   // kind: "", "in", "err", "raw"

/** What the UI shows for the mismatch question of the sight. */
data class ConfirmPrompt(val end: Int, val counted: Int, val entered: Int, val canSplit: Boolean)

data class ImportResult(val added: Int, val filled: Int, val skipped: Int, val what: String)

/** Phone-side settings for notifications and the vibration motor. */
@Serializable
data class NotifPrefs(
    val session: Boolean = true,       // ongoing notification while connected / in a session (keeps the link alive in the background)
    val rangeVibe: Boolean = true,     // vibrate with the aiming-range signal while setting up a sight (aiming angle "on")
    val vibeStrength: String = "medium",   // light / medium / strong
    val autoEnd: Boolean = true,       // notification when the sight ended a session on its own
    val lowBat: Boolean = true,        // notification when the sight's battery is low
    val chargeFull: Boolean = true,    // notification when the sight is fully charged
)

/** Phone-side settings of the hints after an end. */
@Serializable
data class HintPrefs(
    val enabled: Boolean = true,       // show hints under the last end and in the session details
    val clickMmAt18: Double = 0.0,     // how far one click of the sight moves the group at 18 m, mm (0 = unknown: hints give centimetres only)
)

/** Phone-side settings of the photo scoring. */
@Serializable
data class PhotoPrefs(
    val face: String = "WA40",           // FaceType name used last
    val arrowMm: Double = 6.0,           // shaft diameter for the line-cutter rule
    val collect: Boolean = false,        // keep photos and marks as training data
    val environment: String = "outdoor", // "outdoor" / "indoor", remembered choice
    val modelConf: Double = 0.4,         // confidence threshold of the detection model
    val faceMarks: List<Double>? = null, // last face marking as image fractions: [cx, cy, e1x, e1y, ...], reused on the next photo
    val source: String = "camera",       // where Score from photo gets the picture: "camera" or "gallery"
    val cameraApp: String = "",          // package of the camera app to use; "" = whatever the system picks
)

/** The sight's aiming-range signal right now (mirrors the LED): warn = "low" / "high" / "", ok = aim fits. */
data class RangeLive(val warn: String = "", val ok: Boolean = false) {
    val active: Boolean get() = warn.isNotEmpty() || ok
}

/** Things the platform layer turns into notifications. */
sealed class CoreEvent {
    data class SessionAutoEnded(val ends: Int, val scored: Int, val avg: Double, val x: Int, val min: Int) : CoreEvent()
    data class LowBattery(val pct: Int) : CoreEvent()
    object ChargeFull : CoreEvent()
}
