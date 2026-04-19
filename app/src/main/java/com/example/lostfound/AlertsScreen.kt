package com.example.lostfound

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

@Composable
fun AlertsScreen(onNavigateToTimeline: (String) -> Unit) {
    val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: return
    var selectedTabIndex by remember { mutableStateOf(0) }
    val tabs = listOf("Notifications", "My Records")

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Alerts Hub", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        TabRow(selectedTabIndex = selectedTabIndex, containerColor = MaterialTheme.colorScheme.surface) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = { Text(title, fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Normal) }
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            if (selectedTabIndex == 0) NotificationsTab(currentUserId) else MyRecordsTab(currentUserId, onNavigateToTimeline)
        }
    }
}

@Composable
fun NotificationsTab(currentUserId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var notifications by remember { mutableStateOf<List<NotificationRecord>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    var selectedNote by remember { mutableStateOf<NotificationRecord?>(null) }

    DisposableEffect(Unit) {
        val listener = FirebaseFirestore.getInstance().collection("Notifications")
            .whereEqualTo("receiverId", currentUserId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    isLoading = false
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val fetchedList = snapshot.toObjects(NotificationRecord::class.java)
                    notifications = fetchedList.sortedByDescending { it.timestamp }
                }
                isLoading = false
            }
        onDispose { listener.remove() }
    }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else if (notifications.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No new alerts.", color = Color.Gray) }
    } else {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(notifications) { note ->
                NotificationCard(note) {
                    selectedNote = note
                    if (!note.isRead) {
                        FirebaseFirestore.getInstance().collection("Notifications").document(note.id).update("isRead", true)
                    }
                }
            }
        }
    }

    selectedNote?.let { note ->
        when (note.type) {
            "SIGHTING_MATCH" -> {
                AlertDialog(
                    onDismissRequest = { selectedNote = null },
                    title = { Text("Verify Sighting") },
                    text = { Text("Someone reported a possible match. Is this the person you are looking for?") },
                    confirmButton = {
                        Button(onClick = {
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val db = FirebaseFirestore.getInstance()
                                    db.collection("Sightings").document(note.relatedSightingId).update("status", SightingStatus.LINKED.name)
                                    
                                    if (note.relatedMissingPersonId.isNotEmpty()) {
                                        db.collection("MissingPersons").document(note.relatedMissingPersonId).update(
                                            "status", MPStatus.PENDING_VERIFICATION.name,
                                            "linkedSightingIds", FieldValue.arrayUnion(note.relatedSightingId)
                                        )
                                    }

                                    val thanksRef = db.collection("Notifications").document()
                                    thanksRef.set(NotificationRecord(
                                        id = thanksRef.id, receiverId = note.senderId, senderId = currentUserId,
                                        title = "🙏 Thank You!", message = "The family has confirmed your sighting. Thank you for your help!",
                                        type = "THANK_YOU"
                                    ))
                                    db.collection("Notifications").document(note.id).update("type", "MATCH_CONFIRMED")

                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "Sighting Confirmed!", Toast.LENGTH_SHORT).show()
                                        selectedNote = null
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) { Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show() }
                                }
                            }
                        }) { Text("Yes") }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = {
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val db = FirebaseFirestore.getInstance()
                                    db.collection("Sightings").document(note.relatedSightingId).update("status", SightingStatus.REJECTED.name)
                                    
                                    if (note.relatedMissingPersonId.isNotEmpty()) {
                                        val mpRef = db.collection("MissingPersons").document(note.relatedMissingPersonId)
                                        db.runTransaction { transaction ->
                                            val mp = transaction.get(mpRef).toObject(MissingPerson::class.java)
                                            if (mp != null) {
                                                val newList = mp.linkedSightingIds.filter { it != note.relatedSightingId }
                                                transaction.update(mpRef, "linkedSightingIds", newList)
                                                if (newList.isEmpty()) {
                                                    transaction.update(mpRef, "status", MPStatus.ACTIVE.name)
                                                }
                                            }
                                        }.await()
                                    }
                                    db.collection("Notifications").document(note.id).update("type", "MATCH_REJECTED")
                                    withContext(Dispatchers.Main) { selectedNote = null }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) { Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show() }
                                }
                            }
                        }) { Text("No") }
                    }
                )
            }
            "MATCH_CONFIRMED" -> {
                AlertDialog(
                    onDismissRequest = { selectedNote = null },
                    title = { Text("Verified ✅") },
                    text = { Text("This sighting has been confirmed.") },
                    confirmButton = { Button(onClick = { selectedNote = null }) { Text("OK") } }
                )
            }
            "MATCH_REJECTED" -> {
                AlertDialog(
                    onDismissRequest = { selectedNote = null },
                    title = { Text("Rejected ❌") },
                    text = { Text("You marked this sighting as incorrect.") },
                    confirmButton = { Button(onClick = { selectedNote = null }) { Text("OK") } }
                )
            }
            else -> {
                AlertDialog(
                    onDismissRequest = { selectedNote = null },
                    title = { Text(note.title) },
                    text = { Text(note.message) },
                    confirmButton = { Button(onClick = { selectedNote = null }) { Text("Close") } }
                )
            }
        }
    }
}

