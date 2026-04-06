package com.example.lostfound

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

object ImageUtils {

    // 数据类：用来记录每个人的匹配信息
    data class MatchInfo(val boundingBox: RectF, val isMatch: Boolean, val label: String)

    fun bitmapToByteBuffer(bitmap: Bitmap, width: Int, height: Int): ByteBuffer {
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val byteBuffer = ByteBuffer.allocateDirect(4 * width * height * 3)
        byteBuffer.order(ByteOrder.nativeOrder())
        val intValues = IntArray(width * height)
        resizedBitmap.getPixels(intValues, 0, width, 0, 0, width, height)
        var pixel = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val value = intValues[pixel++]
                byteBuffer.putFloat(((value shr 16) and 0xFF) / 255.0f)
                byteBuffer.putFloat(((value shr 8) and 0xFF) / 255.0f)
                byteBuffer.putFloat((value and 0xFF) / 255.0f)
            }
        }
        return byteBuffer
    }

    fun cropFaceWithPadding(originalBitmap: Bitmap, boundingBox: RectF, paddingRatio: Float = 0.20f): Bitmap? {
        val width = boundingBox.width()
        val height = boundingBox.height()
        val padW = (width * paddingRatio).toInt()
        val padH = (height * paddingRatio).toInt()
        val left = max(0, (boundingBox.left - padW).toInt())
        val top = max(0, (boundingBox.top - padH).toInt())
        val right = min(originalBitmap.width, (boundingBox.right + padW).toInt())
        val bottom = min(originalBitmap.height, (boundingBox.bottom + padH).toInt())
        val cropWidth = right - left
        val cropHeight = bottom - top
        if (cropWidth <= 0 || cropHeight <= 0) return null
        return Bitmap.createBitmap(originalBitmap, left, top, cropWidth, cropHeight)
    }

    /**
     * 🎨 可视化引擎：在原图上画出带有百分比的红绿框
     */
    fun drawBoundingBoxes(originalBitmap: Bitmap, matchResults: List<MatchInfo>): Bitmap {
        // 复制一张可修改的图片
        val mutableBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)

        // 动态计算字体和线条粗细 (适配不同分辨率的高清图)
        val strokeW = max(originalBitmap.width / 300f, 4f)
        val txtSize = max(originalBitmap.width / 40f, 30f)

        val boxPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeW
        }

        val textPaint = Paint().apply {
            textSize = txtSize
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(5f, 0f, 0f, Color.BLACK) // 黑色阴影让白字在亮色背景也能看清
        }

        for (info in matchResults) {
            if (info.isMatch) {
                boxPaint.color = Color.GREEN
                textPaint.color = Color.GREEN
            } else {
                boxPaint.color = Color.RED
                textPaint.color = Color.RED
            }

            // 画矩形框
            canvas.drawRect(info.boundingBox, boxPaint)
            // 在框的左上角写文字
            canvas.drawText(info.label, info.boundingBox.left, info.boundingBox.top - 10f, textPaint)
        }

        return mutableBitmap
    }
}