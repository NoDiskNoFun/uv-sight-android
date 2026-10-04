package de.uvsight.core

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Which arrow on the face was which shot: the sight predicts, per shot, where the arrow lands
 * relative to the end's mean (height from the aiming angle, sideways from the bow's rotation
 * at release, once learned) with a spread. Here those predictions are matched to the arrow
 * positions from the photo. The result carries, per arrow, the shot index, a confidence and the
 * runner-up shot, so the app can show a sure match, a guess, or two candidates.
 */
object ShotMatch {
    const val SURE = 0.9
    const val GUESS = 0.6

    data class Result(val shotOf: List<Int>, val conf: List<Double>, val second: List<Int?>) {
        fun src(i: Int): String = when { conf[i] >= SURE -> "sure"; conf[i] >= GUESS -> "guess"; else -> "coin" }
    }

    /** hits in face mm (x right, y up); null when the counts don't agree or nothing can be predicted. */
    fun match(hits: List<Hit>, shots: EndShots): Result? {
        val n = hits.size
        if (n == 0 || shots.shots.size != n) return null
        if (shots.shots.none { it.px != null || it.py != null }) return null
        val mx = hits.sumOf { it.mmX } / n / 10.0; val my = hits.sumOf { it.mmY } / n / 10.0      // cm
        val sdx = max(1.0, shots.sdx); val sdy = max(1.0, shots.sdy)
        val cost = Array(n) { a -> DoubleArray(n) { s ->
            val sh = shots.shots[s]
            val dx = (hits[a].mmX / 10.0 - mx) - (sh.px ?: 0.0)
            val dy = (hits[a].mmY / 10.0 - my) - (sh.py ?: 0.0)
            val wx = if (sh.px != null) 1.0 / (sdx * sdx) else 1.0 / (50.0 * 50.0)     // no sideways prediction: nearly uninformative
            val wy = if (sh.py != null) 1.0 / (sdy * sdy) else 1.0 / (50.0 * 50.0)
            dx * dx * wx + dy * dy * wy
        } }
        val assign = hungarian(cost)
        // confidence from pairwise swaps: how much worse is it to give this arrow another arrow's shot?
        val conf = DoubleArray(n); val second = arrayOfNulls<Int>(n)
        for (a in 0 until n) {
            var sum = 0.0; var bestB = -1; var bestW = 0.0
            for (b in 0 until n) {
                if (b == a) continue
                val delta = cost[a][assign[b]] + cost[b][assign[a]] - cost[a][assign[a]] - cost[b][assign[b]]
                val w = exp(-max(0.0, delta) / 2)
                sum += w
                if (w > bestW) { bestW = w; bestB = b }
            }
            conf[a] = 1.0 / (1.0 + sum)
            second[a] = if (bestB >= 0) assign[bestB] else null
        }
        return Result(assign.toList(), conf.toList(), second.toList())
    }

    /** Minimum-cost assignment (rows = arrows, columns = shots), O(n^3). */
    fun hungarian(cost: Array<DoubleArray>): IntArray {
        val n = cost.size
        val u = DoubleArray(n + 1); val v = DoubleArray(n + 1); val p = IntArray(n + 1); val way = IntArray(n + 1)
        for (i in 1..n) {
            p[0] = i
            var j0 = 0
            val minv = DoubleArray(n + 1) { Double.MAX_VALUE }; val used = BooleanArray(n + 1)
            do {
                used[j0] = true
                val i0 = p[j0]; var delta = Double.MAX_VALUE; var j1 = 0
                for (j in 1..n) if (!used[j]) {
                    val cur = cost[i0 - 1][j - 1] - u[i0] - v[j]
                    if (cur < minv[j]) { minv[j] = cur; way[j] = j0 }
                    if (minv[j] < delta) { delta = minv[j]; j1 = j }
                }
                for (j in 0..n) if (used[j]) { u[p[j]] += delta; v[j] -= delta } else minv[j] -= delta
                j0 = j1
            } while (p[j0] != 0)
            do { val j1 = way[j0]; p[j0] = p[j1]; j0 = j1 } while (j0 != 0)
        }
        val out = IntArray(n)
        for (j in 1..n) out[p[j] - 1] = j - 1
        return out
    }

    /** Lines for "shot teach": the sure and user-set pairs, dx/dy in mm from the group's mean. */
    fun teachLine(end: Int, hits: List<Hit>, shotOf: List<Int>, src: List<String>): String? {
        val n = hits.size
        if (n == 0 || shotOf.size != n) return null
        val mx = hits.sumOf { it.mmX } / n; val my = hits.sumOf { it.mmY } / n
        val parts = (0 until n).filter { src[it] == "sure" || src[it] == "user" }
            .map { a -> "${shotOf[a]}:${Math.round(hits[a].mmX - mx)}:${Math.round(hits[a].mmY - my)}" }
        return if (parts.isEmpty()) null else "shot teach $end " + parts.joinToString(" ")
    }

    /** "Shot matching accuracy": mean confidence of the matched arrows over the last ends of a setup (coin flips count 0.5, corrected sure matches 0). */
    fun accuracy(ends: List<EndDetail>, setup: Int?, lastEnds: Int = 20): Double? {
        val rel = ends.filter { it.shotConf != null && (setup == null || it.setup == setup) }.sortedBy { it.n }.takeLast(lastEnds)
        val vals = rel.flatMap { e -> e.shotConf!!.indices.map { i -> when (e.shotSrc?.getOrNull(i)) { "coin" -> 0.5; "user" -> 1.0; "corrected" -> 0.0; else -> min(1.0, e.shotConf!![i]) } } }
        return if (vals.isEmpty()) null else vals.average()
    }
}
