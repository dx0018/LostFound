package com.example.lostfound

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
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
import androidx.core.net.toUri
import com.google.android.gms.location.LocationServices
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SightingScreen(onNavigateBack: () -> Unit) {
    RequireAuth { user ->
        SightingScreenContent(
            onNavigateBack = onNavigateBack,
            currentUser = user
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SightingScreenContent(
    onNavigateBack: () -> Unit,
    currentUser: FirebaseUser
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var resultBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var isUploading by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Select a photo or take a picture to scan") }
    var scanResults by remember { mutableStateOf<List<FaceScanResult>>(emptyList()) }

    var hasAutoUploaded by remember { mutableStateOf(false) }

    var sightingLocation by remember { mutableStateOf("") }
    var locationLat by remember { mutableStateOf<Double?>(null) }
    var locationLng by remember { mutableStateOf<Double?>(null) }
    var sightingDate by remember { mutableStateOf("") }

    var estimatedFeatures by remember { mutableStateOf("") }
    var clothingAppearance by remember { mutableStateOf("") }

    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = System.currentTimeMillis()
    )
    var showMapPicker by remember { mutableStateOf(false) }

    val fusedLocationClient = remember {
        LocationServices.getFusedLocationProviderClient(context)
    }

    val locationPermissionRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (
            permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            Toast.makeText(context, "Fetching real GPS location...", Toast.LENGTH_SHORT).show()
            try {
                fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                    if (loc != null) {
                        locationLat = loc.latitude
                        locationLng = loc.longitude
                        try {
                            val geocoder = Geocoder(context, Locale.getDefault())
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                geocoder.getFromLocation(loc.latitude, loc.longitude, 1) { addresses ->
                                    val address = addresses.firstOrNull()?.getAddressLine(0)
                                    sightingLocation = address ?: "Unknown address"
                                }
                            } else {
                                @Suppress("DEPRECATION")
                                val addresses = geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
                                sightingLocation =
                                    addresses?.firstOrNull()?.getAddressLine(0)
                                        ?: "Unknown address"
                            }
                        } catch (e: Exception) {
                            sightingLocation = "Unknown address"
                        }
                    } else {
                        Toast.makeText(context, "GPS is off or fetching failed.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
        } else {
            Toast.makeText(context, "Location permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        if (bitmap != null) {
            selectedBitmap = bitmap
            resultBitmap = null
            scanResults = emptyList()
            statusText = "Image loaded."
            hasAutoUploaded = false
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult

        scope.launch(Dispatchers.IO) {
            try {
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ImageDecoder.decodeBitmap(
                        ImageDecoder.createSource(context.contentResolver, uri)
                    ) { decoder, _, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        decoder.isMutableRequired = true
                    }
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                }

                withContext(Dispatchers.Main) {
                    selectedBitmap = bitmap
                    resultBitmap = null
                    scanResults = emptyList()
                    statusText = "Image loaded."
                    hasAutoUploaded = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "Image load failed: ${e.message ?: "Unknown"}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    LaunchedEffect(scanResults) {
        val matchResult = scanResults.firstOrNull { it.isMatch && it.matchedPerson != null }
        if (matchResult != null && !hasAutoUploaded && selectedBitmap != null) {
            val matchedMP = matchResult.matchedPerson!!
            
            // 🆕 Debounce check: query for recent sightings of the same missing person by this user within 10 mins & 100m
            var isDuplicate = false
            try {
                val db = FirebaseFirestore.getInstance("lostfound")
                val tenMinutesAgo = System.currentTimeMillis() - (10 * 60 * 1000)
                val recentSightingsSnap = db.collection("Sightings")
                    .whereEqualTo("linkedMissingPersonId", matchedMP.id)
                    .whereEqualTo("ownerId", currentUser.uid)
                    .whereGreaterThanOrEqualTo("timestamp", tenMinutesAgo)
                    .get()
                    .await()
                
                val recentSightings = recentSightingsSnap.toObjects(SightingRecord::class.java)
                for (recent in recentSightings) {
                    val recentLat = recent.locationLat
                    val recentLng = recent.locationLng
                    if (recentLat != null && recentLng != null && locationLat != null && locationLng != null) {
                        val distanceResults = FloatArray(1)
                        android.location.Location.distanceBetween(
                            locationLat!!, locationLng!!,
                            recentLat, recentLng,
                            distanceResults
                        )
                        if (distanceResults[0] < 100f) { // 100 meters
                            isDuplicate = true
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            if (isDuplicate) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "ℹ️ Sighting already sent from this location recently.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@LaunchedEffect
            }

            hasAutoUploaded = true
            isUploading = true

            withContext(Dispatchers.IO) {
                var uploadedPath: String? = null
                var uploadedThumbnailPath: String? = null
                var uploadedFacePath: String? = null
                try {
                    val uploadResult = StorageRepository.uploadBitmapWithFace(
                        sightingBitmap = selectedBitmap!!,
                        faceBitmap = matchResult.croppedFace,
                        folder = "sightings",
                        userId = currentUser.uid
                    )
                    uploadedPath = uploadResult.photoStoragePath
                    uploadedThumbnailPath = uploadResult.thumbnailStoragePath
                    uploadedFacePath = uploadResult.matchedFaceStoragePath
 
                    val db = FirebaseFirestore.getInstance("lostfound")
                    val batch = db.batch()
 
                    val newSightingRef = db.collection("Sightings").document()
                    val confidencePercent = (matchResult.similarity * 100).roundToInt()
 
                    val sightingData = SightingRecord(
                        id = newSightingRef.id,
                        ownerId = currentUser.uid,
                        sightingDate = sightingDate,
                        location = sightingLocation.ifBlank { "Location not provided" },
                        locationLat = locationLat,
                        locationLng = locationLng,
                        estimatedFeatures = estimatedFeatures,
                        clothingAppearance = clothingAppearance,
                        photoUrl = uploadResult.photoUrl,
                        photoStoragePath = uploadResult.photoStoragePath,
                        thumbnailUrl = uploadResult.thumbnailUrl,
                        thumbnailStoragePath = uploadResult.thumbnailStoragePath,
                        matchedFaceUrl = uploadResult.matchedFaceUrl,
                        matchedFaceStoragePath = uploadResult.matchedFaceStoragePath,
                        embedding = matchResult.faceFeature,
                        status = SightingStatus.LINKED.name,
                        linkedMissingPersonId = matchedMP.id,
                        aiConfidenceScore = confidencePercent,
                        matchLevel = "MATCH"
                    )
                    batch.set(newSightingRef, sightingData)
 
                    val notificationRef = db.collection("Notifications").document()
                    val notification = NotificationRecord(
                        id = notificationRef.id,
                        receiverId = matchedMP.ownerId,
                        senderId = currentUser.uid,
                        title = "🚨 Potential Match Found!",
                        message = "Someone reported seeing a person matching your profile at ${sightingLocation.ifBlank { "an unknown location" }}.",
                        photoUrl = uploadResult.photoUrl,
                        thumbnailUrl = uploadResult.thumbnailUrl,
                        matchedFaceUrl = uploadResult.matchedFaceUrl,
                        relatedSightingId = sightingData.id,
                        relatedMissingPersonId = matchedMP.id
                    )
                    batch.set(notificationRef, notification)
 
                    val mpRef = db.collection("MissingPersons").document(matchedMP.id)
                    batch.update(mpRef, "status", MPStatus.PENDING_VERIFICATION.name)
                    batch.update(mpRef, "linkedSightingIds", FieldValue.arrayUnion(sightingData.id))
 
                    batch.commit().await()
 
                    withContext(Dispatchers.Main) {
                        isUploading = false
                        Toast.makeText(
                            context,
                            "✅ Match detected! Tip auto-sent to family.",
                            Toast.LENGTH_LONG
                        ).show()
                        onNavigateBack()
                    }
                } catch (e: Exception) {
                    Log.e("FaceDebug", "Auto-upload failed", e)
                    uploadedPath?.let {
                        StorageRepository.deleteByPath(it)
                    }
                    uploadedThumbnailPath?.let {
                        StorageRepository.deleteByPath(it)
                    }
                    uploadedFacePath?.let {
                        StorageRepository.deleteByPath(it)
                    }
                    withContext(Dispatchers.Main) {
                        isUploading = false
                        hasAutoUploaded = false
                        Toast.makeText(
                            context,
                            "💥 Auto-upload failed: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let {
                            val formatter = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                            sightingDate = formatter.format(Date(it))
                        }
                        showDatePicker = false
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showMapPicker) {
        MapLocationPickerDialog(
            context = context,
            onDismiss = { showMapPicker = false }
        ) { latLng, addr ->
            locationLat = latLng.latitude
            locationLng = latLng.longitude
            sightingLocation = addr
            showMapPicker = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Report a Sighting", fontWeight = FontWeight.Bold)
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                windowInsets = WindowInsets(top = 8.dp)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .clip(RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                val displayBitmap = resultBitmap ?: selectedBitmap
                if (displayBitmap != null) {
                    Image(
                        bitmap = displayBitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("No Image Selected")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                OutlinedButton(
                    onClick = { galleryLauncher.launch("image/*") },
                    modifier = Modifier.weight(1f),
                    enabled = !isProcessing && !isUploading
                ) {
                    Text("🖼️ Gallery")
                }

                Spacer(modifier = Modifier.width(8.dp))

                OutlinedButton(
                    onClick = { cameraLauncher.launch(null) },
                    modifier = Modifier.weight(1f),
                    enabled = !isProcessing && !isUploading
                ) {
                    Text("📸 Camera")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Context Details (Optional)",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showDatePicker = true }
                    ) {
                        OutlinedTextField(
                            value = sightingDate,
                            onValueChange = {},
                            readOnly = true,
                            enabled = false,
                            label = { Text("Date of Sighting") },
                            trailingIcon = {
                                Icon(Icons.Default.CalendarToday, contentDescription = null)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                disabledBorderColor = MaterialTheme.colorScheme.outline,
                                disabledLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = sightingLocation,
                        onValueChange = { sightingLocation = it },
                        label = { Text("Location of Sighting") },
                        trailingIcon = {
                            Row {
                                IconButton(
                                    onClick = {
                                        locationPermissionRequest.launch(
                                            arrayOf(
                                                android.Manifest.permission.ACCESS_FINE_LOCATION,
                                                android.Manifest.permission.ACCESS_COARSE_LOCATION
                                            )
                                        )
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.MyLocation,
                                        contentDescription = "Current Location",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }

                                IconButton(onClick = { showMapPicker = true }) {
                                    Icon(
                                        Icons.Default.Map,
                                        contentDescription = "Select on Map",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 2
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = estimatedFeatures,
                            onValueChange = { estimatedFeatures = it },
                            label = { Text("Est. Height/Age") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = clothingAppearance,
                            onValueChange = { clothingAppearance = it },
                            label = { Text("Clothing") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    if (selectedBitmap == null) return@Button

                    isProcessing = true
                    scanResults = emptyList()

                    scope.launch(Dispatchers.Default) {
                        val db = FirebaseFirestore.getInstance("lostfound")
                        val yolo = YoloFaceDetector(context)
                        val extractor = MobileFaceNetExtractor(context)
                        val highAccuracyOpts = FaceDetectorOptions.Builder()
                            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                            .build()
                        val mlKitDetector = FaceDetection.getClient(highAccuracyOpts)

                        try {
                            withContext(Dispatchers.Main) {
                                statusText = "☁️ Fetching active cases..."
                            }

                            val snapshot = db.collection("MissingPersons")
                                .whereIn(
                                    "status",
                                    listOf(
                                        MPStatus.ACTIVE.name,
                                        MPStatus.PENDING_VERIFICATION.name
                                    )
                                )
                                .get()
                                .await()

                            val cloudData = snapshot.toObjects(MissingPerson::class.java)
                                .filter { it.embedding.isNotEmpty() }

                            withContext(Dispatchers.Main) {
                                statusText = "🧠 Performing AI analysis..."
                            }

                            val faces = yolo.detect(selectedBitmap!!)
                            val drawList = mutableListOf<ImageUtils.MatchInfo>()
                            val currentResults = mutableListOf<FaceScanResult>()

                            if (faces.isEmpty()) {
                                withContext(Dispatchers.Main) {
                                    statusText = "No faces found in image."
                                }
                            }

                            for (faceBox in faces) {
                                val crop = ImageUtils.cropFaceWithPadding(
                                    selectedBitmap!!,
                                    faceBox.boundingBox
                                ) ?: continue

                                val inputImage = InputImage.fromBitmap(crop, 0)
                                val mlKitFaces = mlKitDetector.process(inputImage).await()
                                if (mlKitFaces.isEmpty()) continue

                                val alignedFace = ImageUtils.alignFace(crop, mlKitFaces[0])

                                Log.d(
                                    "FaceDebug",
                                    "Sighting aligned bitmap = ${alignedFace.width}x${alignedFace.height}, " +
                                            "config=${alignedFace.config}, recycled=${alignedFace.isRecycled}"
                                )

                                if (
                                    alignedFace.isRecycled ||
                                    alignedFace.width != 112 ||
                                    alignedFace.height != 112
                                ) {
                                    throw IllegalStateException(
                                        "Invalid aligned bitmap: " +
                                                "${alignedFace.width}x${alignedFace.height}, " +
                                                "recycled=${alignedFace.isRecycled}"
                                    )
                                }

                                val currentFeature = extractor.extractFeature(alignedFace)

                                Log.d(
                                    "FaceDebug",
                                    "Sighting embedding extracted successfully, size=${currentFeature.size}"
                                )

                                var bestMatch: MissingPerson? = null
                                var bestSimilarity = 0f

                                for (person in cloudData) {
                                    val (isMatch, similarity) = extractor.verifyMatch(
                                        person.embedding.map { it.toFloat() }.toFloatArray(),
                                        currentFeature
                                    )
                                    if (isMatch && similarity > bestSimilarity) {
                                        bestSimilarity = similarity
                                        bestMatch = person
                                    }
                                }

                                 val finalResult = FaceScanResult(
                                     isMatch = bestMatch != null,
                                     matchedPerson = bestMatch,
                                     similarity = bestSimilarity,
                                     faceFeature = currentFeature.map { it.toDouble() },
                                     croppedFace = crop
                                 )

                                currentResults.add(finalResult)

                                val label = if (finalResult.isMatch) {
                                    "${(finalResult.similarity * 100).roundToInt()}%"
                                } else {
                                    "Unknown"
                                }

                                drawList.add(
                                    ImageUtils.MatchInfo(
                                        faceBox.boundingBox,
                                        finalResult.isMatch,
                                        label
                                    )
                                )
                            }

                            val finalImg = ImageUtils.drawBoundingBoxes(selectedBitmap!!, drawList)

                            withContext(Dispatchers.Main) {
                                resultBitmap = finalImg
                                scanResults = currentResults.distinctBy {
                                    it.matchedPerson?.id ?: it.faceFeature.hashCode()
                                }
                                isProcessing = false
                                statusText = "✅ Scan Complete! Found ${currentResults.size} face(s)."
                            }
                        } catch (e: Exception) {
                            Log.e("FaceDebug", "SightingScreen pipeline failed", e)
                            withContext(Dispatchers.Main) {
                                statusText = "💥 Error: ${e.message ?: "Unknown"}"
                                isProcessing = false
                            }
                        } finally {
                            yolo.close()
                            extractor.close()
                            mlKitDetector.close()
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(55.dp),
                enabled = !isProcessing && selectedBitmap != null && !isUploading
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(
                        "Run AI Recognition",
                        fontSize = MaterialTheme.typography.titleMedium.fontSize
                    )
                }
            }

            if (scanResults.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    "AI Analysis Results",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge
                )

                val pendingMatch = scanResults.firstOrNull { it.isMatch }
                if (pendingMatch != null && !hasAutoUploaded && !isUploading) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "⚠️ Tip not delivered",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    "Auto-upload did not complete. Tap Retry to resend.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            Button(
                                onClick = { scanResults = scanResults.toList() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = Color.White
                                )
                            ) {
                                Text("Retry")
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                scanResults.forEach { result ->
                    var isItemUploading by remember { mutableStateOf(false) }
                    var isItemUploaded by remember { mutableStateOf(false) }

                    val cardColor = if (result.isMatch) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = cardColor)
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                result.croppedFace?.let { faceBitmap ->
                                    Image(
                                        bitmap = faceBitmap.asImageBitmap(),
                                        contentDescription = "Face Preview",
                                        modifier = Modifier
                                            .size(60.dp)
                                            .clip(RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    val (titleText, titleColor) = if (result.isMatch) {
                                        "⚠️ MATCH FOUND: ${result.matchedPerson?.name ?: "Unknown"}" to Color.Red
                                    } else {
                                        "❔ Unknown Face Detected" to Color.Unspecified
                                    }

                                    Text(
                                        titleText,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = titleColor
                                    )

                                    if (result.isMatch) {
                                        Text(
                                            "Confidence: ${(result.similarity * 100).roundToInt()}%",
                                            color = titleColor,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            "Contact: ${result.matchedPerson?.contactPhone ?: "N/A"}",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }

                                if (result.isMatch) {
                                    val phone = result.matchedPerson?.contactPhone.orEmpty()
                                    IconButton(
                                        onClick = {
                                            if (phone.isNotBlank()) {
                                                context.startActivity(
                                                    Intent(Intent.ACTION_DIAL).apply {
                                                        data = "tel:$phone".toUri()
                                                    }
                                                )
                                            } else {
                                                Toast.makeText(
                                                    context,
                                                    "No contact number available",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        },
                                        modifier = Modifier.background(
                                            Color.White,
                                            shape = RoundedCornerShape(50)
                                        )
                                    ) {
                                        Icon(
                                            Icons.Default.Call,
                                            contentDescription = "Call",
                                            tint = Color.Green
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            if (result.isMatch) {
                                Button(
                                    onClick = {},
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                                    enabled = false
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Auto-Sending Tip to Family...")
                                }
                            } else {
                                Button(
                                    onClick = {
                                        isItemUploading = true
                                        scope.launch(Dispatchers.IO) {
                                            uploadSighting(
                                                context = context,
                                                currentUser = currentUser,
                                                selectedBitmap = selectedBitmap!!,
                                                faceBitmap = result.croppedFace,
                                                sightingDate = sightingDate,
                                                sightingLocation = sightingLocation,
                                                locationLat = locationLat,
                                                locationLng = locationLng,
                                                estimatedFeatures = estimatedFeatures,
                                                clothingAppearance = clothingAppearance,
                                                faceFeature = result.faceFeature,
                                                similarity = result.similarity,
                                                isMatch = false,
                                                matchedPersonId = null,
                                                onDone = { ok ->
                                                    isItemUploading = false
                                                    if (ok) isItemUploaded = true
                                                }
                                            )
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    ),
                                    enabled = !isItemUploading && !isItemUploaded
                                ) {
                                    when {
                                        isItemUploading -> {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(20.dp),
                                                color = Color.White
                                            )
                                        }

                                        isItemUploaded -> {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Saved")
                                        }

                                        else -> {
                                            Icon(
                                                Icons.AutoMirrored.Filled.Send,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Save to Orphan Clue Database")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }
}

private suspend fun uploadSighting(
    context: android.content.Context,
    currentUser: FirebaseUser,
    selectedBitmap: Bitmap,
    faceBitmap: Bitmap?,
    sightingDate: String,
    sightingLocation: String,
    locationLat: Double?,
    locationLng: Double?,
    estimatedFeatures: String,
    clothingAppearance: String,
    faceFeature: List<Double>,
    similarity: Float,
    isMatch: Boolean,
    matchedPersonId: String?,
    onDone: (Boolean) -> Unit
) {
    var uploadedPath: String? = null
    var uploadedThumbnailPath: String? = null
    var uploadedFacePath: String? = null
    try {
        val uploadResult = StorageRepository.uploadBitmapWithFace(
            sightingBitmap = selectedBitmap,
            faceBitmap = faceBitmap,
            folder = "sightings",
            userId = currentUser.uid
        )
        uploadedPath = uploadResult.photoStoragePath
        uploadedThumbnailPath = uploadResult.thumbnailStoragePath
        uploadedFacePath = uploadResult.matchedFaceStoragePath

        val db = FirebaseFirestore.getInstance("lostfound")
        val newRef = db.collection("Sightings").document()
        val confidencePercent = (similarity * 100).roundToInt()

        val data = SightingRecord(
            id = newRef.id,
            ownerId = currentUser.uid,
            sightingDate = sightingDate,
            location = sightingLocation.ifBlank { "Location not provided" },
            locationLat = locationLat,
            locationLng = locationLng,
            estimatedFeatures = estimatedFeatures,
            clothingAppearance = clothingAppearance,
            photoUrl = uploadResult.photoUrl,
            photoStoragePath = uploadResult.photoStoragePath,
            thumbnailUrl = uploadResult.thumbnailUrl,
            thumbnailStoragePath = uploadResult.thumbnailStoragePath,
            matchedFaceUrl = uploadResult.matchedFaceUrl,
            matchedFaceStoragePath = uploadResult.matchedFaceStoragePath,
            embedding = faceFeature,
            status = if (isMatch) SightingStatus.LINKED.name else SightingStatus.PENDING.name,
            linkedMissingPersonId = matchedPersonId,
            aiConfidenceScore = confidencePercent,
            matchLevel = if (isMatch) "MATCH" else "NO_MATCH"
        )

        newRef.set(data).await()

        withContext(Dispatchers.Main) {
            Toast.makeText(context, "✅ Submitted", Toast.LENGTH_SHORT).show()
            onDone(true)
        }
    } catch (e: Exception) {
        Log.e("FaceDebug", "uploadSighting failed", e)
        uploadedPath?.let {
            StorageRepository.deleteByPath(it)
        }
        uploadedThumbnailPath?.let {
            StorageRepository.deleteByPath(it)
        }
        uploadedFacePath?.let {
            StorageRepository.deleteByPath(it)
        }
        withContext(Dispatchers.Main) {
            Toast.makeText(
                context,
                "Upload failed: ${e.message ?: "Unknown"}",
                Toast.LENGTH_SHORT
            ).show()
            onDone(false)
        }
    }
}
