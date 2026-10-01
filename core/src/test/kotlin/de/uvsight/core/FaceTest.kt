package de.uvsight.core

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FaceTest {
    /** A camera looking at the face from an angle: a homography mapping face mm -> pixels. */
    private val h = Mat3(doubleArrayOf(3.1, 0.4, 1500.0, -0.2, 2.6, 1100.0, 0.00025, 0.00040, 1.0))
    private fun px(mmX: Double, mmY: Double) = h.apply(Pt(mmX, mmY))

    @Test fun fitRecoversRadialDistances() {
        val face = FaceType.WA40
        val r = face.blueEdgeMm
        val center = px(0.0, 0.0)
        val edge = listOf(10.0, 80.0, 150.0, 200.0, 290.0, 340.0).map { deg -> val t = Math.toRadians(deg); px(r * cos(t), r * sin(t)) }
        val g = FaceGeometry.fit(center, edge)
        assertNotNull(g)
        // points at known distances land at the right unit radius
        for ((mm, deg) in listOf(5.0 to 30.0, 25.0 to 200.0, 61.0 to 100.0, 119.0 to 300.0, 199.0 to 45.0)) {
            val t = Math.toRadians(deg)
            val u = g.toFace(px(mm * cos(t), mm * sin(t)))
            assertEquals(mm / r, hypot(u.x, u.y), 0.003, "distance $mm mm")
        }
        // round trip
        val back = g.toImage(g.toFace(Pt(1400.0, 1000.0)))
        assertEquals(1400.0, back.x, 1e-6); assertEquals(1000.0, back.y, 1e-6)
    }

    @Test fun rings() {
        val face = FaceType.WA40      // ring width 20 mm, arrow 6 mm
        fun ringAt(mm: Double) = Scoring.ring(Pt(mm / face.blueEdgeMm, 0.0), face)
        assertEquals(Scoring.X, ringAt(0.0)); assertEquals(Scoring.X, ringAt(12.0))   // 12 - 3 = 9 mm <= 10 mm
        assertEquals(10, ringAt(14.0))
        assertEquals(10, ringAt(22.0))      // shaft edge at 19 mm still touches the 10
        assertEquals(9, ringAt(24.0))
        assertEquals(5, ringAt(119.0))      // blue edge at 120 mm
        assertEquals(1, ringAt(199.0))
        assertEquals(Scoring.MISS, ringAt(204.0))
        assertEquals(Scoring.MISS, Scoring.ring(Pt(105.0 / FaceType.SPOT40.blueEdgeMm, 0.0), FaceType.SPOT40))   // ring 5 is not on a spot
        assertEquals(6, Scoring.ring(Pt(95.0 / FaceType.SPOT40.blueEdgeMm, 0.0), FaceType.SPOT40))
        assertEquals("X", Scoring.entry(Scoring.X)); assertEquals("M", Scoring.entry(0)); assertEquals(10, Scoring.points(Scoring.X))
    }

    @Test fun fourPointsWithCentre() {
        // frontal photo: 4 taps on the blue edge (top, bottom, left, right), a little off
        val c = Pt(640.0, 900.0); val r = 420.0
        val taps = listOf(Pt(c.x + r + 3, c.y - 2), Pt(c.x - 1, c.y + r + 2), Pt(c.x - r - 2, c.y + 1), Pt(c.x + 2, c.y - r + 3))
        val g = assertNotNull(FaceGeometry.fit(c, taps))
        val u = g.toFace(Pt(c.x + r / 2, c.y))
        assertTrue(kotlin.math.abs(hypot(u.x, u.y) - 0.5) < 0.02, "half radius, got ${hypot(u.x, u.y)}")
        // 5 badly spread points (a short arc) still give a geometry thanks to the centred fallback
        val arc = (0 until 5).map { val a = Math.toRadians(200.0 + it * 20); Pt(c.x + r * cos(a), c.y + r * sin(a)) }
        assertNotNull(FaceGeometry.fit(c, arc))
    }

    @Test fun rejectsBadInput() {
        assertNull(FaceGeometry.fit(Pt(0.0, 0.0), listOf(Pt(1.0, 0.0), Pt(0.0, 1.0), Pt(-1.0, 0.0))))                            // too few
        assertNull(FaceGeometry.fit(Pt(0.0, 0.0), listOf(Pt(1.0, 0.0), Pt(2.0, 0.0), Pt(3.0, 0.0), Pt(4.0, 0.0), Pt(5.0, 0.0))))   // a line
    }

    @Test fun groupCenter() {
        val c = Scoring.groupCenterMm(listOf(Pt(10.0, 20.0), Pt(30.0, -10.0)))
        assertNotNull(c); assertEquals(20.0, c.x, 1e-9); assertEquals(5.0, c.y, 1e-9)
        assertTrue(Scoring.groupCenterMm(emptyList()) == null)
    }
}

class CocoTest {
    @Test fun cocoHasArrowsAndFace() {
        val rec = PhotoRecord(id = "a", image = "a.jpg", width = 4000, height = 3000, rotation = 90, face = "WA40", faceDiameterMm = 400, arrowMm = 6.0,
            center = listOf(2000.0, 1500.0), edge = listOf(listOf(2600.0, 1500.0), listOf(2000.0, 2100.0), listOf(1400.0, 1500.0), listOf(2000.0, 900.0), listOf(2424.0, 1924.0)),
            conic = FaceGeometry.fit(Pt(2000.0, 1500.0), listOf(Pt(2600.0, 1500.0), Pt(2000.0, 2100.0), Pt(1400.0, 1500.0), Pt(2000.0, 900.0), Pt(2424.0, 1924.0)))!!.conic.toList(),
            arrows = listOf(ArrowMark(2050.0, 1520.0, 0.08, 0.03, 10.0, -4.0, Scoring.X, Scoring.X, "stylus", false)),
            sightShots = 1, distM = 18, sessionKey = "1-2", endN = 3, environment = "indoor", exif = mapOf("ISO" to "400"), device = "test", app = "1.0", timestamp = 1L)
        val json = Coco.build(listOf(rec))
        assertTrue(json.contains("\"category_id\":1") && json.contains("\"category_id\":2"))
        assertTrue(json.contains("\"file_name\":\"a.jpg\""))
        val b = Coco.ellipseBounds(rec.conic)!!
        assertEquals(1400.0, b[0], 2.0); assertEquals(900.0, b[1], 2.0); assertEquals(2600.0, b[2], 2.0); assertEquals(2100.0, b[3], 2.0)
    }
}
