package com.example.lostfound

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.core.graphics.applyCanvas
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory

object MapMarkerUtils {

    val COLOR_MISSING  = Color.parseColor("#E53935")
    val COLOR_SIGHTING = Color.parseColor("#1E88E5")

    private const val MARKER_SIZE  = 200
    private const val PHOTO_SIZE   = 160
    private const val BORDER_WIDTH = 5f
    private const val TAIL_HEIGHT  = 22
    private const val DOT_SIZE     = 44

    /**
     * 🆕 从 URL 异步下载图片为 Bitmap（用 Coil 复用磁盘缓存）
     */
    suspend fun downloadBitmapFromUrl(context: Context, url: String): Bitmap? {
        if (url.isBlank()) return null
        return try {
            val loader = ImageLoader(context)
            val request = ImageRequest.Builder(context)
                .data(url)
                .allowHardware(false) // BitmapShader 不支持 HARDWARE bitmap，必须关掉
                .build()
            val result = loader.execute(request)
            if (result is SuccessResult) {
                (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 🆕 从 Bitmap 直接生成头像 marker（不再走 Base64 解码）
     */
    fun buildMarkerFromBitmap(avatar: Bitmap?, ringColor: Int): BitmapDescriptor {
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

            // 2. 外圈
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

            // 5. 头像
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
                val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(40, 0, 0, 0)
                }
                drawCircle(cx, cy, PHOTO_SIZE / 2f, placeholderPaint)
            }
        }

        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    /**
     * 纯色圆点 marker —— 低 zoom 级别使用
     */
    fun buildDotMarker(color: Int): BitmapDescriptor {
        val size = DOT_SIZE
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap.applyCanvas {
            val cx = size / 2f
            val cy = size / 2f

            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.argb(80, 0, 0, 0)
                maskFilter = BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL)
            }
            drawCircle(cx, cy + 2f, size / 2f - 4f, shadowPaint)

            val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.WHITE
            }
            drawCircle(cx, cy, size / 2f - 3f, whitePaint)

            val colorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
            }
            drawCircle(cx, cy, size / 2f - 7f, colorPaint)
        }
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

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
