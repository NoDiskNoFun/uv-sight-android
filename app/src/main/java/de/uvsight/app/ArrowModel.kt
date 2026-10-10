package de.uvsight.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import de.uvsight.core.Detection
import de.uvsight.core.Letterbox
import de.uvsight.core.YoloPoseDecoder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

/** One TFLite model of the set, as the app read it when it was imported. */
@Serializable
data class ModelPart(val name: String, val inputSize: Int, val inputType: String, val outputShape: List<Int>, val keypoints: Int = 1)

/** The trainer's evaluation of the models on its validation photos (model.json in the ZIP); every field may be missing. */
@Serializable
data class ModelEval(
    @SerialName("train_photos") val trainPhotos: Int? = null, @SerialName("val_photos") val valPhotos: Int? = null,
    val arrows: Int? = null, val found: Int? = null, @SerialName("false_alarms") val falseAlarms: Int? = null,
    val recall: Double? = null, val precision: Double? = null,
    @SerialName("mm_error_median") val mmErrorMedian: Double? = null, @SerialName("ring_accuracy") val ringAccuracy: Double? = null,
    @SerialName("face_photos") val facePhotos: Int? = null, @SerialName("face_found") val faceFound: Int? = null,
    @SerialName("face_center_mm_median") val faceCenterMm: Double? = null, @SerialName("face_ring_accuracy") val faceRingAccuracy: Double? = null,
)

/**
 * What is installed: the arrow detector (the fields name..outputShape, kept flat so model.json files of
 * earlier versions still read), the face finder if the ZIP had one, and the trainer's evaluation.
 */
@Serializable
data class ModelInfo(
    val name: String, val inputSize: Int, val inputType: String, val outputShape: List<Int>, val imported: Long,
    val face: ModelPart? = null, val eval: ModelEval? = null, val trained: Long? = null, val arrowsPresent: Boolean = true,
) {
    val arrows: ModelPart? get() = if (arrowsPresent) ModelPart(name, inputSize, inputType, outputShape, 1) else null
}

/**
 * The detection models of the app, kept in private storage: arrows.tflite (entry point per arrow) and
 * face.tflite (centre and four edge points of the blue ring), plus model.json with what the trainer measured.
 */
class ModelStore(context: Context) {
    private val dir = File(context.filesDir, "model").apply { mkdirs() }
    val arrowsFile = File(dir, "arrows.tflite")
    val faceFile = File(dir, "face.tflite")
    /** The arrow model, as earlier versions named it. */
    val modelFile get() = arrowsFile
    private val infoFile = File(dir, "model.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val hasArrows get() = arrowsFile.exists()
    val hasFace get() = faceFile.exists()

    fun info(): ModelInfo? = if ((hasArrows || hasFace) && infoFile.exists()) runCatching { json.decodeFromString(ModelInfo.serializer(), infoFile.readText()) }.getOrNull() else null

    /**
     * Imports a model ZIP from the trainer (arrows.tflite, face.tflite, model.json; replaces everything) or a
     * single .tflite file (replaces the arrow or the face model, whichever its keypoint count says).
     * Throws with a plain message when nothing usable is in the file.
     */
    fun import(bytes: ByteArray, name: String): ModelInfo {
        val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        val parts = HashMap<Int, Pair<ByteArray, ModelPart>>()        // keypoints -> model bytes and what the probe read
        var evalJson: JsonObject? = null
        if (isZip) {
            ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val base = e.name.substringAfterLast('/')
                    when {
                        e.isDirectory -> {}
                        base.endsWith(".tflite") -> { val b = z.readBytes(); val part = probe(b, base); parts[part.keypoints] = b to part }
                        base == "model.json" -> evalJson = runCatching { json.parseToJsonElement(z.readBytes().decodeToString()).jsonObject }.getOrNull()
                    }
                }
            }
            if (parts.isEmpty()) throw IllegalArgumentException("Not a model ZIP: no .tflite file inside")
        } else { val part = probe(bytes, name); parts[part.keypoints] = bytes to part }
        val unknown = parts.keys.filter { it != 1 && it != 5 }
        if (unknown.isNotEmpty()) throw IllegalArgumentException("Not a usable model: ${unknown.first()} keypoints per object (1 = arrows, 5 = face)")
        val old = info()
        if (isZip) { arrowsFile.delete(); faceFile.delete() }
        parts[1]?.let { arrowsFile.writeBytes(it.first) }
        parts[5]?.let { faceFile.writeBytes(it.first) }
        val arrows = if (hasArrows) (parts[1]?.second ?: old?.arrows) else null
        val face = if (hasFace) (parts[5]?.second ?: old?.face) else null
        val eval = evalJson?.get("eval")?.let { runCatching { json.decodeFromJsonElement(ModelEval.serializer(), it) }.getOrNull() }
        val setName = evalJson?.get("name")?.jsonPrimitive?.contentOrNull ?: (if (isZip) name.removeSuffix(".zip") else arrows?.name ?: face?.name ?: name)
        val info = ModelInfo(
            name = setName, inputSize = arrows?.inputSize ?: face?.inputSize ?: 0, inputType = arrows?.inputType ?: "", outputShape = arrows?.outputShape ?: emptyList(),
            imported = System.currentTimeMillis(), face = face, eval = if (isZip) eval else null,
            trained = evalJson?.get("trained")?.jsonPrimitive?.doubleOrNull?.let { (it * 1000).toLong() }, arrowsPresent = arrows != null,
        )
        infoFile.writeText(json.encodeToString(ModelInfo.serializer(), info))
        return info
    }

    /** Loads the model once to read its shapes; throws when TFLite cannot or the shapes are not a pose model. */
    private fun probe(bytes: ByteArray, name: String): ModelPart {
        val tmp = File(dir, "probe.tflite")
        try {
            tmp.writeBytes(bytes)
            return TflitePoseDetector(tmp).use { it.describe(name) }
        } catch (e: Exception) {
            throw IllegalArgumentException("Not a usable model ($name): ${e.message}")
        } finally { tmp.delete() }
    }

    fun remove() { arrowsFile.delete(); faceFile.delete(); infoFile.delete() }
}

