package de.uvsight.core

import kotlin.test.Test
import kotlin.test.assertEquals

class DetectorTest {
    private fun rows(vararg r: FloatArray) = r

    @Test fun letterboxMapsBack() {
        val lb = Letterbox.fit(4000, 3000, 640)
        assertEquals(0.16, lb.scale, 1e-9); assertEquals(0.0, lb.padX, 1e-9); assertEquals(80.0, lb.padY, 1e-9)
        val p = lb.toImage(320.0, 320.0)
        assertEquals(2000.0, p.x, 1e-9); assertEquals(1500.0, p.y, 1e-9)
    }

    @Test fun decodesBothLayoutsAndSuppresses() {
        val lb = Letterbox.fit(1000, 1000, 640)
        // three candidates: two on the same arrow, one elsewhere, one below the threshold
        val c = rows(
            floatArrayOf(100f, 100f, 30f, 30f, 0.9f, 101f, 99f, 1f),
            floatArrayOf(104f, 102f, 30f, 30f, 0.6f, 105f, 103f, 1f),
            floatArrayOf(400f, 300f, 30f, 30f, 0.7f, 402f, 298f, 1f),
            floatArrayOf(500f, 500f, 30f, 30f, 0.2f, 500f, 500f, 1f),
        )
        val n = c.size
        val byRow = FloatArray(n * 8) { i -> c[i / 8][i % 8] }                       // [N, 8]
        val byCol = FloatArray(n * 8) { i -> c[i % n][i / n] }                       // [8, N]
        for ((out, shape) in listOf(byRow to intArrayOf(1, n, 8), byCol to intArrayOf(1, 8, n))) {
            val d = YoloPoseDecoder.decode(out, shape, lb, 0.4)
            assertEquals(2, d.size, "two arrows for shape ${shape.toList()}")
            assertEquals(0.9, d[0].conf, 1e-6); assertEquals(101 / lb.scale, d[0].x, 1e-6); assertEquals(99 / lb.scale, d[0].y, 1e-6)
            assertEquals(0.7, d[1].conf, 1e-6)
        }
    }

    @Test fun recognisesKeypointCountFromTheShape() {
        assertEquals(1, YoloPoseDecoder.keypointsOf(intArrayOf(1, 8, 8400)))
        assertEquals(1, YoloPoseDecoder.keypointsOf(intArrayOf(1, 2100, 8)))
        assertEquals(5, YoloPoseDecoder.keypointsOf(intArrayOf(1, 20, 8400)))
        assertEquals(5, YoloPoseDecoder.keypointsOf(intArrayOf(1, 13125, 20)))
        assertEquals(null, YoloPoseDecoder.keypointsOf(intArrayOf(1, 7, 8400)))
        assertEquals(null, YoloPoseDecoder.keypointsOf(intArrayOf(1, 84, 8400)))       // a detection head, not pose
    }

    @Test fun decodesFiveKeypointsAndDenormalises() {
        val lb = Letterbox.fit(640, 640, 640)
        // one face candidate with fractions of the input instead of pixels, [1, 20, N] layout with N = 2
        val face = floatArrayOf(0.5f, 0.5f, 0.6f, 0.6f, 0.9f, 0.5f, 0.5f, 1f, 0.5f, 0.2f, 1f, 0.8f, 0.5f, 1f, 0.5f, 0.8f, 1f, 0.2f, 0.5f, 1f)
        val other = FloatArray(20) { 0.1f }.also { it[4] = 0.05f }
        val n = 2
        val out = FloatArray(20 * n) { i -> val k = i / n; val c = i % n; if (c == 0) face[k] else other[k] }
        val shape = intArrayOf(1, 20, n)
        YoloPoseDecoder.denormalise(out, shape, 640)
        val d = YoloPoseDecoder.decode(out, shape, lb, 0.4)
        assertEquals(1, d.size)
        assertEquals(5, d[0].kpts.size)
        assertEquals(320.0, d[0].kpts[0].x, 1e-3); assertEquals(320.0, d[0].kpts[0].y, 1e-3)   // centre
        assertEquals(128.0, d[0].kpts[1].y, 1e-3)                                             // top
        assertEquals(512.0, d[0].kpts[2].x, 1e-3)                                             // right
        assertEquals(384.0, d[0].boxW, 1e-3)
        val g = FaceGeometry.fit(d[0].kpts[0], d[0].kpts.drop(1))
        assertEquals(true, g != null)
    }
}
