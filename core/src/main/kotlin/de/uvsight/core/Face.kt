package de.uvsight.core

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Target faces the photo scoring knows. Ring width = diameter / 20; spots only print rings 6 to 10. */
enum class FaceType(val label: String, val diameterMm: Int, val minRing: Int) {
    WA40("40 cm", 400, 1), WA60("60 cm", 600, 1), WA80("80 cm", 800, 1), WA122("122 cm", 1220, 1), SPOT40("Spot 40 cm", 400, 6);
    val ringMm: Double get() = diameterMm / 20.0
    /** Radius of the outer edge of the blue ring (ring 5) in mm: the edge the user marks. */
    val blueEdgeMm: Double get() = 6 * ringMm
}

data class Pt(val x: Double, val y: Double)

/** Small 3x3 matrix for homogeneous plane transforms. */
class Mat3(val m: DoubleArray) {
    init { require(m.size == 9) }
    operator fun get(r: Int, c: Int) = m[r * 3 + c]
    operator fun times(o: Mat3): Mat3 {
        val r = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) r[i * 3 + j] = this[i, 0] * o[0, j] + this[i, 1] * o[1, j] + this[i, 2] * o[2, j]
        return Mat3(r)
    }
    fun transpose() = Mat3(doubleArrayOf(this[0, 0], this[1, 0], this[2, 0], this[0, 1], this[1, 1], this[2, 1], this[0, 2], this[1, 2], this[2, 2]))
    fun inverse(): Mat3 {
        val a = this
        val c00 = a[1, 1] * a[2, 2] - a[1, 2] * a[2, 1]; val c01 = -(a[1, 0] * a[2, 2] - a[1, 2] * a[2, 0]); val c02 = a[1, 0] * a[2, 1] - a[1, 1] * a[2, 0]
        val c10 = -(a[0, 1] * a[2, 2] - a[0, 2] * a[2, 1]); val c11 = a[0, 0] * a[2, 2] - a[0, 2] * a[2, 0]; val c12 = -(a[0, 0] * a[2, 1] - a[0, 1] * a[2, 0])
        val c20 = a[0, 1] * a[1, 2] - a[0, 2] * a[1, 1]; val c21 = -(a[0, 0] * a[1, 2] - a[0, 2] * a[1, 0]); val c22 = a[0, 0] * a[1, 1] - a[0, 1] * a[1, 0]
        val det = a[0, 0] * c00 + a[0, 1] * c01 + a[0, 2] * c02
        require(abs(det) > 1e-18) { "singular" }
        return Mat3(doubleArrayOf(c00, c10, c20, c01, c11, c21, c02, c12, c22).map { it / det }.toDoubleArray())
    }
    fun apply(p: Pt): Pt {
        val w = this[2, 0] * p.x + this[2, 1] * p.y + this[2, 2]
        return Pt((this[0, 0] * p.x + this[0, 1] * p.y + this[0, 2]) / w, (this[1, 0] * p.x + this[1, 1] * p.y + this[1, 2]) / w)
    }
    fun applyVec(v: DoubleArray) = doubleArrayOf(
        this[0, 0] * v[0] + this[0, 1] * v[1] + this[0, 2] * v[2],
        this[1, 0] * v[0] + this[1, 1] * v[1] + this[1, 2] * v[2],
        this[2, 0] * v[0] + this[2, 1] * v[1] + this[2, 2] * v[2])
    companion object {
        fun identity() = Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))
    }
}

/**
 * Where the face is in the photo. Built from the tapped centre and at least five points on
 * the outer edge of the blue ring. The perspective is removed with the classic trick: the
 * polar of the centre's image with respect to the ellipse is the image of the line at
 * infinity; sending it back to infinity leaves an affine ellipse, which a symmetric linear
 * map turns into the unit circle. Distances from the centre are then true face distances,
 * in units of the blue ring's radius, whatever the camera angle.
 */
