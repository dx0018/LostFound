package com.example.lostfound

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Geocoder
import android.util.Base64
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

// ============================================================
// Data models
// ============================================================

sealed class FeedItem {
    abstract val timestamp: Long
    data class PersonItem(val person: MissingPerson) : FeedItem() {
        override val timestamp = person.timestamp
    }
    data class SightingItem(val sighting: SightingRecord) : FeedItem() {
        override val timestamp = sighting.timestamp
    }
}

data class MapMarkerInfo(
    val id: String,
    val latLng: LatLng,
    val title: String,
    val isMissingPerson: Boolean,
    val photoBase64: String = "",
    val age: String = "",
    val gender: String = "",
    val status: String = "",
    val date: String = "",
    val locationName: String = ""
)

// ============================================================
// Design tokens
// ============================================================

private val ColorMissing  = Color(0xFFE53935)
private val ColorSighting = Color(0xFF1E88E5)
private val ColorPending  = Color(0xFFFFA000)

// 低于此 zoom 级别时,地图上的 marker 只显示纯色圆点;
// 高于此 zoom 时显示带头像的 marker。可按产品需求调整。
private const val ZOOM_THRESHOLD = 9f

// ============================================================
// Custom three-stage sheet
// ============================================================

private enum class SheetStage { Peek, Half, Full }

