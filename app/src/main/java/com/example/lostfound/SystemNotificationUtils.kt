package com.example.lostfound

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

object SystemNotificationUtils {
    private const val CHANNEL_ID = "lost_found_alerts"
    private var listenerRegistration: ListenerRegistration? = null
    private var isListening = false

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Lost & Found Alerts"
            val descriptionText = "Notifications for missing persons matches and updates"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun startListeningForNotifications(context: Context) {
        if (isListening) return

        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val db = FirebaseFirestore.getInstance()

        // 监听 Firestore 中发给我的、且状态为未读的通知
        listenerRegistration = db.collection("Notifications")
            .whereEqualTo("receiverId", currentUserId)
            .whereEqualTo("isRead", false)
            .addSnapshotListener { snapshots, error ->
                if (error != null || snapshots == null) return@addSnapshotListener

                // 只有当有 "新添加" 的通知时才弹窗，防止重复弹窗
                for (dc in snapshots.documentChanges) {
                    if (dc.type == DocumentChange.Type.ADDED) {
                        val note = dc.document.toObject(NotificationRecord::class.java)
                        showSystemNotification(context, note)
                    }
                }
            }
        isListening = true
    }

    fun stopListening() {
        listenerRegistration?.remove()
        isListening = false
    }

    private fun showSystemNotification(context: Context, note: NotificationRecord) {
        // Android 13+ 运行时权限检查
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }

        // 点击通知后打开 App 的 MainActivity
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            context,
            0, // Request code
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_round) // 使用 App 自带圆角图标
            .setContentTitle(note.title)
            .setContentText(note.message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true) // 点击后自动消除状态栏通知

        with(NotificationManagerCompat.from(context)) {
            // 利用文档 ID 生成唯一的哈希值作为通知 ID
            notify(note.id.hashCode(), builder.build())
        }
    }
}
