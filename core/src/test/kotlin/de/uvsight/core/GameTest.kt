package de.uvsight.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GameTest {
    private val day = Game.DAY_MS
    private fun session(key: String, endedAt: Long, scored: Int = 18, avg: Double = 8.0, x: Int = 1) =
        ArchiveSession(key = key, epoch = 1, id = key.substringAfter('-').toLong(), endedAt = endedAt, importedAt = endedAt, ends = 3, shots = scored, scored = scored, avg = avg, x = x, min = 30)
    private fun end(n: Int, scores: List<String>, shots: List<ShotInfo>? = null) =
        EndDetail(n = n, valid = true, arrows = scores.size, sum = scores.sumOf { if (it == "X") 10 else if (it == "M") 0 else it.toInt() }, x = scores.count { it == "X" }, scores = scores, shots = shots)

    @Test fun steadyHandRewardsAQuietHold() {
        assertEquals(null, Game.steadyHand(ShotInfo(0)))
        val quiet = Game.steadyHand(ShotInfo(0, hold = 0.1, holdMs = 1200, drop = 0.05))!!
        val shaky = Game.steadyHand(ShotInfo(0, hold = 0.9, holdMs = 300, drop = -0.6))!!
        assertTrue(quiet >= 85, "quiet $quiet"); assertTrue(shaky <= 20, "shaky $shaky"); assertTrue(quiet > shaky)
    }

    @Test fun streaksBestsAndBadges() {
        val now = 100 * day + 12 * 3600_000L
        val sessions = listOf(session("1-1", now - 2 * day), session("1-2", now - day, avg = 8.5, x = 2), session("1-3", now, avg = 9.0, x = 4))
        val ends = mapOf(
            "1-1" to listOf(end(1, listOf("9", "8", "7")), end(2, listOf("10", "9", "M"))),
            "1-2" to listOf(end(1, listOf("X", "9", "9")), end(2, listOf("8", "8", "8"))),
            "1-3" to listOf(end(1, listOf("X", "10", "9"), listOf(ShotInfo(0, hold = 0.1, holdMs = 1000, drop = 0.0))), end(2, listOf("9", "9", "9"))),
        )
        assertEquals(3, Game.streakDays(sessions, now, 0))
        assertEquals(0, Game.streakDays(sessions, now + 3 * day, 0))
        val b = Game.bests(sessions, ends)
        assertEquals(29, b.endSum); assertEquals(9.0, b.sessionAvg); assertEquals(4, b.sessionX); assertTrue((b.steady ?: 0) >= 85)
        assertEquals(4, Game.endsWithoutMiss(sessions, ends))      // newest four ends are clean, then the M
        val lv = Badges.levels(Badges.templates(), sessions, ends, 0, now)
        assertEquals(1, lv["t_firstx"]); assertEquals(3, lv["t_golden"]); assertEquals(1, lv["t_streak"]); assertEquals(3, lv["t_steady"])
        assertEquals(0, lv["t_arrows"]); assertEquals(0, lv["t_clean"])
        val (done, goal) = Game.dailyGoal(sessions, ends, 2, now, 0)
        assertEquals(2, goal); assertEquals(2, done)                 // both ends today beat the overall per-arrow average
    }

    @Test fun celebratesOnlyImprovements() {
        val now = 50 * day
        val s1 = listOf(session("1-1", now, avg = 8.0, x = 1)); val e1 = mapOf("1-1" to listOf(end(1, listOf("9", "8", "7"))))
        val defs = Badges.templates()
        val before = Game.snapshot(defs, s1, e1, 0, now)
        val same = Game.celebrations(before, Game.snapshot(defs, s1, e1, 0, now), defs)
        assertEquals(0, same.size)
        val e2 = mapOf("1-1" to listOf(end(1, listOf("9", "8", "7")), end(2, listOf("X", "10", "9"))))
        val after = Game.snapshot(defs, s1, e2, 0, now)
        val c = Game.celebrations(before, after, defs)
        assertTrue(c.any { it.second == "29" }, "best end: $c")
        assertTrue(c.any { it.first.startsWith(tr("Golden end")) && it.second.startsWith("🥇") }, "gold badge: $c")
    }
}
