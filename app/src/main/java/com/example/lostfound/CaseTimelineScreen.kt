package com.example.lostfound

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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

// 💡 时间线事件包装器 (Polymorphic wrapper)
sealed class TimelineEvent {
    abstract val timestamp: Long
    abstract val location: String
    abstract val photoUrl: String   // 🆕 改为 photoUrl
    abstract val thumbnailUrl: String
    abstract val matchedFaceUrl: String

    data class CaseOpened(val person: MissingPerson) : TimelineEvent() {
        override val timestamp = person.timestamp
        override val location = person.lastSeenLocation
        override val photoUrl = person.photoUrl
        override val thumbnailUrl = person.thumbnailUrl
        override val matchedFaceUrl = ""
    }

    data class SightingConfirmed(val sighting: SightingRecord) : TimelineEvent() {
        override val timestamp = sighting.timestamp
        override val location = sighting.location
        override val photoUrl = sighting.photoUrl
        override val thumbnailUrl = sighting.thumbnailUrl
        override val matchedFaceUrl = sighting.matchedFaceUrl
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaseTimelineScreen(missingPersonId: String, onNavigateBack: () -> Unit) {
    var isLoading by remember { mutableStateOf(true) }
    var timelineEvents by remember { mutableStateOf<List<TimelineEvent>>(emptyList()) }
    var personName by remember { mutableStateOf("Loading Case...") }

    LaunchedEffect(missingPersonId) {
        try {
            val db = FirebaseFirestore.getInstance()

            val mpSnapshot = db.collection("MissingPersons").document(missingPersonId).get().await()
            val mp = mpSnapshot.toObject(MissingPerson::class.java)

            if (mp != null) {
                personName = mp.name

                val sightingsSnapshot = db.collection("Sightings")
                    .whereEqualTo("linkedMissingPersonId", missingPersonId)
                    .whereIn("status", listOf(SightingStatus.LINKED.name, SightingStatus.RESOLVED.name))
                    .get().await()

                val sightings = sightingsSnapshot.toObjects(SightingRecord::class.java)

                val combinedList = mutableListOf<TimelineEvent>()
                combinedList.add(TimelineEvent.CaseOpened(mp))
                sightings.forEach { combinedList.add(TimelineEvent.SightingConfirmed(it)) }

                timelineEvents = combinedList.sortedBy { it.timestamp }
            } else {
                // If it is not a missing person, it might be an orphan/pending sighting ID!
                val sightingSnapshot = db.collection("Sightings").document(missingPersonId).get().await()
                val sighting = sightingSnapshot.toObject(SightingRecord::class.java)

                if (sighting != null) {
                    if (!sighting.linkedMissingPersonId.isNullOrBlank()) {
                        // Redirect to the linked MissingPerson case timeline automatically!
                        val linkedMpSnapshot = db.collection("MissingPersons")
                            .document(sighting.linkedMissingPersonId!!).get().await()
                        val linkedMp = linkedMpSnapshot.toObject(MissingPerson::class.java)
                        if (linkedMp != null) {
                            personName = linkedMp.name
                            val sightings = db.collection("Sightings")
                                .whereEqualTo("linkedMissingPersonId", linkedMp.id)
                                .whereIn("status", listOf(SightingStatus.LINKED.name, SightingStatus.RESOLVED.name))
                                .get().await().toObjects(SightingRecord::class.java)

                            val combinedList = mutableListOf<TimelineEvent>()
                            combinedList.add(TimelineEvent.CaseOpened(linkedMp))
                            sightings.forEach { combinedList.add(TimelineEvent.SightingConfirmed(it)) }

                            timelineEvents = combinedList.sortedBy { it.timestamp }
                        }
                    } else {
                        // Render orphan timeline with single Sighting node
                        personName = "Orphan Sighting Clue"
                        timelineEvents = listOf(TimelineEvent.SightingConfirmed(sighting))
                    }
                } else {
                    personName = "Unknown Case"
                }
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
                    val titleText = if (event is TimelineEvent.CaseOpened) {
                        "📍 Initial Report"
                    } else {
                        val sig = (event as TimelineEvent.SightingConfirmed).sighting
                        if (sig.status == SightingStatus.PENDING.name) {
                            "👁️ Sighting Submitted (Orphan Clue)"
                        } else {
                            "👁️ Confirmed Sighting"
                        }
                    }

                    Text(
                        text = titleText,
                        fontWeight = FontWeight.Bold, color = dotColor
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "Location: ${event.location}", style = MaterialTheme.typography.bodyMedium)

                    Spacer(modifier = Modifier.height(8.dp))

                    val imageModel = event.matchedFaceUrl.ifBlank { event.thumbnailUrl.ifBlank { event.photoUrl } }

                    // 🆕 Coil 异步加载 URL
                    if (imageModel.isNotBlank()) {
                        AsyncImage(
                            model = imageModel,
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
