package com.example.lostfound

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

object ImageUtils {

    data class MatchInfo(
        val boundingBox: RectF,
        val isMatch: Boolean,
        val label: String
    )

    fun bitmapToByteBuffer(bitmap: Bitmap, width: Int, height: Int): ByteBuffer {
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val safeBitmap = resizedBitmap.copy(Bitmap.Config.ARGB_8888, false)

        val byteBuffer = ByteBuffer.allocateDirect(4 * width * height * 3)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(width * height)
        safeBitmap.getPixels(intValues, 0, width, 0, 0, width, height)

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

    fun cropFaceWithPadding(
        originalBitmap: Bitmap,
        boundingBox: RectF,
        paddingRatio: Float = 0.20f
    ): Bitmap? {
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

    fun drawBoundingBoxes(
        originalBitmap: Bitmap,
        matchResults: List<MatchInfo>
    ): Bitmap {
        val mutableBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)

        val strokeW = max(originalBitmap.width / 300f, 4f)
        val txtSize = max(originalBitmap.width / 40f, 30f)

        val boxPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeW
            isAntiAlias = true
        }

        val textPaint = Paint().apply {
            textSize = txtSize
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(5f, 0f, 0f, Color.BLACK)
            isAntiAlias = true
        }

        for (info in matchResults) {
            boxPaint.color = if (info.isMatch) Color.GREEN else Color.RED
            textPaint.color = if (info.isMatch) Color.GREEN else Color.RED

            canvas.drawRect(info.boundingBox, boxPaint)

            val textY = if (info.boundingBox.top - 10f > txtSize) {
                info.boundingBox.top - 10f
            } else {
                info.boundingBox.top + txtSize + 10f
            }

            canvas.drawText(
                info.label,
                info.boundingBox.left,
                textY,
                textPaint
            )
        }

        return mutableBitmap
    }

    fun alignFace(
        bitmap: Bitmap,
        face: Face,
        targetSize: Int = 112
    ): Bitmap {
        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position

        if (leftEye == null || rightEye == null) {
            return Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, true)
                .copy(Bitmap.Config.ARGB_8888, false)
        }

        val eyeMidPoint = PointF(
            (leftEye.x + rightEye.x) / 2f,
            (leftEye.y + rightEye.y) / 2f
        )

        val eyeAngleRad = atan2(
            leftEye.y - rightEye.y,
            leftEye.x - rightEye.x
        )
        val eyeAngleDeg = Math.toDegrees(eyeAngleRad.toDouble()).toFloat()

        val targetEyeDist = targetSize * 0.38f
        val targetLeftEyePos = PointF(
            targetSize * 0.5f - targetEyeDist / 2f,
            targetSize * 0.45f
        )

        val actualEyeDist = hypot(
            rightEye.x - leftEye.x,
            rightEye.y - leftEye.y
        )

        if (actualEyeDist <= 1f) {
            return Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, true)
                .copy(Bitmap.Config.ARGB_8888, false)
        }

        val scale = targetEyeDist / actualEyeDist

        val matrix = android.graphics.Matrix()
        matrix.postScale(scale, scale, eyeMidPoint.x, eyeMidPoint.y)
        matrix.postRotate(-eyeAngleDeg, eyeMidPoint.x, eyeMidPoint.y)

        val transformedRightEye = floatArrayOf(rightEye.x, rightEye.y)
        matrix.mapPoints(transformedRightEye)

        val dx = targetLeftEyePos.x - transformedRightEye[0]
        val dy = targetLeftEyePos.y - transformedRightEye[1]
        matrix.postTranslate(dx, dy)

        val finalBitmap = Bitmap.createBitmap(
            targetSize,
            targetSize,
            Bitmap.Config.ARGB_8888
        )

        val canvas = Canvas(finalBitmap)
        canvas.drawColor(Color.BLACK)

        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            isAntiAlias = true
            isDither = true
        }

        canvas.drawBitmap(bitmap, matrix, paint)

        return if (
            finalBitmap.width == targetSize &&
            finalBitmap.height == targetSize &&
            finalBitmap.config == Bitmap.Config.ARGB_8888
        ) {
            finalBitmap
        } else {
            Bitmap.createScaledBitmap(finalBitmap, targetSize, targetSize, true)
                .copy(Bitmap.Config.ARGB_8888, false)
        }
    }
}