// ============================================================
// Main screen
// ============================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToProfile: () -> Unit = {},
    onNavigateToTimeline: (String) -> Unit,
    bottomBarHeight: Dp = 80.dp
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    // --- Firestore state ---
    var personList    by remember { mutableStateOf<List<MissingPerson>>(emptyList()) }
    var sightingList  by remember { mutableStateOf<List<SightingRecord>>(emptyList()) }
    var isLoading     by remember { mutableStateOf(true) }

    // --- UI state ---
    var searchQuery      by remember { mutableStateOf("") }
    var mapMarkers       by remember { mutableStateOf<List<MapMarkerInfo>>(emptyList()) }
    var hasCenteredMap   by remember { mutableStateOf(false) }
    var selectedSighting by remember { mutableStateOf<MapMarkerInfo?>(null) }

    val markerIconCache = remember { mutableStateMapOf<String, BitmapDescriptor>() }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(3.1390, 101.6869), 6f)
    }

    // --- Zoom 监听:只有跨过阈值时才触发实际重组 ---
    val showPhotoMarkers by remember {
        derivedStateOf { cameraPositionState.position.zoom >= ZOOM_THRESHOLD }
    }

    // --- Firestore listeners ---
    DisposableEffect(Unit) {
        val db = FirebaseFirestore.getInstance()
        val personListener = db.collection("MissingPersons")
            .whereIn("status", listOf(MPStatus.ACTIVE.name, MPStatus.PENDING_VERIFICATION.name))
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
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

    // --- Combined, filtered feed ---
    val combinedFeed = remember(personList, sightingList, searchQuery) {
        val all = (personList.map { FeedItem.PersonItem(it) } +
                sightingList.map { FeedItem.SightingItem(it) })
            .sortedByDescending { it.timestamp }

        if (searchQuery.isBlank()) all
        else all.filter {
            when (it) {
                is FeedItem.PersonItem ->
                    it.person.name.contains(searchQuery, ignoreCase = true) ||
                            it.person.lastSeenLocation.contains(searchQuery, ignoreCase = true)
                is FeedItem.SightingItem ->
                    it.sighting.location.contains(searchQuery, ignoreCase = true) ||
                            it.sighting.clothingAppearance.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    // --- Build map markers ---
    LaunchedEffect(combinedFeed) {
        val markers = mutableListOf<MapMarkerInfo>()
        for (item in combinedFeed) {
            val latLng = when {
                item is FeedItem.PersonItem &&
                        item.person.locationLat != null &&
                        item.person.locationLng != null ->
                    LatLng(item.person.locationLat, item.person.locationLng)

                item is FeedItem.SightingItem &&
                        item.sighting.locationLat != null &&
                        item.sighting.locationLng != null ->
                    LatLng(item.sighting.locationLat, item.sighting.locationLng)

                else -> {
                    val addr = if (item is FeedItem.PersonItem) item.person.lastSeenLocation
                    else (item as FeedItem.SightingItem).sighting.location
                    getLatLngFromAddress(context, addr)
                }
            }
            if (latLng != null) {
                markers.add(when (item) {
                    is FeedItem.PersonItem -> MapMarkerInfo(
                        id = item.person.id, latLng = latLng,
                        title = item.person.name, isMissingPerson = true,
                        photoBase64 = item.person.photoBase64,
                        age = item.person.age, gender = item.person.gender,
                        status = item.person.status,
                        date = item.person.lastSeenDate,
                        locationName = item.person.lastSeenLocation
                    )
                    is FeedItem.SightingItem -> MapMarkerInfo(
                        id = item.sighting.id, latLng = latLng,
                        title = "Unverified Sighting", isMissingPerson = false,
                        photoBase64 = item.sighting.photoBase64,
                        status = "PENDING",
                        date = item.sighting.sightingDate,
                        locationName = item.sighting.location
                    )
                })
            }
        }
        mapMarkers = markers
    }

    // --- Pre-generate marker icons(头像版 + 纯色点版)---
    LaunchedEffect(mapMarkers) {
        withContext(Dispatchers.Default) {
            // 纯色点 —— 只需两个
            if (!markerIconCache.containsKey("DOT_M"))
                markerIconCache["DOT_M"] = MapMarkerUtils.buildDotMarker(MapMarkerUtils.COLOR_MISSING)
            if (!markerIconCache.containsKey("DOT_S"))
                markerIconCache["DOT_S"] = MapMarkerUtils.buildDotMarker(MapMarkerUtils.COLOR_SIGHTING)

            // 头像版 —— 每个 marker 一个,key 用 id + photo 哈希保证唯一
            for (marker in mapMarkers) {
                val photoKey = "PHOTO__"
                if (!markerIconCache.containsKey(photoKey)) {
                    val color = if (marker.isMissingPerson) MapMarkerUtils.COLOR_MISSING
                    else MapMarkerUtils.COLOR_SIGHTING
                    markerIconCache[photoKey] = MapMarkerUtils.buildMarker(marker.photoBase64, color)
                }
            }
        }
    }

    // --- Auto-center camera once ---
    LaunchedEffect(mapMarkers) {
        if (mapMarkers.isNotEmpty() && !hasCenteredMap) {
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngZoom(mapMarkers.first().latLng, 10f)
            )
            hasCenteredMap = true
        }
    }

    // ----------------------------------------------------------------
    // Three-stage sheet setup
    // ----------------------------------------------------------------
    val density        = LocalDensity.current
    val config         = LocalConfiguration.current
    val screenHeightDp = config.screenHeightDp.dp
    val screenHeightPx = with(density) { screenHeightDp.toPx() }

    val peekHeightPx = with(density) { (bottomBarHeight + 72.dp).toPx() }
    val halfHeightPx = screenHeightPx * 0.55f
    val fullHeightPx = screenHeightPx * 0.90f

    val peekOffset = screenHeightPx - peekHeightPx
    val halfOffset = screenHeightPx - halfHeightPx
    val fullOffset = screenHeightPx - fullHeightPx

    val sheetAnim = remember { Animatable(halfOffset) }
    var currentStage by remember { mutableStateOf(SheetStage.Half) }

    suspend fun snapToStage(stage: SheetStage) {
        val target = when (stage) {
            SheetStage.Peek -> peekOffset
            SheetStage.Half -> halfOffset
            SheetStage.Full -> fullOffset
        }
        currentStage = stage
        sheetAnim.animateTo(target, animationSpec = tween(durationMillis = 280))
    }

    fun cycleStage() {
        scope.launch {
            val next = when (currentStage) {
                SheetStage.Peek -> SheetStage.Half
                SheetStage.Half -> SheetStage.Full
                SheetStage.Full -> SheetStage.Peek
            }
            snapToStage(next)
        }
    }

    // ----------------------------------------------------------------
    // Layout
    // ----------------------------------------------------------------
    Box(modifier = Modifier.fillMaxSize()) {

        // ---- Map ----
        GoogleMap(
            modifier            = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            uiSettings          = MapUiSettings(
                zoomControlsEnabled     = false,
                myLocationButtonEnabled = true
            )
        ) {
            mapMarkers.forEach { marker ->
                val iconKey = if (showPhotoMarkers) {
                    "PHOTO__"
                } else {
                    if (marker.isMissingPerson) "DOT_M" else "DOT_S"
                }
                val icon = markerIconCache[iconKey]

                // 头像 marker 的 anchor 在底部尖角,纯色点在中心
                val anchor = if (showPhotoMarkers) Offset(0.5f, 1.0f)
                else Offset(0.5f, 0.5f)

                MarkerInfoWindowContent(
                    state  = MarkerState(position = marker.latLng),
                    icon   = icon,
                    anchor = anchor,
                    title  = marker.title,
                    onInfoWindowClick = {
                        if (marker.isMissingPerson) onNavigateToTimeline(marker.id)
                        else selectedSighting = marker
                    }
                ) { MapInfoWindowContent(marker) }
            }
        }

        // ---- Top scrim ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(
                    Brush.verticalGradient(listOf(Color(0x66000000), Color.Transparent))
                )
        )

        // ---- Top bar ----
        HomeTopBar(
            searchQuery    = searchQuery,
            onSearchChange = { searchQuery = it },
            onProfileClick = onNavigateToProfile
        )

        // ---- Three-stage bottom sheet ----
        val sheetOffsetDp = with(density) { sheetAnim.value.toDp() }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .offset(y = sheetOffsetDp)
                .padding(bottom = bottomBarHeight),
                shape           = RoundedCornerShape(topStart = 15.dp, topEnd = 15.dp),
                shadowElevation = 16.dp,
                color           = MaterialTheme.colorScheme.surface
        ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ---- Drag handle + header ----
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { _, dragAmount ->
                                scope.launch {
                                    val newVal = (sheetAnim.value + dragAmount)
                                        .coerceIn(fullOffset, peekOffset)
                                    sheetAnim.snapTo(newVal)
                                }
                            },
                            onDragEnd = {
                                val current = sheetAnim.value
                                val nearest = listOf(
                                    SheetStage.Peek to peekOffset,
                                    SheetStage.Half to halfOffset,
                                    SheetStage.Full to fullOffset
                                ).minByOrNull { abs(it.second - current) }
                                    ?.first ?: SheetStage.Half
                                scope.launch { snapToStage(nearest) }
                            }
                        )
                    }
                    .clickable { cycleStage() }
                    .padding(vertical = 10.dp)
            ) {
                Column(
                    modifier            = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .width(40.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0xFFBDBDBD))
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Nearby Alerts",
                            style      = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color      = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.width(8.dp))
                        if (combinedFeed.isNotEmpty()) {
                            Surface(
                                color = ColorMissing,
                                shape = RoundedCornerShape(50.dp)
                            ) {
                                Text(
                                    combinedFeed.size.toString(),
                                    color      = Color.White,
                                    modifier   = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style      = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(Modifier.width(8.dp))
                        val hint = when (currentStage) {
                            SheetStage.Peek -> "Tap to expand"
                            SheetStage.Half -> "Tap for full view"
                            SheetStage.Full -> "Tap to collapse"
                        }
                        Text(
                            "· $hint",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                    }
                }
            }

            // ---- Sheet body ----
            SheetBody(
                isLoading            = isLoading,
                combinedFeed         = combinedFeed,
                onNavigateToTimeline = onNavigateToTimeline
            )
        }
    }
    }

    // Sighting detail dialog
    selectedSighting?.let { sighting ->
        SightingDetailDialog(sighting = sighting, onDismiss = { selectedSighting = null })
    }
}

