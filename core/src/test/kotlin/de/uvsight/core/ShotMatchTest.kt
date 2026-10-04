package de.uvsight.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShotMatchTest {
    private fun shots(vararg py: Double?, px: List<Double?>? = null, sdx: Double = 15.0, sdy: Double = 4.0) =
        EndShots(1, py.size, 18, sdx, sdy, 0, true, py.mapIndexed { i, y -> ShotInfo(i, py = y, px = px?.getOrNull(i)) })

    @Test fun heightSeparatesClearly() {
        // arrow 0 high right, arrow 1 low left; shot 0 predicted low, shot 1 high (cm from the mean)
        val hits = listOf(Hit(60.0, 80.0, 9), Hit(-50.0, -80.0, 8))
        val r = assertNotNull(ShotMatch.match(hits, shots(-8.0, 8.0)))
        assertEquals(listOf(1, 0), r.shotOf)
        assertTrue(r.conf.all { it > ShotMatch.SURE }, "sure: ${r.conf}")
    }

    @Test fun sameHeightStaysOpen() {
        // both arrows at the same height, no sideways prediction: a coin flip
        val hits = listOf(Hit(60.0, 0.0, 9), Hit(-60.0, 0.0, 9))
        val r = assertNotNull(ShotMatch.match(hits, shots(0.0, 0.0)))
        assertTrue(r.conf.all { it < ShotMatch.GUESS }, "open: ${r.conf}")
        assertEquals("coin", r.src(0))
        assertEquals(r.shotOf[1], r.second[0])
    }

    @Test fun sidewaysPredictionResolvesOnceLearned() {
        val hits = listOf(Hit(60.0, 0.0, 9), Hit(-60.0, 0.0, 9))
        val r = assertNotNull(ShotMatch.match(hits, shots(0.0, 0.0, px = listOf(-6.0, 6.0), sdx = 3.0)))
        assertEquals(listOf(1, 0), r.shotOf)
        assertTrue(r.conf.all { it > ShotMatch.SURE }, "sure sideways: ${r.conf}")
    }

    @Test fun countsMustAgree() {
        assertNull(ShotMatch.match(listOf(Hit(0.0, 0.0, 10)), shots(1.0, -1.0)))
    }

    @Test fun teachLineHasOnlySurePairs() {
        val hits = listOf(Hit(10.0, 20.0, 10), Hit(-10.0, -20.0, 9), Hit(0.0, 0.0, 9))
        val line = ShotMatch.teachLine(4, hits, listOf(2, 0, 1), listOf("sure", "coin", "user"))
        assertEquals("shot teach 4 2:10:20 1:0:0", line)
        assertNull(ShotMatch.teachLine(4, hits, listOf(2, 0, 1), listOf("coin", "coin", "guess")))
    }

    @Test fun hungarianFindsTheMinimum() {
        val cost = arrayOf(doubleArrayOf(4.0, 1.0, 3.0), doubleArrayOf(2.0, 0.0, 5.0), doubleArrayOf(3.0, 2.0, 2.0))
        assertEquals(listOf(1, 0, 2), ShotMatch.hungarian(cost).toList())   // 1 + 2 + 2 = 5
    }
}
