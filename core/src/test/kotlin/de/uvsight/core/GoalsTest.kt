package de.uvsight.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoalsTest {
    private val day = Game.DAY_MS
    private fun session(key: String, t: Long, avg: Double = 8.0, scored: Int = 6) =
        ArchiveSession(key = key, epoch = 1, id = key.substringAfter('-').toLong(), endedAt = t, importedAt = t, ends = 2, shots = scored, scored = scored, avg = avg, x = 0, min = 20)
    private fun end(n: Int, scores: List<String>) =
        EndDetail(n = n, valid = true, arrows = scores.size, sum = scores.sumOf { if (it == "X") 10 else if (it == "M") 0 else it.toInt() }, x = scores.count { it == "X" }, scores = scores)

    @Test fun endsAboveAverageTodayAndBestInPeriod() {
        val now = 200 * day + 10 * 3600_000L
        val sessions = listOf(session("1-1", now - 3 * day), session("1-2", now - 1000))
        val ends = mapOf("1-1" to listOf(end(1, listOf("7", "7", "7")), end(2, listOf("8", "8", "8"))), "1-2" to listOf(end(1, listOf("9", "9", "9")), end(2, listOf("6", "7", "7"))))
        val g = Goals.defaultGoal()
        val p = Goals.progress(g, sessions, ends, now, 0, null)
        assertEquals(1.0, p.value); assertEquals(false, p.reached); assertEquals(0.5f, p.fraction)   // one end today above the 7.75 average
        val best = Goals.progress(GoalDef("x", "", "Best end today", "endPoints", "end", "min", 25.0, "day"), sessions, ends, now, 0, null)
        assertEquals(27.0, best.value); assertTrue(best.reached); assertEquals(1f, best.fraction)
        val week = Goals.progress(GoalDef("w", "", "Arrows this week", "arrows", "total", "min", 20.0, "week"), sessions, ends, now, 0, null)
        assertTrue((week.value ?: 0.0) >= 6.0, "arrows this week: ${week.value}")
        val cant = Goals.progress(GoalDef("c", "", "Cant", "cant", "end", "max", 1.0, "all"), sessions, ends, now, 0, null)
        assertEquals(null, cant.value); assertEquals(false, cant.reached)
        assertTrue(Goals.describe(g).contains("2"))
    }
}
