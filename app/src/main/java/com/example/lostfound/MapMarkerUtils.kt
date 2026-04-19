package com.example.lostfound

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.Base64
import androidx.core.graphics.applyCanvas
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory

object MapMarkerUtils {

    // ---- Colors ----
    val COLOR_MISSING  = Color.parseColor("#E53935")
    val COLOR_SIGHTING = Color.parseColor("#1E88E5")

    // ---- Sizes (in pixels) ----
    private const val MARKER_SIZE  = 200      // 头像 marker 整体宽度
    private const val PHOTO_SIZE   = 160      // 圆形头像直径
    private const val BORDER_WIDTH = 5f       // 外圈粗细
    private const val TAIL_HEIGHT  = 22       // 底部尖角高度
    private const val DOT_SIZE     = 44       // 纯色圆点 marker 尺寸

    /**
     * 带头像的 marker —— 高 zoom 级别使用。
     * 使用 center-crop,人脸不会被压缩变形。
     */
    fun buildMarker(photoBase64: String, ringColor: Int): BitmapDescriptor {
        val totalW = MARKER_SIZE
        val totalH = MARKER_SIZE + TAIL_HEIGHT

        val bitmap = Bitmap.createBitmap(totalW, totalH, Bitmap.Config.ARGB_8888)
        bitmap.applyCanvas {
            val cx = totalW / 2f
            val cy = MARKER_SIZE / 2f
            val ringRadius = MARKER_SIZE / 2f - BORDER_WIDTH / 2f

            // 1. 阴影
            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(60, 0, 0, 0)
                maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL)
            }
            drawCircle(cx, cy + 4f, ringRadius, shadowPaint)

            // 2. 外圈(颜色环)
            val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ringColor
                style = Paint.Style.FILL
            }
            drawCircle(cx, cy, ringRadius, ringPaint)

            // 3. 底部尖角
            val tailPath = Path().apply {
                moveTo(cx - 16f, cy + ringRadius - 4f)
                lineTo(cx + 16f, cy + ringRadius - 4f)
                lineTo(cx, cy + ringRadius + TAIL_HEIGHT - 2f)
                close()
            }
            drawPath(tailPath, ringPaint)

            // 4. 白色内圈
            val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }
            drawCircle(cx, cy, PHOTO_SIZE / 2f + 2f, whitePaint)

            // 5. 头像(center-crop 到圆形)
            val avatar = decodePhoto(photoBase64)
            if (avatar != null) {
                val cropped = centerCropToSquare(avatar, PHOTO_SIZE)
                val avatarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = BitmapShader(
                        cropped,
                        Shader.TileMode.CLAMP,
                        Shader.TileMode.CLAMP
                    ).apply {
                        val matrix = Matrix().apply {
                            setTranslate(cx - PHOTO_SIZE / 2f, cy - PHOTO_SIZE / 2f)
                        }
                        setLocalMatrix(matrix)
                    }
                }
                drawCircle(cx, cy, PHOTO_SIZE / 2f, avatarPaint)
            } else {
                // 无照片时的占位
                val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(40, 0, 0, 0)
                }
                drawCircle(cx, cy, PHOTO_SIZE / 2f, placeholderPaint)
            }
        }

        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    /**
     * 纯色圆点 marker —— 低 zoom 级别使用。
     */
    fun buildDotMarker(color: Int): BitmapDescriptor {
        val size = DOT_SIZE
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap.applyCanvas {
            val cx = size / 2f
            val cy = size / 2f

            // 阴影
            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.argb(80, 0, 0, 0)
                maskFilter = BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL)
            }
            drawCircle(cx, cy + 2f, size / 2f - 4f, shadowPaint)

            // 白色外圈
            val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.WHITE
            }
            drawCircle(cx, cy, size / 2f - 3f, whitePaint)

            // 内部彩色
            val colorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
            }
            drawCircle(cx, cy, size / 2f - 7f, colorPaint)
        }
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    // -------- Helpers --------

    private fun decodePhoto(base64Str: String): Bitmap? {
        if (base64Str.isBlank()) return null
        return try {
            val bytes = Base64.decode(base64Str, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Center-crop:以较短边裁出正方形,再缩放到目标尺寸。
     * 确保人脸比例正确,不被拉伸。
     */
    private fun centerCropToSquare(src: Bitmap, targetSize: Int): Bitmap {
        val shortSide = minOf(src.width, src.height)
        val x = (src.width  - shortSide) / 2
        val y = (src.height - shortSide) / 2
        val square = Bitmap.createBitmap(src, x, y, shortSide, shortSide)
        return if (square.width != targetSize)
            Bitmap.createScaledBitmap(square, targetSize, targetSize, true)
        else square
    }
}
