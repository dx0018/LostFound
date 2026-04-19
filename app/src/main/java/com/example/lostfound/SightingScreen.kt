package com.example.lostfound

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
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
import com.google.android.gms.maps.model.LatLng
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.WindowInsets

data class FaceScanResult(
    val isMatch: Boolean,
    val matchedPerson: MissingPerson?,
    val confidence: Int,
    val faceFeature: List<Double>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SightingScreen(onNavigateBack: () -> Unit) {
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
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis())
    var showMapPicker by remember { mutableStateOf(false) }

    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    val locationPermissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] == true || permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
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
                                    sightingLocation = address ?: "${loc.latitude}, ${loc.longitude}"
                                }
                            } else {
                                @Suppress("DEPRECATION")
                                val addresses = geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
                                sightingLocation = addresses?.firstOrNull()?.getAddressLine(0) ?: "${loc.latitude}, ${loc.longitude}"
                            }
                        } catch (e: Exception) {
                            sightingLocation = "${loc.latitude}, ${loc.longitude}"
                        }
                    } else {
                        Toast.makeText(context, "GPS is off or fetching failed.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: SecurityException) { e.printStackTrace() }
        } else {
            Toast.makeText(context, "Location permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap: Bitmap? ->
        if (bitmap != null) { selectedBitmap = bitmap; resultBitmap = null; scanResults = emptyList(); statusText = "Image loaded."; hasAutoUploaded = false }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, _, _ -> decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE; decoder.isMutableRequired = true }
                } else {
                    @Suppress("DEPRECATION") MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                }
                withContext(Dispatchers.Main) { selectedBitmap = bitmap; resultBitmap = null; scanResults = emptyList(); statusText = "Image loaded."; hasAutoUploaded = false }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(context, "Image load failed", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    LaunchedEffect(scanResults) {
        val matchResult = scanResults.firstOrNull { it.isMatch && it.matchedPerson != null }
        if (matchResult != null && !hasAutoUploaded && selectedBitmap != null) {
            hasAutoUploaded = true
            isUploading = true
            
            withContext(Dispatchers.Default) {
                try {
                    val maxDim = maxOf(selectedBitmap!!.width, selectedBitmap!!.height)
                    val scaleRatio = if (maxDim > 400) 400f / maxDim else 1.0f
                    val targetWidth = (selectedBitmap!!.width * scaleRatio).toInt()
                    val targetHeight = (selectedBitmap!!.height * scaleRatio).toInt()
                    val scaledBitmap = if (scaleRatio < 1.0f) {
                        Bitmap.createScaledBitmap(selectedBitmap!!, targetWidth, targetHeight, true)
                    } else {
                        selectedBitmap!!
                    }
                    val baos = ByteArrayOutputStream()
                    scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 50, baos)
                    if (scaledBitmap != selectedBitmap) {
                        scaledBitmap.recycle()
                    }
                    val base64Image = Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT)

                    val db = FirebaseFirestore.getInstance()
                    val currentUserId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""
                    val batch = db.batch()

                    val newSightingRef = db.collection("Sightings").document()
                    val matchedMP = matchResult.matchedPerson!!

                    val sightingData = SightingRecord(
                        id = newSightingRef.id,
                        ownerId = currentUserId,
                        sightingDate = sightingDate,
                        location = sightingLocation.ifBlank { "Location not provided" },
                        locationLat = locationLat,
                        locationLng = locationLng,
                        estimatedFeatures = estimatedFeatures,
                        clothingAppearance = clothingAppearance,
                        photoBase64 = base64Image,
                        embedding = matchResult.faceFeature,
                        status = SightingStatus.LINKED.name,
                        linkedMissingPersonId = matchedMP.id,
                        aiConfidenceScore = matchResult.confidence
                    )
                    batch.set(newSightingRef, sightingData)

                    val notificationRef = db.collection("Notifications").document()
                    val notification = NotificationRecord(
                        id = notificationRef.id,
                        receiverId = matchedMP.ownerId,
                        senderId = currentUserId,
                        title = "🚨 Potential Match Found!",
                        message = "Someone reported seeing a person matching your profile at ${sightingData.location}.",
                        photoBase64 = base64Image,
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
                        Toast.makeText(context, "✅ Match detected! Tip auto-sent to family.", Toast.LENGTH_LONG).show()
                        onNavigateBack()
                    }

                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        isUploading = false
                        hasAutoUploaded = false 
                        Toast.makeText(context, "Auto-upload failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let {
                        val formatter = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                        sightingDate = formatter.format(Date(it))
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) { DatePicker(state = datePickerState) }
    }

    if (showMapPicker) {
        MapLocationPickerDialog(context, onDismiss = { showMapPicker = false }) { latLng: LatLng, addr: String ->
            locationLat = latLng.latitude
            locationLng = latLng.longitude
            sightingLocation = addr
            showMapPicker = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Report a Sighting", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
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

            Box(modifier = Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                val displayBitmap = resultBitmap ?: selectedBitmap
                if (displayBitmap != null) {
                    Image(bitmap = displayBitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant) { 
                        Box(contentAlignment = Alignment.Center) { Text("No Image Selected") } 
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                OutlinedButton(onClick = { galleryLauncher.launch("image/*") }, modifier = Modifier.weight(1f), enabled = !isProcessing && !isUploading) { Text("🖼️ Gallery") }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(onClick = { cameraLauncher.launch(null) }, modifier = Modifier.weight(1f), enabled = !isProcessing && !isUploading) { Text("📸 Camera") }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Context Details (Optional)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(12.dp))

                    Box(modifier = Modifier.fillMaxWidth().clickable { showDatePicker = true }) {
                        OutlinedTextField(
                            value = sightingDate, onValueChange = {}, readOnly = true, enabled = false,
                            label = { Text("Date of Sighting") },
                            trailingIcon = { Icon(Icons.Default.CalendarToday, null) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                disabledBorderColor = MaterialTheme.colorScheme.outline,
                                disabledLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = sightingLocation, onValueChange = { sightingLocation = it },
                        label = { Text("Location of Sighting") },
                        trailingIcon = {
                            Row {
                                IconButton(onClick = { locationPermissionRequest.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)) }) {
                                    Icon(Icons.Default.MyLocation, "Current Location", tint = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(onClick = { showMapPicker = true }) {
                                    Icon(Icons.Default.Map, "Select on Map", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(), maxLines = 2
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = estimatedFeatures, onValueChange = { estimatedFeatures = it }, label = { Text("Est. Height/Age") }, modifier = Modifier.weight(1f), singleLine = true)
                        OutlinedTextField(value = clothingAppearance, onValueChange = { clothingAppearance = it }, label = { Text("Clothing") }, modifier = Modifier.weight(1f), singleLine = true)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(statusText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    if (selectedBitmap == null) return@Button
                    isProcessing = true; scanResults = emptyList()
                    scope.launch(Dispatchers.Default) {
                        try {
                            withContext(Dispatchers.Main) { statusText = "☁️ Fetching Active Cases..." }
                            val db = FirebaseFirestore.getInstance()
                            val snapshot = db.collection("MissingPersons").whereIn("status", listOf(MPStatus.ACTIVE.name, MPStatus.PENDING_VERIFICATION.name)).get().await()
                            val cloudData = snapshot.toObjects(MissingPerson::class.java).filter { it.embedding.isNotEmpty() }

                            withContext(Dispatchers.Main) { statusText = "🧠 AI is scanning faces..." }
                            val yolo = YoloFaceDetector(context)
                            val extractor = MobileFaceNetExtractor(context)
                            try {
                                val faces = yolo.detect(selectedBitmap!!)
                                val drawList = mutableListOf<ImageUtils.MatchInfo>()
                                val currentResults = mutableListOf<FaceScanResult>()

                                for (faceBox in faces) {
                                    val crop = ImageUtils.cropFaceWithPadding(selectedBitmap!!, faceBox.boundingBox) ?: continue
                                    val currentFeature = extractor.extractFeature(crop).map { it.toDouble() }
                                    var bestMatch: MissingPerson? = null
                                    var highestConf = 0f
                                    for (person in cloudData) {
                                        val sim = extractor.calculateSimilarity(person.embedding.map { it.toFloat() }.toFloatArray(), currentFeature.map{ it.toFloat() }.toFloatArray())
                                        val conf = extractor.calculateConfidenceScore(sim)
                                        if (conf > highestConf) { highestConf = conf; bestMatch = person }
                                    }
                                    val percentage = (highestConf * 100).roundToInt()
                                    val isMatch = highestConf > 0.80f && bestMatch != null
                                    currentResults.add(FaceScanResult(isMatch, if (isMatch) bestMatch else null, percentage, currentFeature))
                                    drawList.add(ImageUtils.MatchInfo(faceBox.boundingBox, isMatch, if(isMatch && bestMatch != null) "${bestMatch.name} $percentage%" else "Unknown"))
                                }

                                val finalImg = ImageUtils.drawBoundingBoxes(selectedBitmap!!, drawList)
                                withContext(Dispatchers.Main) {
                                    resultBitmap = finalImg
                                    scanResults = currentResults.distinctBy { it.matchedPerson?.id ?: it.faceFeature.hashCode() }
                                    isProcessing = false; statusText = "✅ Scan Complete! Found ${faces.size} face(s)."
                                }
                            } finally {
                                yolo.close()
                                extractor.close()
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) { statusText = "💥 Error: ${e.message}"; isProcessing = false }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(55.dp), enabled = !isProcessing && selectedBitmap != null && !isUploading
            ) {
                if (isProcessing) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                else Text("Run AI Recognition", fontSize = MaterialTheme.typography.titleMedium.fontSize)
            }

            if (scanResults.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                Text("AI Analysis Results", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)

                scanResults.forEach { result ->
                    var isItemUploading by remember { mutableStateOf(false) }
                    var isItemUploaded by remember { mutableStateOf(false) }
                    val cardColor = if (result.isMatch) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
                    
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = cardColor)) {
                        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(if (result.isMatch) "⚠️ MATCH FOUND: ${result.matchedPerson?.name}" else "❔ Unknown Face Detected", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = if(result.isMatch) Color.Red else Color.Unspecified)
                                    if (result.isMatch) {
                                        Text("Confidence: ${result.confidence}%", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                                        Text("Contact: ${result.matchedPerson?.contactPhone}", style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                                if (result.isMatch) {
                                    IconButton(
                                        onClick = { context.startActivity(Intent(Intent.ACTION_DIAL).apply { data = "tel:${result.matchedPerson?.contactPhone}".toUri() }) },
                                        modifier = Modifier.background(Color.White, shape = RoundedCornerShape(50))
                                    ) { Icon(Icons.Default.Call, "Call", tint = Color.Green) }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            if (result.isMatch) {
                                Button(
                                    onClick = { },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                                    enabled = false
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Auto-Sending Tip to Family...")
                                }
                            } else {
                                Button(
                                    onClick = {
                                        isItemUploading = true
                                        scope.launch(Dispatchers.Default) {
                                            try {
                                                val maxDim = maxOf(selectedBitmap!!.width, selectedBitmap!!.height)
                                                val scaleRatio = if (maxDim > 400) 400f / maxDim else 1.0f
                                                val targetWidth = (selectedBitmap!!.width * scaleRatio).toInt()
                                                val targetHeight = (selectedBitmap!!.height * scaleRatio).toInt()
                                                val scaledBitmap = if (scaleRatio < 1.0f) {
                                                    Bitmap.createScaledBitmap(selectedBitmap!!, targetWidth, targetHeight, true)
                                                } else {
                                                    selectedBitmap!!
                                                }
                                                val baos = ByteArrayOutputStream()
                                                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 50, baos)
                                                if (scaledBitmap != selectedBitmap) {
                                                    scaledBitmap.recycle()
                                                }
                                                val base64Image = Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT)

                                                val db = FirebaseFirestore.getInstance()
                                                val currentUserId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""
                                                val newSightingRef = db.collection("Sightings").document()
                                                val sightingData = SightingRecord(
                                                    id = newSightingRef.id,
                                                    ownerId = currentUserId,
                                                    sightingDate = sightingDate,
                                                    location = sightingLocation.ifBlank { "Location not provided" },
                                                    locationLat = locationLat,
                                                    locationLng = locationLng,
                                                    estimatedFeatures = estimatedFeatures, clothingAppearance = clothingAppearance,
                                                    photoBase64 = base64Image, embedding = result.faceFeature,
                                                    status = SightingStatus.PENDING.name,
                                                    linkedMissingPersonId = null,
                                                    aiConfidenceScore = result.confidence
                                                )

                                                newSightingRef.set(sightingData).await()

                                                withContext(Dispatchers.Main) {
                                                    isItemUploading = false
                                                    isItemUploaded = true
                                                    Toast.makeText(context, "✅ Saved to Orphan Clue Database", Toast.LENGTH_LONG).show()
                                                }
                                            } catch (e: Exception) {
                                                withContext(Dispatchers.Main) { isItemUploading = false; Toast.makeText(context, "Upload failed: ${e.message}", Toast.LENGTH_SHORT).show() }
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                    enabled = !isItemUploading && !isItemUploaded
                                ) {
                                    if (isItemUploading) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                                    else if (isItemUploaded) {
                                        Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp)); Spacer(modifier = Modifier.width(8.dp))
                                        Text("Saved")
                                    } else {
                                        Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(18.dp)); Spacer(modifier = Modifier.width(8.dp))
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
