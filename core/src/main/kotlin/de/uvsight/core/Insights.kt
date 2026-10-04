package de.uvsight.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * What the collected numbers say about an end or a session, in plain words: where the group
 * sits (sight) and how it is shaped (archer), how steady the hold was, whether the bow was
 * canted or sank before the release, the rhythm, and over a session the first-arrow effect
 * and fatigue. Everything compares against the archer's own history (the baseline), so the
 * hints are about change, not about an absolute standard.
 */
object Insights {
    /** The group of an end in cm on the face: centre (x right, y up), mean radius, spreads. */
    data class Group(val cx: Double, val cy: Double, val radius: Double, val sdX: Double, val sdY: Double, val n: Int)

    fun group(hits: List<Hit>?): Group? {
        if (hits == null || hits.size < 2) return null
        val n = hits.size
        val cx = hits.sumOf { it.mmX } / n / 10; val cy = hits.sumOf { it.mmY } / n / 10
        val r = hits.sumOf { hypot(it.mmX / 10 - cx, it.mmY / 10 - cy) } / n
        val sdX = sqrt(hits.sumOf { (it.mmX / 10 - cx).let { d -> d * d } } / n)
        val sdY = sqrt(hits.sumOf { (it.mmY / 10 - cy).let { d -> d * d } } / n)
        return Group(cx, cy, r, sdX, sdY, n)
    }

    /** Hold spread of an end: the mean of the per-shot hold spreads, else the spread of the aiming angles. */
    fun holdOf(e: EndDetail): Double? = e.shots?.mapNotNull { it.hold }?.takeIf { it.isNotEmpty() }?.average() ?: e.angSd

    /** What is usual for this archer: medians over earlier ends (same setup when given). */
    data class Baseline(val hold: Double?, val groupRadius: Double?, val holdMs: Double?, val ends: Int)

    fun baseline(ends: List<EndDetail>, setup: Int?, exclude: EndDetail? = null): Baseline {
        val rel = ends.filter { it.valid && (setup == null || it.setup == null || it.setup == setup) && it !== exclude }
        return Baseline(median(rel.mapNotNull { holdOf(it) }), median(rel.mapNotNull { group(it.hits)?.radius }),
            median(rel.mapNotNull { e -> e.shots?.mapNotNull { it.holdMs?.toDouble() }?.takeIf { it.isNotEmpty() }?.average() }), rel.size)
    }

