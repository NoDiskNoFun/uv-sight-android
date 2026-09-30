package de.uvsight.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The collected photos as a COCO keypoint dataset, so training tools read them directly.
 * Category "arrow": one keypoint (the entry point) with a small box around it.
 * Category "face": the tapped centre and edge points as keypoints, box = ellipse bounds.
 */
object Coco {
    const val ARROW_BOX_PX = 48.0

    fun build(records: List<PhotoRecord>): String {
        var annId = 1
        val images = buildJsonArray {
            records.forEachIndexed { i, r ->
                add(buildJsonObject {
                    put("id", i + 1); put("file_name", r.image); put("width", r.width); put("height", r.height)
                    put("uv_face", r.face); put("uv_environment", r.environment); put("uv_device", r.device); put("uv_timestamp", r.timestamp)
                })
            }
        }
        val annotations = buildJsonArray {
            records.forEachIndexed { i, r ->
                for (a in r.arrows) {
                    add(buildJsonObject {
                        put("id", annId++); put("image_id", i + 1); put("category_id", 1)
                        put("bbox", num(a.px - ARROW_BOX_PX / 2, a.py - ARROW_BOX_PX / 2, ARROW_BOX_PX, ARROW_BOX_PX))
                        put("area", ARROW_BOX_PX * ARROW_BOX_PX); put("iscrowd", 0)
                        put("keypoints", num(a.px, a.py, 2.0)); put("num_keypoints", 1)
                        put("uv_ring", a.ring); put("uv_ring_auto", a.ringAuto); put("uv_tool", a.tool); put("uv_moved", a.moved)
                        if (!a.mmX.isNaN()) { put("uv_mm_x", a.mmX); put("uv_mm_y", a.mmY) }
                    })
                }
                val c = r.center
                if (c != null && r.edge.size >= 5) {
                    val xs = r.edge.map { it[0] } + c[0]; val ys = r.edge.map { it[1] } + c[1]
                    val bounds = ellipseBounds(r.conic) ?: doubleArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
                    val kps = ArrayList<Double>()
                    kps.addAll(listOf(c[0], c[1], 2.0))
                    for (e in r.edge) kps.addAll(listOf(e[0], e[1], 2.0))
                    add(buildJsonObject {
                        put("id", annId++); put("image_id", i + 1); put("category_id", 2)
                        put("bbox", num(bounds[0], bounds[1], bounds[2] - bounds[0], bounds[3] - bounds[1]))
                        put("area", (bounds[2] - bounds[0]) * (bounds[3] - bounds[1])); put("iscrowd", 0)
                        put("keypoints", JsonArray(kps.map { JsonPrimitive(it) })); put("num_keypoints", 1 + r.edge.size)
                        put("uv_face", r.face); put("uv_face_diameter_mm", r.faceDiameterMm)
                        r.conic?.let { put("uv_conic", JsonArray(it.map { v -> JsonPrimitive(v) })) }
                    })
                }
            }
        }
        val categories = buildJsonArray {
            add(buildJsonObject { put("id", 1); put("name", "arrow"); put("keypoints", JsonArray(listOf(JsonPrimitive("entry")))) })
            add(buildJsonObject { put("id", 2); put("name", "face"); put("keypoints", JsonArray((listOf("center") + (1..12).map { "edge$it" }).map { JsonPrimitive(it) })) })
        }
        val root: JsonObject = buildJsonObject {
            put("info", buildJsonObject { put("description", "UV-Sight photo scoring"); put("version", "1"); put("uv_app", records.firstOrNull()?.app ?: "") })
            put("images", images); put("annotations", annotations); put("categories", categories)
        }
        return root.toString()
    }

    private fun num(vararg v: Double) = JsonArray(v.map { JsonPrimitive(it) })

    /** Axis-aligned bounds [x0, y0, x1, y1] of the ellipse a x^2 + b xy + c y^2 + d x + e y + f = 0. */
    fun ellipseBounds(conic: List<Double>?): DoubleArray? {
        if (conic == null || conic.size != 6) return null
        val (a, b, c, d, e, f) = conic
        val det = 4 * a * c - b * b
        if (det <= 0) return null
        val cx = (b * e - 2 * c * d) / det; val cy = (b * d - 2 * a * e) / det
        val k = -(a * cx * cx + b * cx * cy + c * cy * cy + d * cx + e * cy + f)   // x'^T Q x' = k about the centre
        if (k <= 0) return null
        val hx = sqrt(max(0.0, k * c / (a * c - b * b / 4) / 1.0)) / sqrt(1.0)   // half extents of the conic's bounding box
        val hy = sqrt(max(0.0, k * a / (a * c - b * b / 4)))
        return doubleArrayOf(cx - hx, cy - hy, cx + hx, cy + hy).also { it[0] = min(it[0], it[2]); it[1] = min(it[1], it[3]) }
    }

    private operator fun List<Double>.component6() = this[5]
}
