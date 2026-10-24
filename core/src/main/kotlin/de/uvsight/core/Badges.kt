package de.uvsight.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A badge rule: what is measured, where, which way is better, and up to three thresholds (bronze,
 * silver, gold). The same format on the phone, on the sight and in the shared JSON file.
 */
@Serializable
data class BadgeDef(
    val id: String,                 // 8 characters
    val emoji: String,
    val title: String,
    val metric: String,             // see Badges.METRICS
    val scope: String,              // "end" / "session" / "total"
    val op: String,                 // "min": at least; "max": at most (cant, spread)
    val levels: List<Double>,       // bronze, silver, gold; ascending for min, descending for max
    val template: Boolean = false,  // came from the built-in templates (still the user's own, deletable)
) {
    fun sameRule(o: BadgeDef) = metric == o.metric && scope == o.scope && op == o.op && levels == o.levels
}

/** A badge an archer earned: the level, when, and the rule as it was then. Kept even if the badge goes away. */
@Serializable
data class EarnedBadge(val id: String, val level: Int, val at: Long, val def: BadgeDef, val retired: Boolean = false)

@Serializable
data class BadgeSet(val format: String = "uv-sight-badges", val version: Int = 1, val badges: List<BadgeDef> = emptyList())

/** One thing a badge can measure: where it applies, which direction is good, how it is shown. */
data class Metric(val id: String, val scopes: List<String>, val defaultOp: String, val unit: String, val decimals: Int) {
    val label: String get() = Badges.metricLabel(id)
}

object Badges {
    val SCOPES = listOf("end", "session", "total")
    val METRICS = listOf(
        Metric("endPoints", listOf("end"), "min", "", 0),
        Metric("avg", listOf("end", "session"), "min", "", 2),
        Metric("endMin", listOf("end"), "min", "", 0),
        Metric("x", listOf("end", "session", "total"), "min", "", 0),
        Metric("arrows", listOf("session", "total"), "min", "", 0),
        Metric("ends", listOf("session", "total"), "min", "", 0),
        Metric("shots", listOf("session"), "min", "", 0),
        Metric("sessions", listOf("total"), "min", "", 0),
        Metric("days", listOf("total"), "min", "", 0),
        Metric("daysRow", listOf("total"), "min", "", 0),
        Metric("noMissRow", listOf("total"), "min", "", 0),
        Metric("steady", listOf("end", "session"), "min", "", 0),
        Metric("cant", listOf("end", "session"), "max", "°", 1),
        Metric("hold", listOf("end", "session"), "min", "ms", 0),
        Metric("spread", listOf("end", "session"), "max", "mm", 0),
        Metric("dist", listOf("end", "session"), "min", "m", 0),
    )
    fun metric(id: String) = METRICS.firstOrNull { it.id == id }
    fun metricLabel(id: String) = when (id) {
        "endPoints" -> tr("Points of an end"); "avg" -> tr("Average per arrow"); "endMin" -> tr("Lowest arrow of an end"); "x" -> tr("X count")
        "arrows" -> tr("Arrows scored"); "ends" -> tr("Ends"); "shots" -> tr("Shots counted"); "sessions" -> tr("Sessions"); "days" -> tr("Training days")
        "daysRow" -> tr("Training days in a row"); "noMissRow" -> tr("Ends without a miss in a row"); "steady" -> tr("Steady hand"); "cant" -> tr("Cant at the release")
        "hold" -> tr("Hold time"); "spread" -> tr("Group size (photo)"); "dist" -> tr("Distance"); else -> id
    }
    fun scopeLabel(s: String) = when (s) { "end" -> tr("in one end"); "session" -> tr("in one session"); "total" -> tr("in total"); else -> s }
    fun levelName(l: Int) = when (l) { 1 -> tr("bronze"); 2 -> tr("silver"); 3 -> tr("gold"); else -> "" }
    fun medal(l: Int) = when (l) { 1 -> "🥉"; 2 -> "🥈"; 3 -> "🥇"; else -> "" }
    fun fmtValue(def: BadgeDef, v: Double): String { val m = metric(def.metric); val d = m?.decimals ?: 0; return (if (d == 0) v.roundToInt().toString() else fmt(v, d)) + (m?.unit ?: "") }
    /** "Steady hand, in one end: at least 60 / 70 / 80" */
    fun describe(def: BadgeDef): String = metricLabel(def.metric) + ", " + scopeLabel(def.scope) + ": " + (if (def.op == "max") tr("at most") else tr("at least")) + " " + def.levels.joinToString(" / ") { fmtValue(def, it) }

