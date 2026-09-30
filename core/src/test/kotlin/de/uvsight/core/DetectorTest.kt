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
}
