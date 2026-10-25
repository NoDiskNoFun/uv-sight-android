package de.uvsight.core

import kotlinx.serialization.Serializable

/**
 * A goal the archer set: what is measured (as for badges, plus "ends above your average"), where,
 * the direction, a target and the period it counts in: today, this week, this session or all time.
 */
@Serializable
data class GoalDef(
    val id: String,
    val emoji: String = "",
    val title: String,
    val metric: String,
    val scope: String,              // "end" / "session" / "total" (total = counted over the period)
    val op: String = "min",
    val target: Double,
    val period: String = "day",     // "day" / "week" / "session" / "all"
)

data class GoalProgress(val value: Double?, val fraction: Float, val reached: Boolean, val text: String)

object Goals {
    val PERIODS = listOf("day", "week", "session", "all")
    val METRICS: List<Metric> = listOf(Metric("endsAboveAvg", listOf("total"), "min", "", 0), Metric("arrowsAboveAvg", listOf("total"), "min", "", 0)) + Badges.METRICS
    fun metric(id: String) = METRICS.firstOrNull { it.id == id }
    fun metricLabel(id: String) = when (id) { "endsAboveAvg" -> tr("Ends above your average"); "arrowsAboveAvg" -> tr("Arrows above your average"); else -> Badges.metricLabel(id) }
    fun periodLabel(p: String) = when (p) { "day" -> tr("today"); "week" -> tr("this week"); "session" -> tr("this session"); "all" -> tr("all time"); else -> p }
    fun newId(): String = Badges.newId()
    /** The one goal a fresh app starts with: two ends above your own average per day. */
    fun defaultGoal() = GoalDef("g_default", "🎯", tr("Ends above average"), "endsAboveAvg", "total", "min", 2.0, "day")
    fun describe(g: GoalDef): String = metricLabel(g.metric) + (if (g.scope != "total") ", " + Badges.scopeLabel(g.scope) else "") + ": " +
        (if (g.op == "max") tr("at most") else tr("at least")) + " " + fmtValue(g, g.target) + ", " + periodLabel(g.period)
    fun fmtValue(g: GoalDef, v: Double): String { val m = metric(g.metric); val d = m?.decimals ?: 0; return (if (d == 0) kotlin.math.round(v).toInt().toString() else fmt(v, d)) + (m?.unit ?: "") }

    /** Monday-based week number of a day (day 0 = Thursday 1 Jan 1970). */
    private fun weekOf(day: Long) = Math.floorDiv(day + 3, 7L)

    /** The sessions that count for the period. */
    fun inPeriod(g: GoalDef, sessions: List<ArchiveSession>, now: Long, tzOffsetMs: Long, currentKey: String?): List<ArchiveSession> {
        val shown = sessions.filter { !it.hidden }
        val today = Game.dayOf(now, tzOffsetMs)
        return when (g.period) {
            "day" -> shown.filter { Game.dayOf(it.sortTime, tzOffsetMs) == today }
            "week" -> shown.filter { weekOf(Game.dayOf(it.sortTime, tzOffsetMs)) == weekOf(today) }
            "session" -> (currentKey?.let { k -> shown.filter { it.key == k } } ?: emptyList()).ifEmpty { listOfNotNull(shown.maxByOrNull { it.sortTime }) }
            else -> shown
        }
    }

    fun progress(g: GoalDef, sessions: List<ArchiveSession>, ends: Map<String, List<EndDetail>>, now: Long, tzOffsetMs: Long, currentKey: String?): GoalProgress {
        val inP = inPeriod(g, sessions, now, tzOffsetMs, currentKey)
        val value: Double? = when (g.metric) {
            "endsAboveAvg", "arrowsAboveAvg" -> {
                val avg = Game.arrowAverage(sessions, ends)
                if (avg == null) 0.0 else {
                    val above = inP.flatMap { r -> (ends[r.key] ?: emptyList()).filter { it.valid && it.arrows > 0 && (it.sum ?: 0).toDouble() / it.arrows > avg } }
                    if (g.metric == "endsAboveAvg") above.size.toDouble() else above.sumOf { it.arrows }.toDouble()
                }
            }
            else -> Badges.best(BadgeDef(g.id, "", "", g.metric, g.scope, g.op, listOf(g.target)), inP, ends, tzOffsetMs, now)
        }
        val reached = value != null && (if (g.op == "max") value <= g.target + 1e-9 else value >= g.target - 1e-9)
        val fraction = when {
            value == null || g.target <= 0 -> 0f
            reached -> 1f
            g.op == "max" -> (g.target / value).toFloat().coerceIn(0f, 1f)
            else -> (value / g.target).toFloat().coerceIn(0f, 1f)
        }
        val text = (value?.let { fmtValue(g, it) } ?: "–") + " / " + fmtValue(g, g.target)
        return GoalProgress(value, fraction, reached, text)
    }
}
