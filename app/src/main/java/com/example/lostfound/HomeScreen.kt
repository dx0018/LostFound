package com.example.lostfound

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Geocoder
import android.os.Build
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
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.firebase.firestore.FirebaseFirestore
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

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

// 💡 地图标记数据模型
data class MapMarkerInfo(
    val id: String,
    val latLng: LatLng,
    val title: String,
    val isMissingPerson: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToProfile: () -> Unit = {},
    onNavigateToTimeline: (String) -> Unit
) {
    val context = LocalContext.current
    var personList by remember { mutableStateOf<List<MissingPerson>>(emptyList()) }
    var sightingList by remember { mutableStateOf<List<SightingRecord>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf("") }

    // 地图相关状态
    var mapMarkers by remember { mutableStateOf<List<MapMarkerInfo>>(emptyList()) }
    var hasCenteredMap by remember { mutableStateOf(false) }
    
    // 默认视角：马来西亚吉隆坡/柔佛大致范围
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(3.1390, 101.6869), 6f)
    }

    DisposableEffect(Unit) {
        val db = FirebaseFirestore.getInstance()

        val personListener = db.collection("MissingPersons")
            .whereIn("status", listOf(MPStatus.ACTIVE.name, MPStatus.PENDING_VERIFICATION.name))
            .addSnapshotListener { snapshot, error ->
                if (error != null) { errorMessage = error.message.toString(); return@addSnapshotListener }
                if (snapshot != null) personList = snapshot.toObjects(MissingPerson::class.java)
                isLoading = false
            }

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

    val combinedFeed = remember(personList, sightingList) {
        val persons = personList.map { FeedItem.PersonItem(it) }
        val sightings = sightingList.map { FeedItem.SightingItem(it) }
        (persons + sightings).sortedByDescending { it.timestamp }
    }

    // 💡 后台异步解析位置字符串为 LatLng，生成地图标记
    LaunchedEffect(combinedFeed) {
        val markers = mutableListOf<MapMarkerInfo>()
        for (item in combinedFeed) {
            val locationStr = when(item) {
                is FeedItem.PersonItem -> item.person.lastSeenLocation
                is FeedItem.SightingItem -> item.sighting.location
            }
            
            // 🚨 升级：如果数据库中已经存有精确的坐标，直接使用，不再依赖低效的 Geocoder
            val latLng = if (item is FeedItem.PersonItem && item.person.locationLat != null && item.person.locationLng != null) {
                LatLng(item.person.locationLat, item.person.locationLng)
            } else if (item is FeedItem.SightingItem && item.sighting.locationLat != null && item.sighting.locationLng != null) {
                LatLng(item.sighting.locationLat, item.sighting.locationLng)
            } else {
                getLatLngFromAddress(context, locationStr)
            }
            
            if (latLng != null) {
                val title = when(item) {
                    is FeedItem.PersonItem -> item.person.name
                    is FeedItem.SightingItem -> "Unverified Sighting"
                }
                val id = when(item) {
                    is FeedItem.PersonItem -> item.person.id
                    is FeedItem.SightingItem -> item.sighting.id
                }
                markers.add(MapMarkerInfo(id, latLng, title, item is FeedItem.PersonItem))
            }
        }
        mapMarkers = markers
    }

    // 当获取到标记后，将地图视角拉到最新的案件位置
    LaunchedEffect(mapMarkers) {
        if (mapMarkers.isNotEmpty() && !hasCenteredMap) {
            cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(mapMarkers.first().latLng, 10f))
            hasCenteredMap = true
        }
    }

    val sheetState = rememberStandardBottomSheetState(
        initialValue = SheetValue.PartiallyExpanded,
        skipHiddenState = true 
    )
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = 320.dp,
        sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        sheetContainerColor = MaterialTheme.colorScheme.surface,
        sheetContent = {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(
                    text = "Nearby Cases", 
                    style = MaterialTheme.typography.headlineSmall, 
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Divider(color = MaterialTheme.colorScheme.surfaceVariant, thickness = 1.dp)
                Spacer(modifier = Modifier.height(16.dp))

                if (isLoading) {
                    Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (errorMessage.isNotEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { Text(errorMessage, color = MaterialTheme.colorScheme.error) }
                } else if (combinedFeed.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { Text("No active cases or sightings.", color = Color.Gray) }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 120.dp)
                    ) {
                        items(combinedFeed) { item ->
                            when (item) {
                                is FeedItem.PersonItem -> {
                                    MissingPersonCard(
                                        person = item.person,
                                        onClick = { onNavigateToTimeline(item.person.id) }
                                    )
                                }
                                is FeedItem.SightingItem -> {
                                    SightingCard(sighting = item.sighting)
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState
            ) {
                mapMarkers.forEach { marker ->
                    Marker(
                        state = MarkerState(position = marker.latLng),
                        title = marker.title,
                        snippet = if (marker.isMissingPerson) "Missing Person" else "Sighting"
                    )
                }
            }

            Surface(
                onClick = onNavigateToProfile,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.padding(16.dp).size(50.dp).align(Alignment.TopStart),
                shadowElevation = 6.dp
            ) {
                Icon(Icons.Default.Person, contentDescription = "Profile", modifier = Modifier.padding(12.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

suspend fun getLatLngFromAddress(context: Context, address: String): LatLng? = withContext(Dispatchers.IO) {
    if (address.isBlank()) return@withContext null
    
    val regex = Regex("""(-?\d+\.\d+)\s*,\s*(-?\d+\.\d+)""")
    val match = regex.find(address)
    if (match != null) {
        return@withContext LatLng(match.groupValues[1].toDouble(), match.groupValues[2].toDouble())
    }
    
    try {
        val geocoder = Geocoder(context, Locale.getDefault())
        @Suppress("DEPRECATION")
        val results = geocoder.getFromLocationName(address, 1)
        if (!results.isNullOrEmpty()) {
            return@withContext LatLng(results[0].latitude, results[0].longitude)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return@withContext null
}


// --- 下方为原样保留的 UI Card 组件 ---

@Composable
fun MissingPersonCard(person: MissingPerson, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
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
                    Text(text = "Last seen: ${person.lastSeenDate} ${person.lastSeenLocation}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
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
            Toast.makeText(context, "This is an orphan clue awaiting AI family match.", Toast.LENGTH_SHORT).show()
        },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
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
                    Text(text = "${sighting.sightingDate} ${sighting.location.ifBlank { "Unknown location" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
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