// ============================================================
// Top bar — Profile FAB (left) + floating Search bar (centre)
// ============================================================

@Composable
private fun HomeTopBar(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onProfileClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, start = 16.dp, end = 16.dp)
    ) {
        Card(
            modifier  = Modifier
                .fillMaxWidth()
                .padding(start = 56.dp),
            shape     = RoundedCornerShape(50.dp),
            elevation = CardDefaults.cardElevation(8.dp),
            colors    = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Row(
                modifier          = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value         = searchQuery,
                    onValueChange = onSearchChange,
                    singleLine    = true,
                    textStyle     = LocalTextStyle.current.copy(
                        fontSize = 14.sp,
                        color    = Color(0xFF1A1A2E)
                    ),
                    modifier      = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if (searchQuery.isEmpty()) {
                            Text(
                                "Search name or location…",
                                fontSize = 13.sp,
                                color    = Color(0xFF9E9E9E)
                            )
                        }
                        inner()
                    }
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector        = Icons.Default.Search,
                    contentDescription = "Search",
                    tint               = ColorSighting,
                    modifier           = Modifier.size(20.dp)
                )
            }
        }

        Box(
            modifier         = Modifier
                .size(48.dp)
                .shadow(8.dp, CircleShape)
                .background(Color.White, CircleShape)
                .clickable { onProfileClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector        = Icons.Default.Person,
                contentDescription = "Profile",
                tint               = ColorSighting,
                modifier           = Modifier.size(24.dp)
            )
        }
    }
}

// ============================================================
// Sheet body
// ============================================================