/**
 * Runs a YOLO pose export (one keypoint per arrow, or five per face) on a photo. Input size, layout
 * ([1, N, N, 3] or channels-first [1, 3, N, N]), tensor types and quantisation are read from the model,
 * so the app adapts to whatever the training produced. Coordinates that come out normalised are scaled up.
 */
class TflitePoseDetector(file: File) : AutoCloseable {
    private val interpreter = org.tensorflow.lite.Interpreter(file, org.tensorflow.lite.Interpreter.Options().setNumThreads(4))
    private val input = interpreter.getInputTensor(0)
    private val output = interpreter.getOutputTensor(0)
    val inputSize: Int
    val keypoints: Int
    private val channelsFirst: Boolean
    init {
        val s = input.shape()
        require(s.size == 4 && ((s[3] == 3 && s[1] == s[2]) || (s[1] == 3 && s[2] == s[3]))) { "input shape ${s.toList()} is neither [1, N, N, 3] nor [1, 3, N, N]" }
        channelsFirst = s[1] == 3 && s[3] != 3
        inputSize = if (channelsFirst) s[2] else s[1]
        keypoints = YoloPoseDecoder.keypointsOf(output.shape()) ?: throw IllegalArgumentException("output shape ${output.shape().toList()} is not a pose output")
    }

    fun describe(name: String) = ModelPart(name, inputSize, input.dataType().name, output.shape().toList(), keypoints)

    fun detect(bitmap: Bitmap, confThreshold: Double): List<Detection> {
        val lb = Letterbox.fit(bitmap.width, bitmap.height, inputSize)
        // letterbox onto a grey square
        val square = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        Canvas(square).apply {
            drawColor(Color.rgb(114, 114, 114))
            drawBitmap(bitmap, null, RectF(lb.padX.toFloat(), lb.padY.toFloat(), (lb.padX + bitmap.width * lb.scale).toFloat(), (lb.padY + bitmap.height * lb.scale).toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val pixels = IntArray(inputSize * inputSize)
        square.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        square.recycle()
        val inType = input.dataType()
        val inBuf = ByteBuffer.allocateDirect(inputSize * inputSize * 3 * if (inType == org.tensorflow.lite.DataType.FLOAT32) 4 else 1).order(ByteOrder.nativeOrder())
        val q = input.quantizationParams()
        fun putChannel(c: Int) = when (inType) {
            org.tensorflow.lite.DataType.FLOAT32 -> inBuf.putFloat(c / 255f)
            org.tensorflow.lite.DataType.UINT8 -> inBuf.put(c.toByte())
            else -> {                                                // INT8 with quantisation
                val v = if (q.scale > 0f) ((c / 255f) / q.scale + q.zeroPoint).roundToInt() else c - 128
                inBuf.put(v.coerceIn(-128, 127).toByte())
            }
        }
        if (channelsFirst) {
            // one plane per colour: all red values, then green, then blue
            for (shift in intArrayOf(16, 8, 0)) for (p in pixels) putChannel((p shr shift) and 0xFF)
        } else {
            for (p in pixels) { putChannel((p shr 16) and 0xFF); putChannel((p shr 8) and 0xFF); putChannel(p and 0xFF) }
        }
        inBuf.rewind()
        val outShape = output.shape()
        val count = outShape.fold(1) { a, b -> a * b }
        val outType = output.dataType()
        val outBuf = ByteBuffer.allocateDirect(count * if (outType == org.tensorflow.lite.DataType.FLOAT32) 4 else 1).order(ByteOrder.nativeOrder())
        interpreter.run(inBuf, outBuf)
        outBuf.rewind()
        val values = FloatArray(count)
        val oq = output.quantizationParams()
        for (i in 0 until count) values[i] = when (outType) {
            org.tensorflow.lite.DataType.FLOAT32 -> outBuf.getFloat()
            org.tensorflow.lite.DataType.UINT8 -> ((outBuf.get().toInt() and 0xFF) - oq.zeroPoint) * oq.scale
            else -> (outBuf.get().toInt() - oq.zeroPoint) * oq.scale
        }
        YoloPoseDecoder.denormalise(values, outShape, inputSize)
        return YoloPoseDecoder.decode(values, outShape, lb, confThreshold)
    }

    override fun close() { interpreter.close() }
}
