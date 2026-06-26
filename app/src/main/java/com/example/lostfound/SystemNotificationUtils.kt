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
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

object SystemNotificationUtils {
    private const val CHANNEL_ID = "lost_found_alerts"
    private var listenerRegistration: ListenerRegistration? = null
    private var isListening = false
    private var listeningUserId: String? = null

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Lost & Found Alerts"
            val descriptionText = "Notifications for missing persons matches and updates"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun startListeningForNotifications(context: Context, currentUserId: String) {
        if (currentUserId.isBlank()) return

        if (isListening && listeningUserId == currentUserId) return

        listenerRegistration?.remove()
        listenerRegistration = null
        isListening = false
        listeningUserId = currentUserId

        val db = FirebaseFirestore.getInstance("lostfound")

        listenerRegistration = db.collection("Notifications")
            .whereEqualTo("receiverId", currentUserId)
            .whereEqualTo("isRead", false)
            .addSnapshotListener { snapshots, error ->
                if (error != null || snapshots == null) return@addSnapshotListener

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
        listenerRegistration = null
        isListening = false
        listeningUserId = null
    }

    private fun showSystemNotification(context: Context, note: NotificationRecord) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle(note.title)
            .setContentText(note.message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        with(NotificationManagerCompat.from(context)) {
            notify(note.id.hashCode(), builder.build())
        }
    }
}
