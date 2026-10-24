package de.uvsight.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BadgesTest {
    private fun session(key: String, t: Long, scored: Int = 6, avg: Double = 8.0, x: Int = 0) =
        ArchiveSession(key = key, epoch = 1, id = key.substringAfter('-').toLong(), endedAt = t, importedAt = t, ends = 2, shots = scored, scored = scored, avg = avg, x = x, min = 20)
    private fun end(n: Int, scores: List<String>, cant: Double? = null, hits: List<Hit>? = null) =
        EndDetail(n = n, valid = true, arrows = scores.size, sum = scores.sumOf { if (it == "X") 10 else if (it == "M") 0 else it.toInt() }, x = scores.count { it == "X" }, scores = scores, cant = cant, hits = hits)

    @Test fun levelsFollowTheDirection() {
        val atLeast = BadgeDef("a", "⭐", "Points", "endPoints", "end", "min", listOf(25.0, 27.0, 29.0))
        assertEquals(0, Badges.level(atLeast, 24.0)); assertEquals(1, Badges.level(atLeast, 25.0)); assertEquals(3, Badges.level(atLeast, 30.0)); assertEquals(0, Badges.level(atLeast, null))
        val atMost = BadgeDef("b", "📐", "Cant", "cant", "end", "max", listOf(2.0, 1.0, 0.5))
        assertEquals(0, Badges.level(atMost, 2.5)); assertEquals(2, Badges.level(atMost, 0.8)); assertEquals(3, Badges.level(atMost, 0.2))
    }

    @Test fun valuesPerScope() {
        val now = 1_000_000L
        val sessions = listOf(session("1-1", now - 1000, avg = 8.5, x = 1), session("1-2", now, avg = 9.0, x = 2))
        val ends = mapOf(
            "1-1" to listOf(end(1, listOf("9", "8", "8"), cant = -1.2), end(2, listOf("X", "9", "7"), cant = 0.4)),
            "1-2" to listOf(end(1, listOf("X", "X", "9"), cant = 0.3, hits = listOf(Hit(0.0, 0.0, 10), Hit(30.0, 40.0, 9), Hit(-30.0, -40.0, 9))), end(2, listOf("9", "9", "9"), cant = 0.1)),
        )
        assertEquals(29.0, Badges.best(BadgeDef("p", "", "", "endPoints", "end", "min", listOf(1.0)), sessions, ends, 0, now))
        assertEquals(0.1, Badges.best(BadgeDef("c", "", "", "cant", "end", "max", listOf(1.0)), sessions, ends, 0, now)!!, 1e-9)
        assertEquals(9.0, Badges.best(BadgeDef("s", "", "", "avg", "session", "min", listOf(1.0)), sessions, ends, 0, now))
        assertEquals(3.0, Badges.best(BadgeDef("x", "", "", "x", "total", "min", listOf(1.0)), sessions, ends, 0, now))
        assertEquals(9.0, Badges.best(BadgeDef("m", "", "", "endMin", "end", "min", listOf(1.0)), sessions, ends, 0, now))   // the best "lowest arrow" is the highest one
        val spread = Badges.best(BadgeDef("g", "", "", "spread", "end", "max", listOf(1.0)), sessions, ends, 0, now)!!
        assertTrue(spread > 30 && spread < 40, "mean distance from the group centre: $spread")
        assertEquals(4.0, Badges.best(BadgeDef("r", "", "", "noMissRow", "total", "min", listOf(1.0)), sessions, ends, 0, now))
        assertNull(Badges.best(BadgeDef("h", "", "", "hold", "end", "min", listOf(1.0)), sessions, ends, 0, now))
    }

    @Test fun fileRoundTripAndSightCommand() {
        val defs = Badges.templates().take(3) + BadgeDef("zz9", "🦊", "Fuchs", "cant", "session", "max", listOf(1.5, 1.0))
        val text = Badges.encode(defs)
        assertTrue(text.contains("uv-sight-badges"))
        val back = Badges.decode(text)
        assertNotNull(back); assertEquals(4, back.size); assertEquals(false, back[0].template); assertTrue(back[3].sameRule(defs[3]))
        assertNull(Badges.decode("{\"format\":\"something-else\"}"))
        assertEquals("badge set t_steady steady end min 60.00,70.00,80.00 🖐️ Steady hand", Badges.sightCommand(Badges.templates().first { it.id == "t_steady" }))
        assertTrue(Badges.describe(defs[3]).contains("1.5°"))
    }
}
