package com.example.lostfound

import android.graphics.Bitmap
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * 统一封装 Firebase Storage 上传/删除逻辑
 */
object StorageRepository {

    data class UploadResult(
        val photoUrl: String,
        val photoStoragePath: String,
        val thumbnailUrl: String,
        val thumbnailStoragePath: String
    )

    data class UploadResultWithFace(
        val photoUrl: String,
        val photoStoragePath: String,
        val thumbnailUrl: String,
        val thumbnailStoragePath: String,
        val matchedFaceUrl: String,
        val matchedFaceStoragePath: String
    )

    /**
     * 上传原图与缩略图到 Storage 并返回 UploadResult
     */
    suspend fun uploadBitmapWithThumbnail(
        bitmap: Bitmap,
        folder: String,
        userId: String,
        maxDim: Int = 800, // 保留原大图尺寸 (800px)
        thumbnailMaxDim: Int = 300, // 列表轻量缩略图尺寸 (300px)
        quality: Int = 85,
        thumbnailQuality: Int = 70
    ): UploadResult {
        val (photoUrl, photoPath) = uploadSingleBitmap(bitmap, folder, userId, "original_", maxDim, quality)
        val (thumbUrl, thumbPath) = uploadSingleBitmap(bitmap, folder, userId, "thumb_", thumbnailMaxDim, thumbnailQuality)
        return UploadResult(photoUrl, photoPath, thumbUrl, thumbPath)
    }

    /**
     * 🆕 上传目击原图、目击缩略图以及裁剪出的人脸图，并返回 UploadResultWithFace
     */
    suspend fun uploadBitmapWithFace(
        sightingBitmap: Bitmap,
        faceBitmap: Bitmap?,
        folder: String,
        userId: String,
        maxDim: Int = 800,
        thumbnailMaxDim: Int = 300,
        faceMaxDim: Int = 300,
        quality: Int = 85,
        thumbnailQuality: Int = 70,
        faceQuality: Int = 80
    ): UploadResultWithFace {
        val (photoUrl, photoPath) = uploadSingleBitmap(sightingBitmap, folder, userId, "original_", maxDim, quality)
        val (thumbUrl, thumbPath) = uploadSingleBitmap(sightingBitmap, folder, userId, "thumb_", thumbnailMaxDim, thumbnailQuality)
        val (faceUrl, facePath) = if (faceBitmap != null) {
            uploadSingleBitmap(faceBitmap, folder, userId, "face_", faceMaxDim, faceQuality)
        } else {
            "" to ""
        }
        return UploadResultWithFace(photoUrl, photoPath, thumbUrl, thumbPath, faceUrl, facePath)
    }

    private suspend fun uploadSingleBitmap(
        bitmap: Bitmap,
        folder: String,
        userId: String,
        prefix: String,
        maxDim: Int,
        quality: Int
    ): Pair<String, String> {
        require(userId.isNotBlank()) { "userId cannot be blank" }
        require(folder.isNotBlank()) { "folder cannot be blank" }

        val ratio = if (maxOf(bitmap.width, bitmap.height) > maxDim) {
            maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
        } else {
            1f
        }

        val targetW = maxOf(1, (bitmap.width * ratio).toInt())
        val targetH = maxOf(1, (bitmap.height * ratio).toInt())

        val scaled = if (ratio < 1f) {
            Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        } else {
            bitmap
        }

        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        val bytes = baos.toByteArray()

        if (scaled !== bitmap) {
            scaled.recycle()
        }

        val fileName = "$prefix${UUID.randomUUID()}.jpg"
        val path = "images/$folder/$userId/$fileName"

        val ref = FirebaseStorage.getInstance().reference.child(path)
        ref.putBytes(bytes).await()
        val url = ref.downloadUrl.await().toString()

        return url to path
    }

    /**
     * 上传 Bitmap 到 Storage 并返回 (downloadUrl, storagePath) —— 保留用作向后兼容
     */
    suspend fun uploadBitmap(
        bitmap: Bitmap,
        folder: String,
        userId: String,
        maxDim: Int = 800,
        quality: Int = 75
    ): Pair<String, String> {
        return uploadSingleBitmap(bitmap, folder, userId, "", maxDim, quality)
    }

    /**
     * 删除 Storage 上的图片（用于失败回滚或清理）
     */
    suspend fun deleteByPath(storagePath: String) {
        if (storagePath.isBlank()) return

        try {
            FirebaseStorage.getInstance().reference.child(storagePath).delete().await()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