    private fun median(v: List<Double>): Double? = if (v.isEmpty()) null else v.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }
    private fun f1(v: Double) = String.format(java.util.Locale.ROOT, "%.1f", v)
    private fun f2(v: Double) = String.format(java.util.Locale.ROOT, "%.2f", v)

    /**
     * Hints for one end. cantTol: the sight's cant tolerance in degrees. clickMmAt18: how far one
     * click of the sight moves the group at 18 m (null = unknown, then only centimetres are given).
     */
    fun endHints(e: EndDetail, base: Baseline, cantTol: Double = 2.0, clickMmAt18: Double? = null): List<String> {
        val out = ArrayList<String>()
        if (!e.valid) return out
        val g = group(e.hits)
        if (g != null) {
            val off = hypot(g.cx, g.cy)
            val tight = base.groupRadius == null || g.radius <= base.groupRadius * 1.3
            if (off > max(2.0, 0.6 * g.radius) && g.n >= 3) {
                val parts = ArrayList<String>()
                if (abs(g.cx) >= 1.0) parts.add(tr("{cm} cm {side}", "cm" to f1(abs(g.cx)), "side" to (if (g.cx < 0) tr("left") else tr("right"))))
                if (abs(g.cy) >= 1.0) parts.add(tr("{cm} cm {side}", "cm" to f1(abs(g.cy)), "side" to (if (g.cy < 0) tr("low") else tr("high"))))
                var s = tr("Group {offset}", "offset" to parts.joinToString(", ")) + if (tight) tr(": move the sight") else ""
                val d = e.dist
                if (clickMmAt18 != null && clickMmAt18 > 0 && d != null && d > 0) {
                    val perClickCm = clickMmAt18 / 10 * d / 18.0
                    val cl = ArrayList<String>()
                    val kx = (abs(g.cx) / perClickCm).roundToInt(); val ky = (abs(g.cy) / perClickCm).roundToInt()
                    if (kx >= 1) cl.add(tr("{n} {clicks} {dir}", "n" to kx, "clicks" to (if (kx == 1) tr("click") else tr("clicks")), "dir" to (if (g.cx < 0) tr("left") else tr("right"))))
                    if (ky >= 1) cl.add(tr("{n} {clicks} {dir}", "n" to ky, "clicks" to (if (ky == 1) tr("click") else tr("clicks")), "dir" to (if (g.cy < 0) tr("down") else tr("up"))))
                    if (cl.isNotEmpty()) s += " (" + cl.joinToString(", ") + ")"
                }
                out.add(s + ".")
            }
            if (g.n >= 4) {
                if (g.sdY > 1.5 * g.sdX && g.sdY > 3) out.add(tr("Vertical spread ({v} cm up/down vs {h} sideways): check anchor and draw length.", "v" to f1(g.sdY), "h" to f1(g.sdX)))
                else if (g.sdX > 1.5 * g.sdY && g.sdX > 3) out.add(tr("Horizontal spread ({h} cm sideways vs {v} up/down): check the release.", "h" to f1(g.sdX), "v" to f1(g.sdY)))
            }
            if (base.groupRadius != null && g.n >= 3 && g.radius > base.groupRadius * 1.5 && g.radius > 3) out.add(tr("Wider group than usual ({now} cm vs {usual} cm).", "now" to f1(g.radius), "usual" to f1(base.groupRadius)))
        }
        val hold = holdOf(e)
        if (hold != null && base.hold != null && base.ends >= 3 && hold > base.hold * 1.5 && hold > 0.05) out.add(tr("Unsteady hold (±{now}° vs your usual ±{usual}°).", "now" to f2(hold), "usual" to f2(base.hold)))
        val canted = e.canted ?: 0
        val cant = e.cant
        if (canted >= 2 && cant != null) out.add(tr("Bow canted {side} in {n} of {total} shots.", "side" to (if (cant < 0) tr("left") else tr("right")), "n" to canted, "total" to (e.cantN ?: e.arrows)))
        else if (cant != null && abs(cant) > cantTol) out.add(tr("Bow canted {deg}° {side} on average.", "deg" to f1(abs(cant)), "side" to (if (cant < 0) tr("left") else tr("right"))))
        e.shots?.let { shots ->
            val sinking = shots.count { (it.drop ?: 0.0) < -0.4 }
            if (sinking >= 2) out.add(tr("Aim sank before the release in {n} shots.", "n" to sinking))
            val rates = shots.mapNotNull { it.rate }
            if (rates.size >= 3) {
                val med = median(rates)!!
                val hard = shots.filter { (it.rate ?: 0.0) > 2 * med && (it.rate ?: 0.0) > 150 }
                if (hard.isNotEmpty()) out.add(tr("Hard release in {shots}: the bow turned {rate}°/s.", "shots" to (if (hard.size == 1) tr("shot {n}", "n" to hard[0].i + 1) else tr("shots {list}", "list" to hard.joinToString(", ") { "${it.i + 1}" })), "rate" to f1(hard.maxOf { it.rate!! })))
            }
            rhythm(shots)?.let { (cv, lo, hi) -> if (shots.size >= 4 && cv > 0.5) out.add(tr("Uneven rhythm: {lo} to {hi} s between shots.", "lo" to f1(lo), "hi" to f1(hi))) }
        }
        return out
    }

    /** Coefficient of variation of the intervals between shots, with the shortest and longest interval (s). */
    fun rhythm(shots: List<ShotInfo>): Triple<Double, Double, Double>? {
        val t = shots.mapNotNull { it.t }.sorted()
        if (t.size < 3) return null
        val iv = t.zipWithNext { a, b -> b - a }.filter { it > 0 }
        if (iv.size < 2) return null
        val m = iv.average(); val sd = sqrt(iv.sumOf { (it - m) * (it - m) } / iv.size)
        return Triple(if (m > 0) sd / m else 0.0, iv.min(), iv.max())
    }

    /** Hints over a whole session: first-arrow effect and fatigue. */
    fun sessionHints(ends: List<EndDetail>): List<String> {
        val out = ArrayList<String>()
        val valid = ends.filter { it.valid }.sortedBy { it.n }
        // first arrow of the end vs the rest, from sure or hand-set shot matches
        var first = 0.0; var firstN = 0; var rest = 0.0; var restN = 0; var endsWith = 0
        for (e in valid) {
            val sc = e.scores ?: continue; val so = e.shotOf ?: continue; val src = e.shotSrc ?: continue
            if (sc.size != so.size) continue
            var used = false
            for (i in so.indices) {
                if (src[i] != "sure" && src[i] != "user") continue
                val v = Scoring.points(Csv.arrowValue(sc[i]).let { if (sc[i] == "X") Scoring.X else it })
                if (so[i] == 0) { first += v; firstN++; used = true } else { rest += v; restN++ }
            }
            if (used) endsWith++
        }
        if (endsWith >= 4 && firstN >= 4 && restN >= 8) {
            val d = rest / restN - first / firstN
            if (d >= 0.8) out.add(tr("The first arrow of an end scores {d} points below the others ({n} ends).", "d" to f1(d), "n" to firstN))
        }
        // fatigue: last third vs first third
        if (valid.size >= 6) {
            val k = valid.size / 3
            fun avg(l: List<EndDetail>) = l.mapNotNull { e -> e.sum?.toDouble()?.div(max(1, e.arrows)) }.average()
            val a = avg(valid.take(k)); val b = avg(valid.takeLast(k))
            val ha = valid.take(k).mapNotNull { holdOf(it) }; val hb = valid.takeLast(k).mapNotNull { holdOf(it) }
            if (a - b >= 0.5) out.add(tr("Scores fell from {a} to {b} per arrow over the session", "a" to f2(a), "b" to f2(b)) +
                (if (ha.isNotEmpty() && hb.isNotEmpty() && hb.average() > ha.average() * 1.3) tr(", and the hold got less steady") else "") + ".")
        }
        return out
    }

    /** Session figures for trends over time. */
    data class SessionStats(val groupRadius: Double?, val hold: Double?, val cantSd: Double?, val holdMs: Double?, val ends: Int)

    fun sessionStats(ends: List<EndDetail>): SessionStats {
        val valid = ends.filter { it.valid }
        val radii = valid.mapNotNull { group(it.hits)?.radius }
        val holds = valid.mapNotNull { holdOf(it) }
        val cants = valid.flatMap { e -> e.shots?.mapNotNull { it.cant } ?: (e.cant?.let { listOf(it) } ?: emptyList()) }
        val cantSd = if (cants.size >= 3) { val m = cants.average(); sqrt(cants.sumOf { (it - m) * (it - m) } / cants.size) } else null
        val holdMs = valid.flatMap { e -> e.shots?.mapNotNull { it.holdMs?.toDouble() } ?: emptyList() }
        return SessionStats(radii.takeIf { it.isNotEmpty() }?.average(), holds.takeIf { it.isNotEmpty() }?.average(), cantSd, holdMs.takeIf { it.isNotEmpty() }?.average(), valid.size)
    }
}
