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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
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
import com.google.firebase.firestore.FirebaseFirestore

// 💡 架构设计：使用密封类统一包装两张不同的数据库表，方便混合排序
sealed class FeedItem {
    abstract val timestamp: Long
    data class PersonItem(val person: MissingPerson) : FeedItem() {
        override val timestamp = person.timestamp
    }
    data class SightingItem(val sighting: SightingRecord) : FeedItem() {
        override val timestamp = sighting.timestamp
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToProfile: () -> Unit = {},
    onNavigateToTimeline: (String) -> Unit // 💡 接收跳转指令
) {
    var personList by remember { mutableStateOf<List<MissingPerson>>(emptyList()) }
    var sightingList by remember { mutableStateOf<List<SightingRecord>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        val db = FirebaseFirestore.getInstance()

        // 1. 监听未结案的 Missing Persons (ACTIVE 或 PENDING_VERIFICATION)
        val personListener = db.collection("MissingPersons")
            .whereIn("status", listOf(MPStatus.ACTIVE.name, MPStatus.PENDING_VERIFICATION.name))
            .addSnapshotListener { snapshot, error ->
                if (error != null) { errorMessage = error.message.toString(); return@addSnapshotListener }
                if (snapshot != null) personList = snapshot.toObjects(MissingPerson::class.java)
                isLoading = false
            }

        // 2. 监听尚未被绑定的 Orphan Sightings (PENDING)
        val sightingListener = db.collection("Sightings")
            .whereEqualTo("status", SightingStatus.PENDING.name)
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                if (snapshot != null) sightingList = snapshot.toObjects(SightingRecord::class.java)
            }

        onDispose {
            personListener.remove()
            sightingListener.remove()
        }
    }

    // 💡 核心逻辑：合并两个列表，并在本地按时间戳倒序排列
    val combinedFeed = remember(personList, sightingList) {
        val persons = personList.map { FeedItem.PersonItem(it) }
        val sightings = sightingList.map { FeedItem.SightingItem(it) }
        (persons + sightings).sortedByDescending { it.timestamp }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, start = 16.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                onClick = onNavigateToProfile,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(50.dp)
            ) {
                Icon(Icons.Default.Person, contentDescription = "Profile", modifier = Modifier.padding(12.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(text = "Nearby Cases", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        Divider(color = MaterialTheme.colorScheme.surfaceVariant, thickness = 1.dp)

        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Spacer(modifier = Modifier.height(16.dp))

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (errorMessage.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(errorMessage, color = MaterialTheme.colorScheme.error) }
            } else if (combinedFeed.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No active cases or sightings.", color = Color.Gray) }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 80.dp)) {
                    items(combinedFeed) { item ->
                        when (item) {
                            // 渲染案件卡片
                            is FeedItem.PersonItem -> {
                                MissingPersonCard(
                                    person = item.person,
                                    onClick = { onNavigateToTimeline(item.person.id) } // 💡 触发跳转
                                )
                            }
                            // 渲染孤立线索卡片
                            is FeedItem.SightingItem -> {
                                SightingCard(sighting = item.sighting)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MissingPersonCard(person: MissingPerson, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }, // 💡 使卡片可点击
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val bitmap = remember(person.photoBase64) { decodeBase64ToBitmap(person.photoBase64) }

            if (bitmap != null) {
                Image(bitmap = bitmap.asImageBitmap(), contentDescription = "Photo of ${person.name}", modifier = Modifier.size(90.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
            } else {
                Box(modifier = Modifier.size(90.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Person, contentDescription = null, tint = Color.Gray)
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(person.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = person.status.uppercase(),
                        color = if (person.status == "ACTIVE") Color.White else MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.background(color = if (person.status == "ACTIVE") Color(0xFFE53935) else MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text("Age: ${person.age} | ${person.gender}", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📍", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "Last seen: ${person.lastSeenLocation}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun SightingCard(sighting: SightingRecord) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().clickable {
            // 💡 孤立线索无法查看 Timeline，点击时给予提示
            Toast.makeText(context, "This is an orphan clue awaiting AI family match.", Toast.LENGTH_SHORT).show()
        },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant) // 颜色区分
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val bitmap = remember(sighting.photoBase64) { decodeBase64ToBitmap(sighting.photoBase64) }

            if (bitmap != null) {
                Image(bitmap = bitmap.asImageBitmap(), contentDescription = "Orphan Sighting", modifier = Modifier.size(90.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
            } else {
                Box(modifier = Modifier.size(90.dp).clip(RoundedCornerShape(12.dp)).background(Color.LightGray), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray)
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text("👁️ Unverified Sighting", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                Spacer(modifier = Modifier.height(4.dp))

                if (sighting.estimatedFeatures.isNotBlank() || sighting.clothingAppearance.isNotBlank()) {
                    Text("${sighting.estimatedFeatures} | ${sighting.clothingAppearance}", style = MaterialTheme.typography.bodyMedium, color = Color.DarkGray, maxLines = 1)
                } else {
                    Text("No visual description provided", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                }

                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📍", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = sighting.location.ifBlank { "Unknown location" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                }
            }
        }
    }
}

fun decodeBase64ToBitmap(base64Str: String): Bitmap? {
    if (base64Str.isBlank()) return null
    return try {
        val decodedBytes = Base64.decode(base64Str, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}