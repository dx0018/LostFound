package com.example.lostfound

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class YoloFaceDetector(context: Context, modelName: String = "yolov11n-face_float16.tflite") {

    private var interpreter: Interpreter
    private val inputSize = 320

    data class Recognition(val boundingBox: RectF, val confidence: Float)

    init {
        val options = Interpreter.Options().apply { numThreads = 4 }
        interpreter = Interpreter(loadModelFile(context, modelName), options)
    }

    fun detect(bitmap: Bitmap): List<Recognition> {
        val byteBuffer = ImageUtils.bitmapToByteBuffer(bitmap, inputSize, inputSize)
        val output = Array(1) { Array(5) { FloatArray(2100) } }

        interpreter.run(byteBuffer, output)

        val recognitions = mutableListOf<Recognition>()

        val imgWidth = bitmap.width.toFloat()
        val imgHeight = bitmap.height.toFloat()

        for (i in 0 until 2100) {
            val confidence = output[0][4][i]

            if (confidence > 0.5f) {
                val cx = output[0][0][i]
                val cy = output[0][1][i]
                val w = output[0][2][i]
                val h = output[0][3][i]

                // 将百分比转化为实际像素
                val left = (cx - w / 2) * imgWidth
                val top = (cy - h / 2) * imgHeight
                val right = (cx + w / 2) * imgWidth
                val bottom = (cy + h / 2) * imgHeight

                val rect = RectF(left, top, right, bottom)
                recognitions.add(Recognition(rect, confidence))
            }
        }

        return applyNMS(recognitions)
    }

    fun applyNMS(recognitions: List<Recognition>, iouThreshold: Float = 0.4f): List<Recognition> {
        val sortedList = recognitions.sortedByDescending { it.confidence }
        val selected = mutableListOf<Recognition>()

        for (box in sortedList) {
            var shouldSelect = true
            for (selectedBox in selected) {
                if (calculateIoU(box.boundingBox, selectedBox.boundingBox) > iouThreshold) {
                    shouldSelect = false
                    break
                }
            }
            if (shouldSelect) {
                selected.add(box)
            }
        }
        return selected
    }

    fun calculateIoU(box1: RectF, box2: RectF): Float {
        val intersectionLeft = maxOf(box1.left, box2.left)
        val intersectionTop = maxOf(box1.top, box2.top)
        val intersectionRight = minOf(box1.right, box2.right)
        val intersectionBottom = minOf(box1.bottom, box2.bottom)

        val intersectionArea = maxOf(0f, intersectionRight - intersectionLeft) * maxOf(0f, intersectionBottom - intersectionTop)
        val box1Area = (box1.right - box1.left) * (box1.bottom - box1.top)
        val box2Area = (box2.right - box2.left) * (box2.bottom - box2.top)

        val denominator = box1Area + box2Area - intersectionArea
        if (denominator <= 0f) return 0f
        return intersectionArea / denominator
    }

    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        return inputStream.channel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
    }

    fun close() {
        interpreter.close()
    }
}