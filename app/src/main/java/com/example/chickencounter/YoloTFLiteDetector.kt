package com.example.chickencounter

import android.content.Context
import android.graphics.*
import android.util.Log
import androidx.core.graphics.scale
import org.tensorflow.lite.Delegate
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Interpreter.Options
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

data class Detection(
    val rect: RectF,
    val confidence: Float
)

class YoloTFLiteDetector private constructor(
    private val interpreter: Interpreter,
    private val delegate: Delegate? = null
) {
    private val inputSize = 640
    private val confidenceThreshold = 0.90f
    private val iouThreshold = 0.40f

    companion object {
        fun create(context: Context, modelFilename: String): YoloTFLiteDetector {
            val afd = context.assets.openFd(modelFilename)
            FileInputStream(afd.fileDescriptor).use { fis ->
                val fc: FileChannel = fis.channel
                val modelBuffer = fc.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.length)

                var nnDelegate: Delegate? = null
                return try {
                    nnDelegate = try {
                        NnApiDelegate()
                    } catch (e: Exception) {
                        Log.w("YOLO_DETECTOR", "NNAPI Delegate tidak tersedia (${e.message}), fallback CPU")
                        null
                    }

                    val options = Options().apply {
                        setNumThreads(4)
                        nnDelegate?.let { addDelegate(it) }
                    }

                    val interpreter = Interpreter(modelBuffer, options)
                    Log.i("YOLO_DETECTOR", "✅ Model YOLO berhasil dimuat")
                    YoloTFLiteDetector(interpreter, nnDelegate)
                } catch (e: Exception) {
                    nnDelegate?.let { (it as? NnApiDelegate)?.close() }
                    throw Exception("Gagal inisialisasi YOLO: ${e.message}", e)
                }
            }
        }
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3)
        buffer.order(ByteOrder.nativeOrder())
        val scaled = bitmap.scale(inputSize, inputSize, true)
        val pixels = IntArray(inputSize * inputSize)
        scaled.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        var idx = 0
        for (i in 0 until inputSize) {
            val v = pixels[idx++]
            buffer.putFloat(((v shr 16 and 0xFF) / 255f))
            buffer.putFloat(((v shr 8 and 0xFF) / 255f))
            buffer.putFloat(((v and 0xFF) / 255f))
        }
        return buffer
    }

    fun detect(bitmapInput: Bitmap): Triple<Int, Float, Bitmap> {
        val inputBuffer = convertBitmapToByteBuffer(bitmapInput)

        // 🔍 deteksi bentuk output secara otomatis
        val outputTensor = interpreter.getOutputTensor(0)
        val outputShape = outputTensor.shape() // contoh [1,5,8400] atau [1,8400,5]
        Log.d("YOLO_DETECTOR", "Output tensor shape: ${outputShape.contentToString()}")

        // Buat output array dinamis sesuai bentuk model
        val outputData = when {
            outputShape.size == 3 -> Array(outputShape[0]) {
                Array(outputShape[1]) { FloatArray(outputShape[2]) }
            }
            else -> throw Exception("Bentuk output tidak dikenali: ${outputShape.contentToString()}")
        }

        interpreter.run(inputBuffer, outputData)

        // Normalisasi bentuk menjadi [5, N] agar mudah diproses
        val detections = mutableListOf<Detection>()
        val (_, numDetections) = if (outputShape[1] < outputShape[2])
            Pair(outputShape[1], outputShape[2]) else Pair(outputShape[2], outputShape[1])

        val wRatio = bitmapInput.width.toFloat() / inputSize
        val hRatio = bitmapInput.height.toFloat() / inputSize

        for (i in 0 until numDetections) {
            val cx = if (outputShape[1] < outputShape[2]) outputData[0][0][i] else outputData[0][i][0]
            val cy = if (outputShape[1] < outputShape[2]) outputData[0][1][i] else outputData[0][i][1]
            val w = if (outputShape[1] < outputShape[2]) outputData[0][2][i] else outputData[0][i][2]
            val h = if (outputShape[1] < outputShape[2]) outputData[0][3][i] else outputData[0][i][3]
            val conf = if (outputShape[1] < outputShape[2]) outputData[0][4][i] else outputData[0][i][4]

            if (conf < confidenceThreshold) continue

            val left = (cx - w / 2f) * wRatio
            val top = (cy - h / 2f) * hRatio
            val right = (cx + w / 2f) * wRatio
            val bottom = (cy + h / 2f) * hRatio
            detections.add(Detection(RectF(left, top, right, bottom), conf))
        }

        val finalDetections = nms(detections)
        val outBitmap = bitmapInput.copy(Bitmap.Config.ARGB_8888, true)
        drawBoxes(outBitmap, finalDetections)

        val avgConf = if (finalDetections.isNotEmpty())
            finalDetections.map { it.confidence }.average().toFloat() * 100 else 0f

        return Triple(finalDetections.size, avgConf, outBitmap)
    }

    private fun nms(boxes: List<Detection>): List<Detection> {
        if (boxes.isEmpty()) return emptyList()
        val sorted = boxes.sortedByDescending { it.confidence }.toMutableList()
        val selected = mutableListOf<Detection>()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            selected.add(best)
            val it = sorted.iterator()
            while (it.hasNext()) {
                val box = it.next()
                if (iou(best.rect, box.rect) > iouThreshold) it.remove()
            }
        }
        return selected
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        val interArea = max(0f, interRight - interLeft) * max(0f, interBottom - interTop)
        val unionArea = a.width() * a.height() + b.width() * b.height() - interArea
        return interArea / (unionArea + 1e-6f)
    }

    private fun drawBoxes(bitmap: Bitmap, boxes: List<Detection>) {
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            color = Color.GREEN
            strokeWidth = 3f
            style = Paint.Style.STROKE
        }
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 28f
            style = Paint.Style.FILL
        }

        for (d in boxes) {
            canvas.drawRect(d.rect, paint)
            canvas.drawText("%.1f%%".format(d.confidence * 100), d.rect.left, d.rect.top - 6, textPaint)
        }
    }

    fun close() {
        try {
            delegate?.let { (it as? NnApiDelegate)?.close() }
            interpreter.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
