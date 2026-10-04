package de.uvsight.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InsightsTest {
    private fun end(n: Int, hits: List<Hit>, scores: List<String>? = null, shots: List<ShotInfo>? = null, cant: Double? = null, canted: Int? = null, angSd: Double? = null, dist: Int? = 18) =
        EndDetail(n, true, hits.size, hits.sumOf { Scoring.points(it.ring) }, 0, scores ?: hits.map { Scoring.label(it.ring) }, null, dist, false, 0,
            null, angSd, hits.size, cant, null, canted, hits.size, hits = hits, shots = shots)
    private val none = Insights.Baseline(null, null, null, 0)

    @Test fun groupOffsetNamesTheSightCorrection() {
        // tight group 4 cm left and 3 cm high
        val hits = listOf(Hit(-45.0, 25.0, 9), Hit(-35.0, 35.0, 9), Hit(-40.0, 30.0, 9), Hit(-40.0, 30.0, 9))
        val h = Insights.endHints(end(1, hits), none, clickMmAt18 = 5.0)
        assertTrue(h.any { it.startsWith("Group 4.0 cm left, 3.0 cm high: move the sight (8 clicks left, 6 clicks up)") }, h.toString())
    }

    @Test fun spreadShapeTellsArcherFromSight() {
        val tall = listOf(Hit(0.0, 80.0, 8), Hit(5.0, -70.0, 8), Hit(-5.0, 30.0, 9), Hit(0.0, -40.0, 9))
        assertTrue(Insights.endHints(end(1, tall), none).any { it.startsWith("Vertical spread") })
        val wide = listOf(Hit(80.0, 0.0, 8), Hit(-70.0, 5.0, 8), Hit(30.0, -5.0, 9), Hit(-40.0, 0.0, 9))
        assertTrue(Insights.endHints(end(1, wide), none).any { it.startsWith("Horizontal spread") })
    }

    @Test fun holdCompareToOwnBaseline() {
        val hits = listOf(Hit(10.0, 0.0, 10), Hit(-10.0, 0.0, 10), Hit(0.0, 10.0, 10))
        val base = Insights.Baseline(hold = 0.10, groupRadius = 2.0, holdMs = 1200.0, ends = 10)
        val shaky = end(1, hits, shots = (0..2).map { ShotInfo(it, hold = 0.25) })
        assertTrue(Insights.endHints(shaky, base).any { it.startsWith("Unsteady hold") })
        val steady = end(1, hits, shots = (0..2).map { ShotInfo(it, hold = 0.10) })
        assertTrue(Insights.endHints(steady, base).none { it.startsWith("Unsteady") })
    }

    @Test fun cantRhythmAndRelease() {
        val hits = listOf(Hit(10.0, 0.0, 10), Hit(-10.0, 0.0, 10), Hit(0.0, 10.0, 10), Hit(0.0, -10.0, 10))
        val shots = listOf(ShotInfo(0, t = 0.0, rate = 60.0, drop = -0.5), ShotInfo(1, t = 5.0, rate = 70.0, drop = -0.6), ShotInfo(2, t = 30.0, rate = 400.0), ShotInfo(3, t = 33.0, rate = 65.0))
        val h = Insights.endHints(end(1, hits, shots = shots, cant = -2.6, canted = 3), none)
        assertTrue(h.any { it == "Bow canted left in 3 of 4 shots." }, h.toString())
        assertTrue(h.any { it.startsWith("Aim sank before the release in 2 shots") }, h.toString())
        assertTrue(h.any { it.startsWith("Hard release in shot 3") }, h.toString())
        assertTrue(h.any { it.startsWith("Uneven rhythm") }, h.toString())
    }

    @Test fun firstArrowEffectNeedsSureMatches() {
        val hits = listOf(Hit(0.0, 0.0, 10), Hit(10.0, 0.0, 10), Hit(-10.0, 0.0, 10))
        val ends = (1..5).map { n ->
            EndDetail(n, true, 3, 27, 0, listOf("7", "10", "10"), null, 18, false, 0, null, null, 3, null, null, null, null,
                hits = hits, shotOf = listOf(0, 1, 2), shotConf = listOf(0.95, 0.95, 0.95), shotSrc = listOf("sure", "sure", "sure"))
        }
        val h = Insights.sessionHints(ends)
        assertTrue(h.any { it.startsWith("The first arrow of an end scores 3.0 points below") }, h.toString())
        val coin = ends.map { it.copy(shotSrc = listOf("coin", "coin", "coin")) }
        assertTrue(Insights.sessionHints(coin).none { it.startsWith("The first arrow") })
    }

    @Test fun fatigueOverTheSession() {
        val ends = (1..9).map { n -> val v = if (n <= 3) 10 else if (n <= 6) 9 else 8
            EndDetail(n, true, 3, 3 * v, 0, List(3) { v.toString() }, null, 18, false, 0, null, 0.1 * n, 3, null, null, null, null) }
        val h = Insights.sessionHints(ends)
        assertTrue(h.any { it.startsWith("Scores fell from 10.00 to 8.00 per arrow") && it.contains("less steady") }, h.toString())
    }

    @Test fun statsAndBaseline() {
        val hits = listOf(Hit(10.0, 0.0, 10), Hit(-10.0, 0.0, 10), Hit(0.0, 10.0, 10), Hit(0.0, -10.0, 10))
        val ends = (1..4).map { n -> end(n, hits, shots = (0..3).map { ShotInfo(it, hold = 0.1 * n, holdMs = 1000, cant = 1.0 * it) }) }
        val st = Insights.sessionStats(ends)
        assertEquals(1.0, assertNotNull(st.groupRadius), 1e-9)
        assertEquals(0.25, assertNotNull(st.hold), 1e-9)
        assertEquals(1000.0, assertNotNull(st.holdMs), 1e-9)
        val b = Insights.baseline(ends, null, exclude = ends[3])
        assertEquals(0.2, assertNotNull(b.hold), 1e-9)
    }
}