@Composable
private fun SheetBody(
    isLoading:            Boolean,
    combinedFeed:         List<FeedItem>,
    onNavigateToTimeline: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                color    = ColorMissing
            )
        } else {
            LazyColumn(
                modifier            = Modifier.fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding      = PaddingValues(bottom = 24.dp)
            ) {
                items(combinedFeed) { item ->
                    when (item) {
                        is FeedItem.PersonItem ->
                            MissingPersonCard(
                                person  = item.person,
                                onClick = { onNavigateToTimeline(item.person.id) }
                            )
                        is FeedItem.SightingItem ->
                            SightingCard(sighting = item.sighting)
                    }
                }
            }
        }
    }
}

// ============================================================
// Sighting detail dialog
// ============================================================

@Composable
private fun SightingDetailDialog(
    sighting:  MapMarkerInfo,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier  = Modifier.fillMaxWidth().padding(8.dp),
            shape     = RoundedCornerShape(24.dp),
            colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(16.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                val bitmap = remember(sighting.photoBase64) {
                    decodeBase64ToBitmap(sighting.photoBase64)
                }
                if (bitmap != null) {
                    Image(
                        bitmap             = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier           = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)),
                        contentScale       = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier         = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color(0xFF1E88E5), Color(0xFF1565C0))
                                )
                            )
                            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Person, null,
                            tint     = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(72.dp)
                        )
                    }
                }

                Column(modifier = Modifier.padding(20.dp)) {
                    Surface(
                        color = ColorSighting,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            "👁  Potential Sighting",
                            color      = Color.White,
                            modifier   = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            style      = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Unverified Community Report",
                        style      = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(16.dp))
                    DetailRow("📍", "Location", sighting.locationName)
                    Spacer(Modifier.height(10.dp))
                    DetailRow("📅", "Reported on", sighting.date.ifBlank { "Unknown" })
                    Spacer(Modifier.height(20.dp))
                    Text(
                        "This sighting has not been linked to any missing person report yet. " +
                                "If you recognise this individual, please contact the nearest authority.",
                        style      = MaterialTheme.typography.bodySmall,
                        color      = Color.Gray,
                        lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick  = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        shape    = RoundedCornerShape(12.dp),
                        colors   = ButtonDefaults.buttonColors(containerColor = ColorSighting)
                    ) {
                        Text("Got it", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(emoji: String, label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(emoji, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray
            )
            Text(
                value,
                style      = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

// ============================================================
// Map info window
// ============================================================

@Composable
fun MapInfoWindowContent(marker: MapMarkerInfo) {
    Card(
        modifier  = Modifier.width(220.dp),
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(8.dp)
    ) {
        Column {
            val bitmap = remember(marker.photoBase64) { decodeBase64ToBitmap(marker.photoBase64) }
            if (bitmap != null) {
                Image(
                    bitmap             = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier           = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
                    contentScale       = ContentScale.Crop
                )
            } else {
                Box(
                    modifier         = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .background(
                            if (marker.isMissingPerson)
                                Brush.verticalGradient(listOf(Color(0xFFE53935), Color(0xFFB71C1C)))
                            else
                                Brush.verticalGradient(listOf(Color(0xFF1E88E5), Color(0xFF1565C0)))
                        )
                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Person, null,
                        tint     = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(40.dp)
                    )
                }
            }

            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    marker.title,
                    fontWeight = FontWeight.Bold,
                    style      = MaterialTheme.typography.titleSmall,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))

                val badgeColor = when {
                    !marker.isMissingPerson     -> ColorSighting
                    marker.status == "ACTIVE"   -> ColorMissing
                    else                        -> ColorPending
                }
                val badgeText = if (marker.isMissingPerson) marker.status else "SIGHTING"
                Surface(color = badgeColor, shape = RoundedCornerShape(4.dp)) {
                    Text(
                        badgeText,
                        color      = Color.White,
                        modifier   = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style      = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📍", fontSize = 10.sp)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        marker.locationName,
                        style    = MaterialTheme.typography.labelSmall,
                        color    = Color.DarkGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(8.dp))
                val ctaColor = if (marker.isMissingPerson) ColorMissing else ColorSighting
                val ctaText  = if (marker.isMissingPerson) "View Timeline →" else "View Details →"
                Text(
                    ctaText,
                    color      = ctaColor,
                    fontWeight = FontWeight.Bold,
                    style      = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

// ============================================================
// Feed cards
// ============================================================

@Composable
fun MissingPersonCard(person: MissingPerson, onClick: () -> Unit) {
    Card(
        modifier  = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape     = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier          = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val bitmap = remember(person.photoBase64) { decodeBase64ToBitmap(person.photoBase64) }
            Box(
                modifier         = Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap             = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier           = Modifier.fillMaxSize(),
                        contentScale       = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier         = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color(0xFFE53935), Color(0xFFB71C1C))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Person, null,
                            tint     = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                val isActive = person.status.uppercase() == "ACTIVE"
                Box(
                    modifier         = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            if (isActive) ColorMissing.copy(alpha = 0.88f)
                            else ColorPending.copy(alpha = 0.88f)
                        )
                        .padding(vertical = 3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        person.status.uppercase(),
                        color      = Color.White,
                        style      = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        fontSize   = 9.sp
                    )
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    person.name,
                    style      = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    InfoChip(label = person.age.ifBlank { "Age N/A" })
                    Spacer(Modifier.width(6.dp))
                    InfoChip(label = person.gender.ifBlank { "—" })
                }

                Spacer(Modifier.height(6.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📍", fontSize = 11.sp)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        person.lastSeenLocation.ifBlank { "Location unknown" },
                        style    = MaterialTheme.typography.bodySmall,
                        color    = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📅", fontSize = 11.sp)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        person.lastSeenDate.ifBlank { "Date unknown" },
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                }
            }

            Icon(
                imageVector        = Icons.Default.ChevronRight,
                contentDescription = null,
                tint               = Color.LightGray,
                modifier           = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun InfoChip(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            label,
            modifier   = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style      = MaterialTheme.typography.labelSmall,
            color      = MaterialTheme.colorScheme.onSecondaryContainer,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun SightingCard(sighting: SightingRecord) {
    val context = LocalContext.current
    Card(
        modifier  = Modifier
            .fillMaxWidth()
            .clickable {
                Toast.makeText(
                    context,
                    "Orphan clue — awaiting AI family match.",
                    Toast.LENGTH_SHORT
                ).show()
            },
        shape     = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors    = CardDefaults.cardColors(containerColor = Color(0xFFF0F4FF))
    ) {
        Row(
            modifier          = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val bitmap = remember(sighting.photoBase64) { decodeBase64ToBitmap(sighting.photoBase64) }
            Box(
                modifier         = Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap             = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier           = Modifier.fillMaxSize(),
                        contentScale       = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier         = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color(0xFF1E88E5), Color(0xFF1565C0))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Search, null,
                            tint     = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                Box(
                    modifier         = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(ColorSighting.copy(alpha = 0.88f))
                        .padding(vertical = 3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "PENDING",
                        color      = Color.White,
                        style      = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        fontSize   = 9.sp
                    )
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("👁  ", fontSize = 14.sp)
                    Text(
                        "Unverified Sighting",
                        style      = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color      = ColorSighting
                    )
                }

                Spacer(Modifier.height(4.dp))

                val description = buildString {
                    if (sighting.estimatedFeatures.isNotBlank())
                        append(sighting.estimatedFeatures)
                    if (sighting.clothingAppearance.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append(sighting.clothingAppearance)
                    }
                }.ifBlank { "No visual description provided" }

                Text(
                    description,
                    style    = MaterialTheme.typography.bodySmall,
                    color    = Color.DarkGray,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(6.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📍", fontSize = 11.sp)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        sighting.location.ifBlank { "Location unknown" },
                        style    = MaterialTheme.typography.bodySmall,
                        color    = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// ============================================================
// Helpers
// ============================================================

suspend fun getLatLngFromAddress(
    context: Context,
    address: String
): LatLng? = withContext(Dispatchers.IO) {
    if (address.isBlank()) return@withContext null

    // 直接支持 "lat,lng" 格式
    val regex = Regex("""(-?\d+\.\d+)\s*,\s*(-?\d+\.\d+)""")
    val match = regex.find(address)
    if (match != null) {
        return@withContext LatLng(
            match.groupValues[1].toDouble(),
            match.groupValues[2].toDouble()
        )
    }

    try {
        val geocoder = Geocoder(context, Locale.getDefault())
        @Suppress("DEPRECATION")
        val results = geocoder.getFromLocationName(address, 1)
        if (!results.isNullOrEmpty()) {
            return@withContext LatLng(
                results[0].latitude,
                results[0].longitude
            )
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    null
}

fun decodeBase64ToBitmap(base64Str: String): Bitmap? {
    if (base64Str.isBlank()) return null
    return try {
        val bytes = Base64.decode(base64Str, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
