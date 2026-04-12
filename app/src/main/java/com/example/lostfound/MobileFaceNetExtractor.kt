package com.example.lostfound

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

class MobileFaceNetExtractor(context: Context, modelName: String = "MobileFaceNet.tflite") {

    private var interpreter: Interpreter
    private val inputSize = 112
    private val outputSize = 192 // 从之前的 Logcat 确认的输出维度

    init {
        // 提取器不需要太多的线程，2 个足够
        val options = Interpreter.Options().apply { numThreads = 2 }
        interpreter = Interpreter(loadModelFile(context, modelName), options)
    }

    /**
     * 将抠出来的人脸图片转换为 192 维特征向量 (Embedding)
     */
    fun extractFeature(faceBitmap: Bitmap): FloatArray {
        // 1. 缩放图片到 112x112
        val resizedBitmap = Bitmap.createScaledBitmap(faceBitmap, inputSize, inputSize, true)

        // 2. ⚠️ Edge Case 处理: 分配双倍内存 (Batch Size = 2)
        // 2(张) * 112 * 112 * 3(RGB通道) * 4(Float字节数)
        val byteBuffer = ByteBuffer.allocateDirect(2 * inputSize * inputSize * 3 * 4)
        byteBuffer.order(ByteOrder.nativeOrder())

        // 3. 提取真实图片的像素并写入内存的“前半部分”
        val intValues = IntArray(inputSize * inputSize)
        resizedBitmap.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)

        // 替换 MobileFaceNetExtractor.kt 里的 for 循环部分
        var pixel = 0
        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val value = intValues[pixel++]
                val r = ((value shr 16) and 0xFF).toFloat()
                val g = ((value shr 8) and 0xFF).toFloat()
                val b = (value and 0xFF).toFloat()

                // 💡 核心修复：MobileFaceNet 需要 (像素值 - 127.5) / 128.0 的标准化格式
                // 这会把 0~255 的颜色转换到 -1.0 到 1.0 之间
                byteBuffer.putFloat((r - 127.5f) / 128.0f)
                byteBuffer.putFloat((g - 127.5f) / 128.0f)
                byteBuffer.putFloat((b - 127.5f) / 128.0f)
            }
        }
        // 内存的“后半部分”会自动保持为默认值 0.0f，充当第二张假图片

        // 4. 准备输出容器: 存放 2 个 192 维的数组
        val output = Array(2) { FloatArray(outputSize) }

        // 5. 运行 AI 推理
        interpreter.run(byteBuffer, output)

        // 6. 只要第一张真实图片的特征
        return output[0]
    }

    /**
     * 数学算法：计算两个特征向量的余弦相似度 (Cosine Similarity)
     * 返回值在 -1.0 到 1.0 之间，越接近 1.0 表示越像同一个人
     */
    fun calculateSimilarity(v1: FloatArray, v2: FloatArray): Float {
        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f
        for (i in v1.indices) {
            dotProduct += v1[i] * v2[i]
            normA += v1[i] * v1[i]
            normB += v2[i] * v2[i]
        }
        if (normA == 0.0f || normB == 0.0f) return 0.0f
        return (dotProduct / (sqrt(normA) * sqrt(normB)))
    }

    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        return inputStream.channel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
    }

    /**
     * 工业级置信度映射逻辑 (Confidence Calibration)
     * 将 ArcFace 压抑的余弦相似度转换为人类易读的百分比置信度
     */
    /**
     * 极简置信度映射 (Linear Normalization)
     * 将机器的余弦相似度，平滑翻译为人类的百分比直觉
     */
    fun calculateConfidenceScore(cosineSimilarity: Float): Float {
        // 过滤极其异常的负数结果
        if (cosineSimilarity < 0f) return 0.05f

        // 🚨 设定单一严格及格线
        // 在 MobileFaceNet 中，0.50 已经是一个非常高的要求了
        val threshold = 0.50f

        return if (cosineSimilarity >= threshold) {
            // 及格区间：把 0.50 ~ 1.0 的机器分数，均匀拉伸到 0.80 ~ 0.99 (80% 到 99%)
            // 这样一来，只要是同一个人，分数看起来就会非常高（90%左右），解决“分数太低”的问题
            0.80f + ((cosineSimilarity - threshold) / (1.0f - threshold)) * 0.19f
        } else {
            // 不及格区间：把 0.0 ~ 0.50 的分数，均匀压缩到 0.0 ~ 0.79 (低于 80%)
            // 解决“乱认人”的问题，路人脸绝对无法越过 80% 的门槛
            (cosineSimilarity / threshold) * 0.79f
        }
    }

    fun close() {
        interpreter.close()
    }
}