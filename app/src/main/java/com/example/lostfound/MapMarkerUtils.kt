package com.example.lostfound

import android.graphics.*
import android.util.Base64
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory

/**
 * Utility for building custom map markers that show a circular photo
 * surrounded by a colored ring and a pin tail.
 *
 * Red ring  -> Missing Person
 * Blue ring -> Sighting
 */
object MapMarkerUtils {

    private const val MARKER_WIDTH = 140
    private const val MARKER_HEIGHT = 170
    private const val PHOTO_SIZE = 110
    private const val RING_WIDTH = 8f

    val COLOR_MISSING: Int = Color.parseColor("#E53935")   // Red
    val COLOR_SIGHTING: Int = Color.parseColor("#1E88E5")  // Blue

    fun buildMarker(
        base64Photo: String,
        ringColor: Int
    ): BitmapDescriptor {
        val photoBitmap = decodeSafely(base64Photo)
        val canvasBitmap = Bitmap.createBitmap(
            MARKER_WIDTH, MARKER_HEIGHT, Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(canvasBitmap)

        drawPinTail(canvas, ringColor)
        drawPhotoCircle(canvas, photoBitmap, ringColor)

        return BitmapDescriptorFactory.fromBitmap(canvasBitmap)
    }

    private fun decodeSafely(base64: String): Bitmap? {
        if (base64.isBlank()) return null
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun drawPinTail(canvas: Canvas, color: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        val centerX = MARKER_WIDTH / 2f
        val tailTopY = (PHOTO_SIZE + RING_WIDTH * 2) - 4f
        val tailBottomY = MARKER_HEIGHT.toFloat() - 4f

        val path = Path().apply {
            moveTo(centerX - 18f, tailTopY)
            lineTo(centerX + 18f, tailTopY)
            lineTo(centerX, tailBottomY)
            close()
        }
        canvas.drawPath(path, paint)
    }

    private fun drawPhotoCircle(
        canvas: Canvas,
        photo: Bitmap?,
        ringColor: Int
    ) {
        val centerX = MARKER_WIDTH / 2f
        val centerY = (PHOTO_SIZE / 2f) + RING_WIDTH
        val outerRadius = (PHOTO_SIZE / 2f) + RING_WIDTH
        val innerRadius = PHOTO_SIZE / 2f

        // Outer colored ring
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ringColor
            style = Paint.Style.FILL
        }
        canvas.drawCircle(centerX, centerY, outerRadius, ringPaint)

        // Inner white backdrop
        val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(centerX, centerY, innerRadius, backdropPaint)

        if (photo != null) {
            val scaled = Bitmap.createScaledBitmap(
                photo, PHOTO_SIZE, PHOTO_SIZE, true
            )
            val photoPaint = Paint(Paint.ANTI_ALIAS_FLAG)

            val savedLayer = canvas.saveLayer(
                centerX - innerRadius,
                centerY - innerRadius,
                centerX + innerRadius,
                centerY + innerRadius,
                null
            )
            canvas.drawCircle(centerX, centerY, innerRadius, photoPaint)
            photoPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(
                scaled,
                centerX - innerRadius,
                centerY - innerRadius,
                photoPaint
            )
            photoPaint.xfermode = null
            canvas.restoreToCount(savedLayer)

            if (scaled != photo) scaled.recycle()
        } else {
            val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.LTGRAY
                style = Paint.Style.FILL
            }
            canvas.drawCircle(centerX, centerY, innerRadius - 2f, placeholderPaint)

            val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.DKGRAY
                textSize = 48f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("👤", centerX, centerY + 16f, glyphPaint)
        }
    }
}
