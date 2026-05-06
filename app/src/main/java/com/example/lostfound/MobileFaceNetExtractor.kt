package com.example.lostfound

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.min
import kotlin.math.sqrt

class MobileFaceNetExtractor(
    context: Context,
    modelName: String = "MobileFaceNet.tflite"
) {

    private var interpreter: Interpreter

    private var inputBatch = 1
    private var inputHeight = 112
    private var inputWidth = 112
    private var inputChannels = 3

    private var outputBatch = 1
    private var outputSize = 192

    init {
        val options = Interpreter.Options().apply {
            numThreads = 2
        }

        interpreter = Interpreter(loadModelFile(context, modelName), options)

        val inputTensor = interpreter.getInputTensor(0)
        val inputShape = inputTensor.shape()
        val inputType = inputTensor.dataType()

        val outputTensor = interpreter.getOutputTensor(0)
        val outputShape = outputTensor.shape()
        val outputType = outputTensor.dataType()

        Log.d(
            "MobileFaceNet",
            "Input tensor shape=${inputShape.contentToString()}, type=$inputType"
        )
        Log.d(
            "MobileFaceNet",
            "Output tensor shape=${outputShape.contentToString()}, type=$outputType"
        )

        if (inputType != DataType.FLOAT32) {
            throw IllegalStateException(
                "MobileFaceNetExtractor currently expects FLOAT32 input, but model input is $inputType"
            )
        }

        if (inputShape.size != 4) {
            throw IllegalStateException(
                "Unsupported input shape: ${inputShape.contentToString()}. Expected [batch, height, width, channels]."
            )
        }

        inputBatch = inputShape[0]
        inputHeight = inputShape[1]
        inputWidth = inputShape[2]
        inputChannels = inputShape[3]

        if (inputBatch <= 0) inputBatch = 1

        if (inputHeight <= 0 || inputWidth <= 0 || inputChannels <= 0) {
            throw IllegalStateException(
                "Invalid input shape: ${inputShape.contentToString()}"
            )
        }

        if (inputChannels != 3) {
            throw IllegalStateException(
                "Unsupported channel count: $inputChannels. Expected 3 RGB channels."
            )
        }

        if (outputType != DataType.FLOAT32) {
            throw IllegalStateException(
                "MobileFaceNetExtractor currently expects FLOAT32 output, but model output is $outputType"
            )
        }

        if (outputShape.isNotEmpty()) {
            outputBatch = if (outputShape[0] > 0) outputShape[0] else inputBatch
            outputSize = outputShape.last()
        }

        if (outputSize <= 0) {
            throw IllegalStateException(
                "Invalid output shape: ${outputShape.contentToString()}"
            )
        }

        Log.d(
            "MobileFaceNet",
            "Resolved inputBatch=$inputBatch, inputHeight=$inputHeight, inputWidth=$inputWidth, inputChannels=$inputChannels, outputBatch=$outputBatch, outputSize=$outputSize"
        )
    }

    fun extractFeature(faceBitmap: Bitmap): FloatArray {
        if (faceBitmap.isRecycled) {
            throw IllegalStateException("Cannot extract feature from recycled bitmap.")
        }

        val resizedBitmap = if (
            faceBitmap.width != inputWidth ||
            faceBitmap.height != inputHeight ||
            faceBitmap.config != Bitmap.Config.ARGB_8888
        ) {
            Bitmap.createScaledBitmap(faceBitmap, inputWidth, inputHeight, true)
                .copy(Bitmap.Config.ARGB_8888, false)
        } else {
            faceBitmap.copy(Bitmap.Config.ARGB_8888, false)
        }

        val expectedInputBytes = inputBatch * inputHeight * inputWidth * inputChannels * 4

        val byteBuffer = ByteBuffer.allocateDirect(expectedInputBytes)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(inputWidth * inputHeight)
        resizedBitmap.getPixels(
            intValues,
            0,
            inputWidth,
            0,
            0,
            inputWidth,
            inputHeight
        )

        /*
         * Important:
         * Your MobileFaceNet.tflite expects batch=2.
         * But this app extracts one face at a time.
         * Therefore we duplicate the same face into every batch slot,
         * then use output[0] as the final embedding.
         */
        repeat(inputBatch) {
            var pixel = 0

            for (y in 0 until inputHeight) {
                for (x in 0 until inputWidth) {
                    val value = intValues[pixel++]

                    val r = ((value shr 16) and 0xFF).toFloat()
                    val g = ((value shr 8) and 0xFF).toFloat()
                    val b = (value and 0xFF).toFloat()

                    byteBuffer.putFloat((r - 127.5f) / 128.0f)
                    byteBuffer.putFloat((g - 127.5f) / 128.0f)
                    byteBuffer.putFloat((b - 127.5f) / 128.0f)
                }
            }
        }

        byteBuffer.rewind()

        Log.d(
            "MobileFaceNet",
            "Running inference. Bitmap=${resizedBitmap.width}x${resizedBitmap.height}, config=${resizedBitmap.config}, inputBatch=$inputBatch, bufferBytes=${byteBuffer.capacity()}, expectedBytes=$expectedInputBytes"
        )

        val actualOutputBatch = maxOf(outputBatch, inputBatch)
        val output = Array(actualOutputBatch) {
            FloatArray(outputSize)
        }

        try {
            interpreter.run(byteBuffer, output)
        } catch (e: Exception) {
            Log.e(
                "MobileFaceNet",
                "TFLite run failed. " +
                        "Model input batch=$inputBatch, " +
                        "input=${inputWidth}x${inputHeight}x${inputChannels}, " +
                        "bufferBytes=${byteBuffer.capacity()}, " +
                        "outputBatch=$actualOutputBatch, outputSize=$outputSize",
                e
            )
            throw e
        }

        val feature = output[0]

        if (feature.isEmpty()) {
            throw IllegalStateException("MobileFaceNet returned an empty feature vector.")
        }

        Log.d(
            "MobileFaceNet",
            "Embedding extracted successfully. size=${feature.size}"
        )

        return l2Normalize(feature)
    }

    fun calculateSimilarity(v1: FloatArray, v2: FloatArray): Float {
        val size = min(v1.size, v2.size)

        if (size <= 0) return 0f

        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f

        for (i in 0 until size) {
            dotProduct += v1[i] * v2[i]
            normA += v1[i] * v1[i]
            normB += v2[i] * v2[i]
        }

        if (normA == 0.0f || normB == 0.0f) return 0.0f

        return dotProduct / (sqrt(normA) * sqrt(normB))
    }

    fun verifyMatch(v1: FloatArray, v2: FloatArray): Pair<Boolean, Float> {
        val similarity = calculateSimilarity(v1, v2)
        return (similarity >= VERIFICATION_THRESHOLD) to similarity
    }

    fun close() {
        interpreter.close()
    }

    private fun l2Normalize(vector: FloatArray): FloatArray {
        var sum = 0f

        for (value in vector) {
            sum += value * value
        }

        val norm = sqrt(sum)

        if (norm == 0f) return vector

        return FloatArray(vector.size) { index ->
            vector[index] / norm
        }
    }

    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)

        return inputStream.channel.map(
            FileChannel.MapMode.READ_ONLY,
            fileDescriptor.startOffset,
            fileDescriptor.declaredLength
        )
    }

    companion object {
        const val VERIFICATION_THRESHOLD = 0.6202f
    }
}
