package com.example.lostfound

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

// 💡 架构基石：时间线事件包装器 (Polymorphic wrapper)
sealed class TimelineEvent {
    abstract val timestamp: Long
    abstract val location: String
    abstract val photoBase64: String

    data class CaseOpened(val person: MissingPerson) : TimelineEvent() {
        override val timestamp = person.timestamp
        override val location = person.lastSeenLocation
        override val photoBase64 = person.photoBase64
    }

    data class SightingConfirmed(val sighting: SightingRecord) : TimelineEvent() {
        override val timestamp = sighting.timestamp
        override val location = sighting.location
        override val photoBase64 = sighting.photoBase64
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaseTimelineScreen(missingPersonId: String, onNavigateBack: () -> Unit) {
    var isLoading by remember { mutableStateOf(true) }
    var timelineEvents by remember { mutableStateOf<List<TimelineEvent>>(emptyList()) }
    var personName by remember { mutableStateOf("Loading Case...") }

    // 💡 异步获取并合并数据
    LaunchedEffect(missingPersonId) {
        try {
            val db = FirebaseFirestore.getInstance()

            // 1. 获取源案件 (MissingPerson)
            val mpSnapshot = db.collection("MissingPersons").document(missingPersonId).get().await()
            val mp = mpSnapshot.toObject(MissingPerson::class.java)

            if (mp != null) {
                personName = mp.name

                // 2. 🚨 规避 whereIn 限制：反向查询所有关联此案件且被确认的 Sightings
                val sightingsSnapshot = db.collection("Sightings")
                    .whereEqualTo("linkedMissingPersonId", missingPersonId)
                    .whereIn("status", listOf(SightingStatus.LINKED.name, SightingStatus.RESOLVED.name))
                    .get().await()

                val sightings = sightingsSnapshot.toObjects(SightingRecord::class.java)

                // 3. 聚合并按时间顺序排列 (最早的在上面)
                val combinedList = mutableListOf<TimelineEvent>()
                combinedList.add(TimelineEvent.CaseOpened(mp))
                sightings.forEach { combinedList.add(TimelineEvent.SightingConfirmed(it)) }

                timelineEvents = combinedList.sortedBy { it.timestamp }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            personName = "Error Loading Case"
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Case Tracker: $personName", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                windowInsets = WindowInsets(top = 8.dp),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            )
        }
    ) { paddingValues ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (timelineEvents.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                Text("No data available.", color = Color.Gray)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp) 
            ) {
                itemsIndexed(timelineEvents) { index, event ->
                    val isLastItem = index == timelineEvents.lastIndex
                    TimelineNode(event = event, isLast = isLastItem)
                }
            }
        }
    }
}

@Composable
fun TimelineNode(event: TimelineEvent, isLast: Boolean) {
    val formatter = remember { SimpleDateFormat("MMM dd, yyyy • HH:mm a", Locale.getDefault()) }
    val dateString = formatter.format(Date(event.timestamp))

    val dotColor = if (event is TimelineEvent.CaseOpened) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                if (!isLast) {
                    val strokeWidth = 2.dp.toPx()
                    val xOffset = 20.dp.toPx() 
                    val yOffset = 24.dp.toPx() 
                    drawLine(
                        color = Color.LightGray,
                        start = Offset(xOffset, yOffset),
                        end = Offset(xOffset, size.height), 
                        strokeWidth = strokeWidth
                    )
                }
            }
    ) {
        Box(
            modifier = Modifier
                .width(40.dp)
                .padding(top = 16.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 24.dp, start = 8.dp, top = 12.dp)
        ) {
            Text(dateString, style = MaterialTheme.typography.labelMedium, color = Color.Gray)
            Spacer(modifier = Modifier.height(4.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = if (event is TimelineEvent.CaseOpened) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = if (event is TimelineEvent.CaseOpened) "📍 Initial Report" else "👁️ Confirmed Sighting",
                        fontWeight = FontWeight.Bold, color = dotColor
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "Location: ${event.location}", style = MaterialTheme.typography.bodyMedium)

                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // 💡 修复：将解码逻辑移出 try-catch 块，放在 remember 中
                    val imageBitmap = remember(event.photoBase64) {
                        try {
                            if (event.photoBase64.isNotEmpty()) {
                                val imageBytes = Base64.decode(event.photoBase64, Base64.DEFAULT)
                                BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)?.asImageBitmap()
                            } else null
                        } catch (e: Exception) {
                            null
                        }
                    }

                    if (imageBitmap != null) {
                        Image(
                            bitmap = imageBitmap,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp)
                                .background(Color.LightGray.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No image available", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }
                }
            }
        }
    }
}