class FaceGeometry private constructor(
    /** image -> unit face (blue edge radius = 1) */
    private val toFaceM: Mat3,
    private val toImageM: Mat3,
    /** conic in image pixels: a x^2 + b xy + c y^2 + d x + e y + f = 0 */
    val conic: DoubleArray,
) {
    fun toFace(p: Pt): Pt = toFaceM.apply(p)
    fun toImage(u: Pt): Pt = toImageM.apply(u)
    /** Image points of the circle with the given radius (1 = blue edge), for drawing. */
    fun circle(radius: Double, n: Int = 72): List<Pt> = (0 until n).map { i -> val t = 2 * Math.PI * i / n; toImage(Pt(radius * cos(t), radius * sin(t))) }

    companion object {
        /** null if the points do not describe an ellipse around the centre. */
        fun fit(center: Pt, edge: List<Pt>): FaceGeometry? {
            if (edge.size < 5) return null
            // Condition the coordinates: centre at the origin, edge radius about 1
            val scale = edge.map { hypot(it.x - center.x, it.y - center.y) }.average()
            if (scale < 1e-6) return null
            val t = Mat3(doubleArrayOf(1 / scale, 0.0, -center.x / scale, 0.0, 1 / scale, -center.y / scale, 0.0, 0.0, 1.0))
            val pts = edge.map { t.apply(it) }
            // Least squares conic with a + c = 1:  a (x^2 - y^2) + b xy + d x + e y + f = -y^2
            val a = Array(5) { DoubleArray(5) }; val b = DoubleArray(5)
            for (p in pts) {
                val row = doubleArrayOf(p.x * p.x - p.y * p.y, p.x * p.y, p.x, p.y, 1.0)
                val y = -p.y * p.y
                for (i in 0..4) { b[i] += row[i] * y; for (j in 0..4) a[i][j] += row[i] * row[j] }
            }
            val sol = solve(a, b) ?: return null
            val ca = sol[0]; val cb = sol[1]; val cc = 1 - ca; val cd = sol[2]; val ce = sol[3]; val cf = sol[4]
            if (cb * cb - 4 * ca * cc >= 0) return null                     // not an ellipse
            val cN = Mat3(doubleArrayOf(ca, cb / 2, cd / 2, cb / 2, cc, ce / 2, cd / 2, ce / 2, cf))
            // centre is the origin in normalised coordinates: its polar is the last row of C
            val l = cN.applyVec(doubleArrayOf(0.0, 0.0, 1.0))
            if (abs(l[2]) < 1e-12) return null
            val h1 = Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, l[0] / l[2], l[1] / l[2], 1.0))
            val h1i = h1.inverse()
            val c2 = h1i.transpose() * cN * h1i                              // affine ellipse, centred at the origin
            val gamma = c2[2, 2]
            if (gamma >= 0) return null
            val s00 = c2[0, 0] / -gamma; val s01 = c2[0, 1] / -gamma; val s11 = c2[1, 1] / -gamma
            val m = symSqrt(s00, s01, s11) ?: return null                    // u = M p  puts the ellipse on the unit circle
            val mM = Mat3(doubleArrayOf(m[0], m[1], 0.0, m[1], m[2], 0.0, 0.0, 0.0, 1.0))
            val toFace = mM * h1 * t
            // conic back in pixel coordinates: C = T^T C_N T
            val cPix = t.transpose() * cN * t
            val conic = doubleArrayOf(cPix[0, 0], 2 * cPix[0, 1], cPix[1, 1], 2 * cPix[0, 2], 2 * cPix[1, 2], cPix[2, 2])
            return runCatching { FaceGeometry(toFace, toFace.inverse(), conic) }.getOrNull()
        }

        /** Symmetric square root of a positive definite 2x2 matrix [[s00, s01], [s01, s11]]. */
        private fun symSqrt(s00: Double, s01: Double, s11: Double): DoubleArray? {
            val tr = s00 + s11; val det = s00 * s11 - s01 * s01
            if (det <= 0 || tr <= 0) return null
            val disc = sqrt(max(0.0, tr * tr / 4 - det))
            val l1 = tr / 2 + disc; val l2 = tr / 2 - disc
            if (l2 <= 0) return null
            val theta = if (abs(s01) < 1e-15 && abs(s00 - s11) < 1e-15) 0.0 else 0.5 * atan2(2 * s01, s00 - s11)
            val c = cos(theta); val s = sin(theta)
            val r1 = sqrt(l1); val r2 = sqrt(l2)
            // R diag(r1, r2) R^T with R = [[c, -s], [s, c]]
            return doubleArrayOf(c * c * r1 + s * s * r2, c * s * (r1 - r2), s * s * r1 + c * c * r2)
        }

        private fun solve(a0: Array<DoubleArray>, b0: DoubleArray): DoubleArray? {
            val n = b0.size
            val a = Array(n) { a0[it].copyOf() }; val b = b0.copyOf()
            for (c in 0 until n) {
                var piv = c
                for (r in c + 1 until n) if (abs(a[r][c]) > abs(a[piv][c])) piv = r
                if (abs(a[piv][c]) < 1e-12) return null
                if (piv != c) { val tmp = a[c]; a[c] = a[piv]; a[piv] = tmp; val tb = b[c]; b[c] = b[piv]; b[piv] = tb }
                for (r in 0 until n) {
                    if (r == c) continue
                    val f = a[r][c] / a[c][c]
                    for (k in c until n) a[r][k] -= f * a[c][k]
                    b[r] -= f * b[c]
                }
            }
            return DoubleArray(n) { b[it] / a[it][it] }
        }
    }
}

