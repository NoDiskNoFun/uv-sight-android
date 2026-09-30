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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToInt

@Serializable
data class ModelInfo(val name: String, val inputSize: Int, val inputType: String, val outputShape: List<Int>, val imported: Long)

/** The one detection model of the app, kept in private storage; null when none was imported. */
class ModelStore(context: Context) {
    private val dir = File(context.filesDir, "model").apply { mkdirs() }
    val modelFile = File(dir, "arrows.tflite")
    private val infoFile = File(dir, "model.json")
    private val json = Json { ignoreUnknownKeys = true }

    fun info(): ModelInfo? = if (modelFile.exists() && infoFile.exists()) runCatching { json.decodeFromString(ModelInfo.serializer(), infoFile.readText()) }.getOrNull() else null

    /** Copies the file in and checks that it looks like the arrow model; throws with a plain message otherwise. */
    fun import(bytes: ByteArray, name: String): ModelInfo {
        val tmp = File(dir, "import.tflite")
        tmp.writeBytes(bytes)
        val info = try { TfliteArrowDetector(tmp).use { it.describe(name) } } catch (e: Exception) { tmp.delete(); throw IllegalArgumentException("Not a usable arrow model: ${e.message}") }
        tmp.renameTo(modelFile)
        infoFile.writeText(json.encodeToString(ModelInfo.serializer(), info))
        return info
    }

    fun remove() { modelFile.delete(); infoFile.delete() }
}

/**
 * Runs the arrow entry-point model (a YOLO pose export with one keypoint) on a photo.
 * Input size, tensor types and quantisation are read from the model, so the app adapts to
 * whatever the training produced. Coordinates that come out normalised (0..1) are scaled up.
 */
class TfliteArrowDetector(file: File) : AutoCloseable {
    private val interpreter = Interpreter(file, Interpreter.Options().setNumThreads(4))
    private val input = interpreter.getInputTensor(0)
    private val output = interpreter.getOutputTensor(0)
    val inputSize: Int
    init {
        val s = input.shape()
        require(s.size == 4 && s[3] == 3 && s[1] == s[2]) { "input shape ${s.toList()} is not [1, N, N, 3]" }
        inputSize = s[1]
        val o = output.shape().filter { it != 1 }
        require(o.size == 2 && (o[0] == YoloPoseDecoder.ROW || o[1] == YoloPoseDecoder.ROW)) { "output shape ${output.shape().toList()} is not a one-keypoint pose output" }
    }

    fun describe(name: String) = ModelInfo(name, inputSize, input.dataType().name, output.shape().toList(), System.currentTimeMillis())

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
        val inBuf = ByteBuffer.allocateDirect(inputSize * inputSize * 3 * if (inType == DataType.FLOAT32) 4 else 1).order(ByteOrder.nativeOrder())
        val q = input.quantizationParams()
        for (p in pixels) {
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            when (inType) {
                DataType.FLOAT32 -> { inBuf.putFloat(r / 255f); inBuf.putFloat(g / 255f); inBuf.putFloat(b / 255f) }
                DataType.UINT8 -> { inBuf.put(r.toByte()); inBuf.put(g.toByte()); inBuf.put(b.toByte()) }
                else -> for (c in intArrayOf(r, g, b)) {            // INT8 with quantisation
                    val v = if (q.scale > 0f) ((c / 255f) / q.scale + q.zeroPoint).roundToInt() else c - 128
                    inBuf.put(v.coerceIn(-128, 127).toByte())
                }
            }
        }
        inBuf.rewind()
        val outShape = output.shape()
        val count = outShape.fold(1) { a, b -> a * b }
        val outType = output.dataType()
        val outBuf = ByteBuffer.allocateDirect(count * if (outType == DataType.FLOAT32) 4 else 1).order(ByteOrder.nativeOrder())
        interpreter.run(inBuf, outBuf)
        outBuf.rewind()
        val values = FloatArray(count)
        val oq = output.quantizationParams()
        for (i in 0 until count) values[i] = when (outType) {
            DataType.FLOAT32 -> outBuf.getFloat()
            DataType.UINT8 -> ((outBuf.get().toInt() and 0xFF) - oq.zeroPoint) * oq.scale
            else -> (outBuf.get().toInt() - oq.zeroPoint) * oq.scale
        }
        // TFLite exports of YOLO give normalised coordinates: scale them to input pixels
        val dims = outShape.filter { it != 1 }
        val transposed = dims[0] == YoloPoseDecoder.ROW
        val n = if (transposed) dims[1] else dims[0]
        var maxCoord = 0f
        for (i in 0 until n) { val kx = if (transposed) values[5 * n + i] else values[i * 8 + 5]; maxCoord = max(maxCoord, kx) }
        if (maxCoord <= 1.5f) {
            for (i in 0 until n) for (k in intArrayOf(0, 1, 2, 3, 5, 6)) {
                val idx = if (transposed) k * n + i else i * 8 + k
                values[idx] *= inputSize
            }
        }
        return YoloPoseDecoder.decode(values, outShape, lb, confThreshold)
    }

    override fun close() { interpreter.close() }
}