    fun newId(): String = (1..8).map { "abcdefghijklmnopqrstuvwxyz0123456789"[kotlin.random.Random.nextInt(36)] }.joinToString("")

    /** The built-in set, offered as templates and, in single mode, seeded as the archer's own badges. */
    fun templates(): List<BadgeDef> = listOf(
        BadgeDef("t_firstx", "⭐", tr("First X"), "x", "total", "min", listOf(1.0), true),
        BadgeDef("t_arrows", "🏹", tr("Arrows"), "arrows", "total", "min", listOf(100.0, 500.0, 1000.0), true),
        BadgeDef("t_days", "📅", tr("Training days"), "days", "total", "min", listOf(10.0, 30.0, 100.0), true),
        BadgeDef("t_streak", "🔥", tr("Days in a row"), "daysRow", "total", "min", listOf(3.0, 5.0, 10.0), true),
        BadgeDef("t_golden", "🥇", tr("Golden end"), "endMin", "end", "min", listOf(7.0, 8.0, 9.0), true),
        BadgeDef("t_clean", "🧹", tr("Clean run"), "noMissRow", "total", "min", listOf(5.0, 10.0, 20.0), true),
        BadgeDef("t_steady", "🖐️", tr("Steady hand"), "steady", "end", "min", listOf(60.0, 70.0, 80.0), true),
        BadgeDef("t_xs", "✨", tr("X collector"), "x", "total", "min", listOf(30.0, 100.0, 300.0), true),
        BadgeDef("t_sessions", "🏆", tr("Sessions"), "sessions", "total", "min", listOf(10.0, 50.0, 200.0), true),
        BadgeDef("t_bestend", "🎯", tr("Big end"), "endPoints", "end", "min", listOf(25.0, 27.0, 29.0), true),
    )

    // ---- values ----
    private fun score(s: String) = when (s) { "X" -> 10; "M" -> 0; else -> s.toIntOrNull() ?: 0 }
    private fun endValue(metric: String, e: EndDetail): Double? = when (metric) {
        "endPoints" -> e.sum?.toDouble()
        "avg" -> e.sum?.let { if (e.arrows > 0) it.toDouble() / e.arrows else null }
        "endMin" -> e.scores?.takeIf { it.size >= e.arrows && it.isNotEmpty() }?.minOf { score(it) }?.toDouble()
        "x" -> e.x?.toDouble()
        "steady" -> Game.steadyHand(e)?.toDouble()
        "cant" -> e.cant?.let { abs(it) }
        "hold" -> e.shots?.mapNotNull { it.holdMs?.toDouble() }?.takeIf { it.isNotEmpty() }?.average()
        "spread" -> e.hits?.takeIf { it.size >= 2 }?.let { h -> val cx = h.map { it.mmX }.average(); val cy = h.map { it.mmY }.average(); h.map { hypot(it.mmX - cx, it.mmY - cy) }.average() }
        "dist" -> e.dist?.toDouble()
        else -> null
    }
    private fun sessionValue(metric: String, r: ArchiveSession, ends: List<EndDetail>): Double? = when (metric) {
        "avg" -> if (r.scored > 0) r.avg else null
        "x" -> r.x.toDouble()
        "arrows" -> r.scored.toDouble()
        "ends" -> r.ends.toDouble()
        "shots" -> r.shots.toDouble()
        "steady", "cant", "hold", "spread" -> ends.filter { it.valid }.mapNotNull { endValue(metric, it) }.takeIf { it.isNotEmpty() }?.average()
        "dist" -> ends.filter { it.valid }.mapNotNull { it.dist?.toDouble() }.maxOrNull()
        else -> null
    }
    private fun totalValue(metric: String, sessions: List<ArchiveSession>, ends: Map<String, List<EndDetail>>, tzOffsetMs: Long): Double? = when (metric) {
        "x" -> sessions.sumOf { it.x }.toDouble()
        "arrows" -> sessions.sumOf { it.scored }.toDouble()
        "ends" -> sessions.sumOf { it.ends }.toDouble()
        "sessions" -> sessions.size.toDouble()
        "days" -> Game.trainingDays(sessions, tzOffsetMs).size.toDouble()
        "daysRow" -> Game.longestStreak(sessions, tzOffsetMs).toDouble()
        "noMissRow" -> longestNoMissRun(sessions, ends).toDouble()
        else -> null
    }
    /** Longest run of valid ends without a miss, over the whole history in time order. */
    fun longestNoMissRun(sessions: List<ArchiveSession>, ends: Map<String, List<EndDetail>>): Int {
        var best = 0; var run = 0
        for (r in sessions.sortedBy { it.sortTime }) for (e in (ends[r.key] ?: emptyList()).sortedBy { it.n }) {
            if (!e.valid) continue
            val sc = e.scores
            if (sc == null || sc.any { it == "M" }) { run = 0; continue }
            run++; best = max(best, run)
        }
        return best
    }