/** Ring values: 11 = X, 10..1, 0 = miss. */
object Scoring {
    const val X = 11
    const val MISS = 0
    const val DEFAULT_ARROW_MM = 6.0

    /** Ring for a hit at unit-face coordinates u (blue edge radius = 1), with the line-cutter rule. */
    fun ring(u: Pt, face: FaceType, arrowMm: Double = DEFAULT_ARROW_MM): Int {
        val rMm = hypot(u.x, u.y) * face.blueEdgeMm
        val edge = max(0.0, rMm - arrowMm / 2)          // the shaft's near edge decides
        val rw = edge / face.ringMm
        if (rw >= 10) return MISS
        val n = 10 - floor(rw).toInt()
        if (n < face.minRing) return MISS
        return if (n == 10 && rw <= 0.5) X else n
    }
    fun label(ring: Int) = when (ring) { X -> "X"; MISS -> "M"; else -> ring.toString() }
    fun points(ring: Int) = if (ring == X) 10 else ring
    /** Entry as the keypad produces it ("X", "10" ... "1", "M"). */
    fun entry(ring: Int) = label(ring)
    fun mmFromCenter(u: Pt, face: FaceType) = Pt(u.x * face.blueEdgeMm, -u.y * face.blueEdgeMm)   // y up
    /** Mean position of the hits in mm (x right, y up); null without hits. */
    fun groupCenterMm(hits: List<Pt>): Pt? = if (hits.isEmpty()) null else Pt(hits.sumOf { it.x } / hits.size, hits.sumOf { it.y } / hits.size)
}

/** One arrow of an end as placed on a photo: in face millimetres (x right, y up) and its ring. */
@Serializable
data class Hit(val mmX: Double, val mmY: Double, val ring: Int)

/** One arrow mark on a photo, as stored for training. */
@Serializable
data class ArrowMark(
    val px: Double, val py: Double,          // upright image pixels
    val u: Double, val v: Double,            // unit face coordinates (blue edge radius = 1), NaN without face marking
    val mmX: Double, val mmY: Double,        // face millimetres, x right, y up
    val ringAuto: Int,                       // ring the app computed (-1 without face marking)
    val ring: Int,                           // ring after the user's correction
    val tool: String,                        // "stylus" / "finger" / "mouse"
    val moved: Boolean,                      // dragged after placing
    val source: String = "user",             // "user" (tapped) or "model" (proposed by the detector)
)

/** Everything about one scored photo, saved as a training example. */
@Serializable
data class PhotoRecord(
    val id: String,
    val image: String,                       // file name of the JPEG next to this record
    val width: Int, val height: Int,         // upright image size
    val rotation: Int,                       // degrees applied to the camera file to make it upright
    val face: String,                        // FaceType name
    val faceDiameterMm: Int,
    val arrowMm: Double,
    val center: List<Double>?,               // tapped centre [x, y], null if the face was not marked
    val edge: List<List<Double>>,            // tapped blue-edge points
    val conic: List<Double>?,                // fitted ellipse a..f in pixels
    val arrows: List<ArrowMark>,
    val sightShots: Int?,                    // shots the sight counted for this end
    val distM: Int,
    val sessionKey: String?,
    val endN: Int?,
    val environment: String,                 // "outdoor" / "indoor"
    val exif: Map<String, String>,
    val device: String,
    val app: String,
    val timestamp: Long,
    val model: String? = null,               // detection model that proposed marks, if any
    val modelConf: Double? = null,           // confidence threshold used
)
