package de.uvsight.core

import kotlin.math.max
import kotlin.math.min

/**
 * One proposal of a pose model in image pixels: for the arrow detector the entry point (x, y) and its
 * confidence; for the face finder [kpts] holds the centre and the top, right, bottom and left edge points
 * (x, y then repeat the first keypoint).
 */
data class Detection(val x: Double, val y: Double, val conf: Double, val boxW: Double, val boxH: Double, val kpts: List<Pt> = listOf(Pt(x, y)))

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
 * Decodes the output of a YOLO pose model (what training/ exports): rows [cx, cy, w, h, conf, k1x, k1y, k1v, ...]
 * per candidate with K keypoints, either as [5+3K, N] or [N, 5+3K], coordinates in input pixels. The arrow
 * detector has one keypoint (the entry point), the face finder five. The box only serves the suppression.
 */
object YoloPoseDecoder {
    const val ROW = 8                       // row length of the one-keypoint (arrow) model
    private const val MAX_KPTS = 17

    fun rowSize(kpts: Int) = 5 + 3 * kpts

    /** Keypoints per candidate the output shape describes, or null if it is not a pose output. */
    fun keypointsOf(shape: IntArray): Int? {
        val dims = shape.filter { it != 1 }
        if (dims.size != 2) return null
        // the row is the dimension of the form 5 + 3K; the candidate count never has it for a usual input size
        val row = dims.filter { it >= ROW && (it - 5) % 3 == 0 && (it - 5) / 3 <= MAX_KPTS }.minOrNull() ?: return null
        return (row - 5) / 3
    }

    /** Index of entry k of candidate i in [output] of the given shape (k: 0..3 box, 4 conf, 5.. keypoints). */
    private fun layout(shape: IntArray): Triple<Int, Int, Boolean> {
        val k = keypointsOf(shape) ?: throw IllegalArgumentException("unexpected output shape ${shape.toList()}")
        val dims = shape.filter { it != 1 }
        val row = rowSize(k)
        val transposed = dims[0] == row            // [row, N]
        return Triple(row, if (transposed) dims[1] else dims[0], transposed)
    }

    /**
     * TFLite exports of YOLO may give coordinates as fractions of the input: when no keypoint x exceeds 1.5,
     * all coordinate entries are scaled to input pixels in place.
     */
    fun denormalise(output: FloatArray, shape: IntArray, inputSize: Int) {
        val (row, n, transposed) = layout(shape)
        fun idx(i: Int, k: Int) = if (transposed) k * n + i else i * row + k
        var maxCoord = 0f
        for (i in 0 until n) for (k in 5 until row step 3) maxCoord = max(maxCoord, output[idx(i, k)])
        if (maxCoord > 1.5f) return
        val coords = listOf(0, 1, 2, 3) + (5 until row).filter { (it - 5) % 3 != 2 }
        for (i in 0 until n) for (k in coords) output[idx(i, k)] *= inputSize
    }

    fun decode(output: FloatArray, shape: IntArray, box: Letterbox, confThreshold: Double, iouThreshold: Double = 0.5): List<Detection> {
        val (row, n, transposed) = layout(shape)
        val kpts = (row - 5) / 3
        fun v(i: Int, k: Int) = if (transposed) output[k * n + i] else output[i * row + k]
        val cands = ArrayList<Detection>()
        for (i in 0 until n) {
            val conf = v(i, 4).toDouble()
            if (conf < confThreshold) continue
            val pts = (0 until kpts).map { j -> box.toImage(v(i, 5 + 3 * j).toDouble(), v(i, 6 + 3 * j).toDouble()) }
            cands.add(Detection(pts[0].x, pts[0].y, conf, v(i, 2) / box.scale, v(i, 3) / box.scale, pts))
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
