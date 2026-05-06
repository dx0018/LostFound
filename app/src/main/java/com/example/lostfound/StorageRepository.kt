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

    /**
     * 上传 Bitmap 到 Storage 并返回 (downloadUrl, storagePath)
     *
     * @param bitmap   原始图
     * @param folder   "missing_persons" 或 "sightings"
     * @param userId   已确认存在的登录用户 UID
     * @param maxDim   最长边像素，默认 800
     * @param quality  JPEG 质量，默认 75
     */
    suspend fun uploadBitmap(
        bitmap: Bitmap,
        folder: String,
        userId: String,
        maxDim: Int = 800,
        quality: Int = 75
    ): Pair<String, String> {
        require(userId.isNotBlank()) { "userId cannot be blank" }
        require(folder.isNotBlank()) { "folder cannot be blank" }

        val ratio = if (maxOf(bitmap.width, bitmap.height) > maxDim) {
            maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
        } else {
            1f
        }

        val targetW = (bitmap.width * ratio).toInt()
        val targetH = (bitmap.height * ratio).toInt()

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

        val fileName = "${UUID.randomUUID()}.jpg"
        val path = "images/$folder/$userId/$fileName"

        val ref = FirebaseStorage.getInstance().reference.child(path)
        ref.putBytes(bytes).await()
        val url = ref.downloadUrl.await().toString()

        return url to path
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
