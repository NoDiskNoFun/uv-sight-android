package de.uvsight.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

const val PROTO_EXPECTED = 19
const val NUS_SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
const val NUS_RX = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"   // app -> device (write)
const val NUS_TX = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"   // device -> app (notify)
const val DEVICE_NAME_PREFIX = "UV-Sight"

/** One JSON line from the sight, with typed accessors. */
class Msg(val t: String, val obj: JsonObject) {
    private fun prim(k: String): JsonPrimitive? = (obj[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }
    fun str(k: String): String? = prim(k)?.contentOrNull
    fun int(k: String): Int? = prim(k)?.intOrNull ?: prim(k)?.doubleOrNull?.toInt()
    fun long(k: String): Long? = prim(k)?.longOrNull ?: prim(k)?.doubleOrNull?.toLong()
    fun dbl(k: String): Double? = prim(k)?.doubleOrNull
    fun bool(k: String): Boolean? = prim(k)?.booleanOrNull
    fun has(k: String): Boolean = obj[k] != null && obj[k] !is JsonNull
    fun objs(k: String): List<Msg> = (obj[k] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let { o -> Msg("", o) } } ?: emptyList()
    fun dbls(k: String): List<Double> = (obj[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull } ?: emptyList()

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        fun parse(line: String): Msg? {
            if (!line.startsWith("{")) return null
            return try {
                val o = json.parseToJsonElement(line).jsonObject
                Msg((o["t"] as? JsonPrimitive)?.contentOrNull ?: return null, o)
            } catch (e: Exception) { null }
        }
    }
}

fun Msg.toHello() = HelloInfo(int("proto") ?: 0, str("fw") ?: "?", str("name") ?: "", bool("imu") ?: false, bool("log") ?: false,
    id = str("id")?.takeIf { it.isNotEmpty() }, sightName = str("sname")?.takeIf { it.isNotEmpty() })

fun Msg.toStatus() = StatusInfo(
    light = int("light") ?: 0, dark = bool("dark") ?: false, vbat = dbl("vbat") ?: 0.0, pct = int("pct") ?: 0,
    chg = str("chg") ?: "no USB", led = str("led") ?: "off", duty = int("duty") ?: 0, bright = int("bright") ?: 0,
    mode = str("mode") ?: "auto", lowbat = bool("lowbat") ?: false, tilt = dbl("tilt"), session = bool("session") ?: false,
    awake = str("awake") ?: "", rwarn = bool("rwarn") ?: false, lastShotG = dbl("lastShotG"),
    lastShotClip = bool("lastShotClip") ?: false, end = int("end"), endShots = int("endShots"),
)

fun Msg.toBat() = BatInfo(dbl("light") ?: 0.0, dbl("rest") ?: 0.0, int("full"), str("src") ?: "estimate",
    int("rows") ?: 0, int("charges") ?: 0, dbl("idleMa") ?: 0.0, dbl("activeMa") ?: 0.0, dbl("ledK") ?: 1.0, int("cap") ?: 0)

fun Msg.toSession() = SessionInfo(
    counter = bool("counter") ?: false, active = bool("active") ?: false, epoch = long("epoch"), nextId = long("nextId"),
    dist = int("dist"), distSrc = str("distSrc"), end = int("end") ?: 1, endShots = int("endShots") ?: 0,
    ends = int("ends") ?: 0, invalidEnds = int("invalidEnds") ?: 0, shots = int("shots") ?: 0, scored = int("scored") ?: 0,
    sum = int("sum") ?: 0, x = int("x") ?: 0, avg = dbl("avg") ?: 0.0, min = int("min") ?: 0, pending = bool("pending") ?: false,
)

fun Msg.toLevel() = LevelInfo(str("mode") ?: "off", str("style") ?: "normal", bool("cal") ?: false,
    str("angle") ?: "auto", bool("cantSignal") ?: true, bool("rangeSignal") ?: true)

fun Msg.toRange() = RangeInfo(int("setup") ?: 0, str("name") ?: "", str("state") ?: "learning", int("ends") ?: 0,
    int("dists") ?: 0, int("min"), int("max"), dbl("speed"), int("kmh"), dbl("drag"), int("dist") ?: 0,
    str("distSrc") ?: "none", bool("estimated") ?: false, bool("signal") ?: true, str("warn") ?: "", bool("ok") ?: false)

fun Msg.toSetupItem() = SetupItem(int("id") ?: 0, str("name") ?: "", bool("deleted") ?: false, str("state") ?: "learning",
    int("ends") ?: 0, int("kmh"), int("min"), int("max"))

fun Msg.toLogInfo() = LogInfo(bool("ok") ?: false, str("jedec") ?: "", int("count") ?: 0, int("capacity") ?: 0,
    long("last") ?: 0, bool("clock") ?: false, int("migrated") ?: 0, str("error") ?: "")

fun Msg.toCfgItem() = CfgItem(str("k") ?: "", dbl("v") ?: 0.0, dbl("def") ?: 0.0, dbl("min") ?: 0.0, dbl("max") ?: 0.0,
    int("dec") ?: 0, bool("zero") ?: false, str("unit") ?: "", str("d") ?: "")

fun Msg.toEnd() = EndMsg(int("n") ?: 0, bool("valid") ?: false, int("arrows") ?: 0, int("sum"), int("x"), dbl("avg"),
    str("reason"), int("dist"), str("distSrc"), int("setup"), dbl("ang"), dbl("angSd"), int("angN"), dbl("cant"),
    dbl("cantMax"), int("canted"), int("cantN"), bool("stored") ?: true, if (has("shots")) toShots(int("n") ?: 0) else null)

fun Msg.toShotInfo() = ShotInfo(int("i") ?: 0, dbl("ang"), dbl("cant"), dbl("yaw"), dbl("roll"), dbl("rate"), dbl("px"), dbl("py"),
    t = dbl("t"), hold = dbl("hold"), holdMs = int("holdMs"), drop = dbl("drop"))
fun Msg.toTrace() = AimTrace(int("end") ?: 0, int("i") ?: 0, dbls("ms").map { it.toInt() }, dbls("pitch").map { it / 100.0 }, dbls("cant").map { it / 100.0 })
fun Msg.toShots(end: Int = int("end") ?: 0) = EndShots(end, int("n") ?: 0, int("dist")?.takeIf { it > 0 }, dbl("sdx") ?: 15.0, dbl("sdy") ?: 4.0,
    int("modelN") ?: 0, bool("matching") ?: true, objs("shots").map { it.toShotInfo() })
fun Msg.toShotModel() = ShotModelInfo(int("setup") ?: 0, bool("on") ?: true, int("n") ?: 0, dbl("ky") ?: 1.0, dbl("sdy") ?: 4.0, dbls("kx"), dbl("sdx") ?: 15.0)

fun Msg.toConfirm() = ConfirmMsg(int("end") ?: 0, int("counted") ?: 0, int("entered") ?: 0, bool("split") ?: false)

fun Msg.toSlot() = SlotMsg(long("seq"), long("epoch"), long("id") ?: 0, long("start"), int("ago"), int("ends") ?: 0,
    int("shots") ?: 0, int("scored") ?: 0, dbl("avg") ?: 0.0, int("x") ?: 0, int("min") ?: 0, int("invalidEnds") ?: 0,
    int("invalidArrows") ?: 0, bool("mismatch") ?: false, bool("manual") ?: false)

fun Msg.toEndRec() = EndRecMsg(long("seq"), long("epoch") ?: 0, long("id") ?: 0, int("n") ?: 0, bool("valid") ?: false,
    int("arrows") ?: 0, bool("skipped") ?: false, int("sum"), int("x"), int("dist"), bool("auto") ?: false, int("setup"),
    dbl("ang"), dbl("angSd"), int("angN"), dbl("cant"), dbl("cantMax"), int("canted"), int("cantN"), str("scores"))

fun Msg.toCal() = CalMsg(int("step") ?: 0, str("state") ?: "", str("text") ?: "")
