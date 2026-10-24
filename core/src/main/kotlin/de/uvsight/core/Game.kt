package de.uvsight.core

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Play mode: personal bests, streaks, the steady-hand score, badges and the goals. Off by default. */
@Serializable
data class GamePrefs(
    val enabled: Boolean = false,
    val color: Int = 0,            // avatar colour, index into the app's palette
    val emoji: String = "",        // avatar emoji (a system font glyph, no picture needed)
    val dailyGoal: Int = 2,        // ends above your own average per training day
    val groupGoal: Int = 500,      // trainer's board: points of all sights together, per evening
)

data class Bests(val endSum: Int? = null, val endArrows: Int? = null, val sessionAvg: Double? = null, val sessionX: Int? = null, val steady: Int? = null)
/** What may be celebrated after an end or a session: compared before and after the archive changed. */
data class GameSnapshot(val bests: Bests, val levels: Map<String, Int>)

object Game {
    const val HOLD_TOL_DEG = 0.6       // the sight's hold tolerance: within it the aim counts as steady
    const val DAY_MS = 86_400_000L

    /**
     * Steady hand, 0..100, from what the sight measured at the release: the spread of the aiming
     * angle while holding, the sinking just before the release and how long the aim stayed steady.
     * null without an angle (no calibration, angle off).
     */
    fun steadyHand(s: ShotInfo): Int? {
        val hold = s.hold ?: return null
        val drop = abs(s.drop ?: 0.0)
        val ms = s.holdMs ?: 0
        val v = 100.0 - 50 * min(1.5, hold / HOLD_TOL_DEG) - 30 * min(1.5, drop / 0.5) - 20 * max(0.0, 1 - ms / 1000.0)
        return v.roundToInt().coerceIn(0, 100)
    }
    fun steadyHand(e: EndDetail): Int? = e.shots?.mapNotNull { steadyHand(it) }?.takeIf { it.isNotEmpty() }?.average()?.roundToInt()

    private fun validEnds(ends: Map<String, List<EndDetail>>, sessions: List<ArchiveSession>): List<Pair<ArchiveSession, EndDetail>> {
        val byKey = sessions.filter { !it.hidden }.associateBy { it.key }
        return ends.flatMap { (k, l) -> val r = byKey[k] ?: return@flatMap emptyList<Pair<ArchiveSession, EndDetail>>(); l.filter { it.valid && it.sum != null && it.arrows > 0 }.map { r to it } }
    }

    fun bests(sessions: List<ArchiveSession>, ends: Map<String, List<EndDetail>>): Bests {
        val ve = validEnds(ends, sessions)
        val bestEnd = ve.maxWithOrNull(compareBy<Pair<ArchiveSession, EndDetail>> { it.second.sum ?: 0 }.thenBy { it.second.x ?: 0 })?.second
        val shown = sessions.filter { !it.hidden && it.scored >= 12 }
        return Bests(
            endSum = bestEnd?.sum, endArrows = bestEnd?.arrows,
            sessionAvg = shown.maxOfOrNull { it.avg }, sessionX = sessions.filter { !it.hidden }.maxOfOrNull { it.x },
            steady = ve.mapNotNull { steadyHand(it.second) }.maxOrNull(),
        )
    }

    fun dayOf(ms: Long, tzOffsetMs: Long) = (ms + tzOffsetMs).floorDiv(DAY_MS)
    fun trainingDays(sessions: List<ArchiveSession>, tzOffsetMs: Long): List<Long> =
        sessions.filter { !it.hidden }.map { dayOf(it.sortTime, tzOffsetMs) }.distinct().sorted()

    /** Training days in a row up to today or yesterday (a rest day today does not break it yet). */
    fun streakDays(sessions: List<ArchiveSession>, now: Long, tzOffsetMs: Long): Int {
        val days = trainingDays(sessions, tzOffsetMs).toSet()
        val today = dayOf(now, tzOffsetMs)
        var d = if (today in days) today else if (today - 1 in days) today - 1 else return 0
        var n = 0
        while (d in days) { n++; d-- }
        return n
    }
    /** The longest run of training days ever. */
    fun longestStreak(sessions: List<ArchiveSession>, tzOffsetMs: Long): Int {
        var best = 0; var run = 0; var prev = Long.MIN_VALUE
        for (d in trainingDays(sessions, tzOffsetMs)) { run = if (d == prev + 1) run + 1 else 1; prev = d; best = max(best, run) }
        return best
    }
    /** Valid ends without a miss in a row, newest end backwards (ends without scores stop the count). */
    fun endsWithoutMiss(sessions: List<ArchiveSession>, ends: Map<String, List<EndDetail>>): Int {
        var n = 0
        for (r in sessions.filter { !it.hidden }.sortedByDescending { it.sortTime }) {
            for (e in (ends[r.key] ?: emptyList()).sortedByDescending { it.n }) {
                if (!e.valid) continue
                val sc = e.scores ?: return n
                if (sc.any { it == "M" }) return n
                n++
            }
        }
        return n
    }

    /** Per-arrow average over all valid ends, the yardstick for "above your average". */
    fun arrowAverage(sessions: List<ArchiveSession>, ends: Map<String, List<EndDetail>>): Double? {
        val ve = validEnds(ends, sessions)
        val arrows = ve.sumOf { it.second.arrows }
        return if (arrows == 0) null else ve.sumOf { it.second.sum ?: 0 }.toDouble() / arrows
    }
    /** Today's ends that beat the archer's own average, against the goal. */
    fun dailyGoal(sessions: List<ArchiveSession>, ends: Map<String, List<EndDetail>>, goal: Int, now: Long, tzOffsetMs: Long): Pair<Int, Int> {
        val avg = arrowAverage(sessions, ends) ?: return 0 to goal
        val today = dayOf(now, tzOffsetMs)
        val done = validEnds(ends, sessions).count { (r, e) -> dayOf(r.sortTime, tzOffsetMs) == today && (e.sum ?: 0).toDouble() / e.arrows > avg }
        return done to goal
    }

    fun snapshot(defs: List<BadgeDef>, sessions: List<ArchiveSession>, ends: Map<String, List<EndDetail>>, tzOffsetMs: Long, now: Long) =
        GameSnapshot(bests(sessions, ends), Badges.levels(defs, sessions, ends, tzOffsetMs, now))

    /** What changed for the better between two snapshots: titles and values to celebrate. */
    fun celebrations(before: GameSnapshot, after: GameSnapshot, defs: List<BadgeDef>): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val b = before.bests; val a = after.bests
        if (b.endSum != null && a.endSum != null && a.endSum > b.endSum) out.add(tr("New best end") to "${a.endSum}")
        if (b.sessionAvg != null && a.sessionAvg != null && a.sessionAvg > b.sessionAvg + 1e-9) out.add(tr("New best average") to fmt(a.sessionAvg, 2))
        if (b.sessionX != null && a.sessionX != null && a.sessionX > b.sessionX) out.add(tr("Most X in a session") to "${a.sessionX}")
        if (b.steady != null && a.steady != null && a.steady > b.steady) out.add(tr("Steadiest hand yet") to "${a.steady}")
        for ((id, lvl) in after.levels) if (lvl > (before.levels[id] ?: 0)) defs.firstOrNull { it.id == id }?.let { out.add(it.title + " · " + Badges.levelName(lvl) to Badges.medal(lvl) + " " + it.emoji) }
        return out
    }
}
