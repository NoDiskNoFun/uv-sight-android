package de.uvsight.core

import kotlin.math.max
import kotlin.math.min

/** One proposal of the arrow detector: entry point in image pixels and its confidence. */
data class Detection(val x: Double, val y: Double, val conf: Double, val boxW: Double, val boxH: Double)

/** How the photo was fitted into the model's square input: scale and the padding offsets. */
data class Letterbox(val scale: Double, val padX: Double, val padY: Double, val inputSize: Int) {
    companion object {
        fun fit(imageW: Int, imageH: Int, inputSize: Int): Letterbox {
            val s = min(inputSize.toDouble() / imageW, inputSize.toDouble() / imageH)
            return Letterbox(s, (inputSize - imageW * s) / 2, (inputSize - imageH * s) / 2, inputSize)
        }
    }
    fun toImage(px: Double, py: Double) = Pt((px - padX) / scale, (py - padY) / scale)
}

/**
 * Decodes the output of a YOLO pose model with one keypoint per arrow (what training/ exports):
 * rows [cx, cy, w, h, conf, kx, ky, kv] per candidate, either as [8, N] or [N, 8], coordinates
 * in input pixels. The entry point is the keypoint; the box only serves the suppression.
 */
object YoloPoseDecoder {
    const val ROW = 8

    fun decode(output: FloatArray, shape: IntArray, box: Letterbox, confThreshold: Double, iouThreshold: Double = 0.5): List<Detection> {
        val dims = shape.filter { it != 1 }
        require(dims.size == 2) { "unexpected output shape ${shape.toList()}" }
        val transposed = dims[0] == ROW          // [8, N]
        val n = if (transposed) dims[1] else dims[0]
        require((if (transposed) dims[1] else dims[1]) > 0 && (transposed || dims[1] == ROW)) { "unexpected output shape ${shape.toList()}" }
        fun v(i: Int, k: Int) = if (transposed) output[k * n + i] else output[i * ROW + k]
        val cands = ArrayList<Detection>()
        for (i in 0 until n) {
            val conf = v(i, 4).toDouble()
            if (conf < confThreshold) continue
            val kx = v(i, 5).toDouble(); val ky = v(i, 6).toDouble()
            val p = box.toImage(kx, ky)
            cands.add(Detection(p.x, p.y, conf, v(i, 2) / box.scale, v(i, 3) / box.scale))
        }
        return nms(cands, iouThreshold)
    }

    /** Boxes are centred on the entry point; overlapping proposals collapse to the most confident one. */
    fun nms(cands: List<Detection>, iouThreshold: Double): List<Detection> {
        val sorted = cands.sortedByDescending { it.conf }
        val keep = ArrayList<Detection>()
        for (c in sorted) if (keep.none { iou(it, c) > iouThreshold }) keep.add(c)
        return keep
    }

    private fun iou(a: Detection, b: Detection): Double {
        val ax0 = a.x - a.boxW / 2; val ay0 = a.y - a.boxH / 2; val ax1 = a.x + a.boxW / 2; val ay1 = a.y + a.boxH / 2
        val bx0 = b.x - b.boxW / 2; val by0 = b.y - b.boxH / 2; val bx1 = b.x + b.boxW / 2; val by1 = b.y + b.boxH / 2
        val iw = max(0.0, min(ax1, bx1) - max(ax0, bx0)); val ih = max(0.0, min(ay1, by1) - max(ay0, by0))
        val inter = iw * ih
        val union = a.boxW * a.boxH + b.boxW * b.boxH - inter
        return if (union <= 0) 0.0 else inter / union
    }
}