@Composable
fun MyRecordsTab(currentUserId: String, onNavigateToTimeline: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var myPersons by remember { mutableStateOf<List<MissingPerson>>(emptyList()) }
    var mySightings by remember { mutableStateOf<List<SightingRecord>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    var selectedMP by remember { mutableStateOf<MissingPerson?>(null) }
    var selectedSighting by remember { mutableStateOf<SightingRecord?>(null) }

    LaunchedEffect(Unit) {
        val db = FirebaseFirestore.getInstance()
        db.collection("MissingPersons").whereEqualTo("ownerId", currentUserId)
            .addSnapshotListener { snap, _ -> if (snap != null) myPersons = snap.toObjects(MissingPerson::class.java) }
        db.collection("Sightings").whereEqualTo("ownerId", currentUserId)
            .addSnapshotListener { snap, _ -> if (snap != null) mySightings = snap.toObjects(SightingRecord::class.java); isLoading = false }
    }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("📢 My Missing Person Reports", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
            if (myPersons.isEmpty()) item { Text("None reported.", style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
            items(myPersons) { person ->
                MyRecordCard(person.name, person.status, person.photoBase64) { selectedMP = person }
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
            item { Text("🔍 My Sighting Contributions", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary) }
            if (mySightings.isEmpty()) item { Text("No clues submitted yet.", style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
            items(mySightings) { sighting ->
                MyRecordCard("Sighting @ ${sighting.location}", sighting.status, sighting.photoBase64) { selectedSighting = sighting }
            }
        }
    }

    selectedMP?.let { mp ->
        AlertDialog(
            onDismissRequest = { selectedMP = null },
            title = { Text("Manage Case: ${mp.name}") },
            text = { 
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("Current Status: ${mp.status}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Button(
                        onClick = {
                            selectedMP = null
                            onNavigateToTimeline(mp.id)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Icon(Icons.Default.LocationOn, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("View Case Tracker")
                    }

                    if (mp.status != MPStatus.FOUND.name && mp.status != MPStatus.CANCELLED.name) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val db = FirebaseFirestore.getInstance()
                                        val batch = db.batch()
                                        batch.update(db.collection("MissingPersons").document(mp.id), "status", MPStatus.FOUND.name)
                                        mp.linkedSightingIds.forEach { sightingId ->
                                            batch.update(db.collection("Sightings").document(sightingId), "status", SightingStatus.RESOLVED.name)
                                        }
                                        batch.commit().await()
                                        withContext(Dispatchers.Main) { selectedMP = null }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) { Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show() }
                                    }
                                }
                            }, 
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                        ) {
                            Icon(Icons.Default.Check, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Mark as FOUND ✅")
                        }
                    }

                    if (mp.status == MPStatus.ACTIVE.name) {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = {
                                FirebaseFirestore.getInstance().collection("MissingPersons").document(mp.id).update("status", MPStatus.CANCELLED.name)
                                selectedMP = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red)
                        ) {
                            Text("Cancel Report ⚪")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedMP = null }) { Text("Close") }
            }
        )
    }

    selectedSighting?.let { sighting ->
        AlertDialog(
            onDismissRequest = { selectedSighting = null },
            title = { Text("Sighting Detail") },
            text = {
                Text("Status: ${sighting.status}\n\n" +
                        if (sighting.status == SightingStatus.LINKED.name) "Thank you! The family has confirmed your clue."
                        else "Waiting for family verification.")
            },
            confirmButton = {
                Button(onClick = { selectedSighting = null }) { Text("Close") }
            },
            dismissButton = {
                if (sighting.status == SightingStatus.PENDING.name) {
                    OutlinedButton(onClick = {
                        FirebaseFirestore.getInstance().collection("Sightings").document(sighting.id).update("status", SightingStatus.REJECTED.name)
                        selectedSighting = null
                    }) { Text("Withdraw Clue ❌") }
                }
            }
        )
    }
}

@Composable
fun NotificationCard(note: NotificationRecord, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = if (note.isRead) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val bitmap = remember(note.photoBase64) { decodeBase64ToBitmapInternal(note.photoBase64) }
            if (bitmap != null) Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(60.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
            else Box(modifier = Modifier.size(60.dp).clip(RoundedCornerShape(8.dp)).background(Color.LightGray))

            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(note.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(4.dp))
                Text(note.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!note.isRead) Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(Color.Red))
        }
    }
}

@Composable
fun MyRecordCard(title: String, status: String, base64Image: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onClick() }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val bitmap = remember(base64Image) { decodeBase64ToBitmapInternal(base64Image) }
            if (bitmap != null) Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(50.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
            else Box(modifier = Modifier.size(50.dp).clip(RoundedCornerShape(8.dp)).background(Color.LightGray))

            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(title, fontWeight = FontWeight.Bold)
                Text("Status: $status", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private fun decodeBase64ToBitmapInternal(base64Str: String): Bitmap? {
    if (base64Str.isBlank()) return null
    return try {
        val decodedBytes = Base64.decode(base64Str, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
    } catch (e: Exception) {
        null
    }
}