    /** Every value the rule can be checked against, with its time (ends and sessions) or now (totals). */
    fun values(def: BadgeDef, sessions: List<ArchiveSession>, ends: Map<String, List<EndDetail>>, tzOffsetMs: Long, now: Long): List<Pair<Double, Long>> {
        val shown = sessions.filter { !it.hidden }
        return when (def.scope) {
            "end" -> shown.flatMap { r -> (ends[r.key] ?: emptyList()).filter { it.valid && it.arrows > 0 }.mapNotNull { e -> endValue(def.metric, e)?.let { it to r.sortTime } } }
            "session" -> shown.mapNotNull { r -> sessionValue(def.metric, r, ends[r.key] ?: emptyList())?.let { it to r.sortTime } }
            else -> listOfNotNull(totalValue(def.metric, shown, ends, tzOffsetMs)?.let { it to now })
        }
    }
    /** The archer's best value for this rule (highest for "at least", lowest for "at most"). */
    fun best(def: BadgeDef, sessions: List<ArchiveSession>, ends: Map<String, List<EndDetail>>, tzOffsetMs: Long, now: Long): Double? {
        val v = values(def, sessions, ends, tzOffsetMs, now).map { it.first }
        return if (def.op == "max") v.minOrNull() else v.maxOrNull()
    }
    /** Level 0..3 a value reaches. */
    fun level(def: BadgeDef, value: Double?): Int {
        if (value == null) return 0
        return def.levels.count { if (def.op == "max") value <= it + 1e-9 else value >= it - 1e-9 }
    }
    fun levelReached(def: BadgeDef, sessions: List<ArchiveSession>, ends: Map<String, List<EndDetail>>, tzOffsetMs: Long, now: Long): Int =
        level(def, best(def, sessions, ends, tzOffsetMs, now))

    /** Levels of every rule, by id: what a snapshot compares. */
    fun levels(defs: List<BadgeDef>, sessions: List<ArchiveSession>, ends: Map<String, List<EndDetail>>, tzOffsetMs: Long, now: Long): Map<String, Int> =
        defs.associate { it.id to levelReached(it, sessions, ends, tzOffsetMs, now) }

    // ---- the shared file ----
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    fun encode(defs: List<BadgeDef>): String = json.encodeToString(BadgeSet.serializer(), BadgeSet(badges = defs.map { it.copy(template = false) }))
    fun decode(text: String): List<BadgeDef>? = runCatching { json.decodeFromString(BadgeSet.serializer(), text) }.getOrNull()?.takeIf { it.format == "uv-sight-badges" }?.badges
        ?.filter { it.id.isNotEmpty() && it.levels.isNotEmpty() && metric(it.metric) != null && it.scope in SCOPES }
    /** The command line that stores this rule on the sight. */
    fun sightCommand(d: BadgeDef) = "badge set ${d.id.take(8)} ${d.metric} ${d.scope} ${d.op} ${d.levels.joinToString(",") { fmt(it, 2) }} ${d.emoji.ifEmpty { "🏅" }} ${d.title.take(18)}"
}
