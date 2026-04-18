package com.example.lostfound

import android.content.Context
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PersonAdd
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf("") }
    var height by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var locationLat by remember { mutableStateOf<Double?>(null) }
    var locationLng by remember { mutableStateOf<Double?>(null) }
    var contact by remember { mutableStateOf("") }
    var lastSeenDate by remember { mutableStateOf("") }

    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isUploading by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf("") }

    var showMatchDialog by remember { mutableStateOf(false) }
    var historicalMatches by remember { mutableStateOf<List<SightingRecord>>(emptyList()) }
    var currentMissingPersonId by remember { mutableStateOf("") }

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
                                    location = address ?: "${loc.latitude}, ${loc.longitude}"
                                }
                            } else {
                                @Suppress("DEPRECATION")
                                val addresses = geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
                                location = addresses?.firstOrNull()?.getAddressLine(0) ?: "${loc.latitude}, ${loc.longitude}"
                            }
                        } catch (e: Exception) { location = "${loc.latitude}, ${loc.longitude}" }
                    } else Toast.makeText(context, "GPS is off or fetching failed.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: SecurityException) { e.printStackTrace() }
        } else {
            Toast.makeText(context, "Location permission denied", Toast.LENGTH_SHORT).show()
        }
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
                withContext(Dispatchers.Main) { selectedBitmap = bitmap }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(context, "Image load failed", Toast.LENGTH_SHORT).show() }
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
                        lastSeenDate = formatter.format(Date(it))
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
        MapLocationPickerDialog(context, onDismiss = { showMapPicker = false }) { latLng, addr ->
            locationLat = latLng.latitude
            locationLng = latLng.longitude
            location = addr
            showMapPicker = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Report Missing Person", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "Back") }
                }
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

            // ================= Image Picker Area =================
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(2.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                    .clickable { galleryLauncher.launch("image/*") },
                contentAlignment = Alignment.Center
            ) {
                if (selectedBitmap != null) {
                    Image(bitmap = selectedBitmap!!.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Tap to upload a clear face photo", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ================= Personal Details Card =================
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Personal Details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Full Name *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = age, onValueChange = { age = it }, label = { Text("Age") }, modifier = Modifier.weight(1f), singleLine = true)

                        var genderExpanded by remember { mutableStateOf(false) }
                        val genderOptions = listOf("Male", "Female", "Other", "Unknown")
                        ExposedDropdownMenuBox(
                            expanded = genderExpanded,
                            onExpandedChange = { genderExpanded = !genderExpanded },
                            modifier = Modifier.weight(1f)
                        ) {
                            OutlinedTextField(
                                value = gender, onValueChange = {}, readOnly = true,
                                label = { Text("Gender") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = genderExpanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth(),
                                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                            )
                            ExposedDropdownMenu(expanded = genderExpanded, onDismissRequest = { genderExpanded = false }) {
                                genderOptions.forEach { option ->
                                    DropdownMenuItem(text = { Text(option) }, onClick = { gender = option; genderExpanded = false })
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = height, onValueChange = { height = it }, label = { Text("Height (cm)") }, modifier = Modifier.weight(1f), singleLine = true)
                        OutlinedTextField(value = weight, onValueChange = { weight = it }, label = { Text("Weight (kg)") }, modifier = Modifier.weight(1f), singleLine = true)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ================= Incident Details Card =================
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Incident Details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(12.dp))

                    Box(modifier = Modifier.fillMaxWidth().clickable { showDatePicker = true }) {
                        OutlinedTextField(
                            value = lastSeenDate, onValueChange = {}, readOnly = true, enabled = false,
                            label = { Text("Last Seen Date *") },
                            trailingIcon = { Icon(Icons.Default.CalendarToday, null) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                disabledBorderColor = MaterialTheme.colorScheme.outline,
                                disabledLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = location, onValueChange = { location = it },
                        label = { Text("Last Seen Location *") },
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
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 2
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ================= Contact Info Card =================
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Contact Information", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(value = contact, onValueChange = { contact = it }, label = { Text("Emergency Contact Number *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
            }

            Spacer(modifier = Modifier.height(30.dp))

            if (isUploading) { Text(progressText, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium); Spacer(modifier = Modifier.height(8.dp)) }

            Button(
                onClick = {
                    if (name.isBlank() || location.isBlank() || lastSeenDate.isBlank() || selectedBitmap == null) { Toast.makeText(context, "Please fill required fields (Photo, Name, Date, Location)", Toast.LENGTH_SHORT).show(); return@Button }
                    isUploading = true
                    scope.launch(Dispatchers.Default) {
                        try {
                            withContext(Dispatchers.Main) { progressText = "🧠 Edge AI is analyzing..." }
                            val yolo = YoloFaceDetector(context)
                            val extractor = MobileFaceNetExtractor(context)
                            try {
                                val faces = yolo.detect(selectedBitmap!!)
                                if (faces.isEmpty()) { 
                                    withContext(Dispatchers.Main) { Toast.makeText(context, "❌ No face detected!", Toast.LENGTH_LONG).show(); isUploading = false }
                                    return@launch 
                                }

                                val crop = ImageUtils.cropFaceWithPadding(selectedBitmap!!, faces[0].boundingBox)
                                if (crop == null) {
                                    withContext(Dispatchers.Main) { Toast.makeText(context, "Failed to crop face, please retry.", Toast.LENGTH_SHORT).show(); isUploading = false }
                                    return@launch
                                }
                                val embeddingList = extractor.extractFeature(crop).map { it.toDouble() }

                                withContext(Dispatchers.Main) { progressText = "🗜️ Compressing image..." }
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
                                withContext(Dispatchers.Main) { progressText = "💾 Saving to Missing Persons DB..." }

                                val currentUserId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""

                                val newMpRef = db.collection("MissingPersons").document()
                                currentMissingPersonId = newMpRef.id

                                val personData = MissingPerson(
                                    id = currentMissingPersonId,
                                    ownerId = currentUserId,
                                    name = name, age = age, gender = gender, height = height, weight = weight,
                                    lastSeenDate = lastSeenDate,
                                    lastSeenLocation = location, 
                                    locationLat = locationLat,
                                    locationLng = locationLng,
                                    contactPhone = contact, photoBase64 = base64Image,
                                    embedding = embeddingList, status = MPStatus.ACTIVE.name
                                )
                                newMpRef.set(personData).await()

                                withContext(Dispatchers.Main) { progressText = "🔍 Searching historical sightings..." }
                                val pendingSightings = db.collection("Sightings").whereEqualTo("status", SightingStatus.PENDING.name).get().await().toObjects(SightingRecord::class.java)

                                val foundMatches = mutableListOf<SightingRecord>()
                                for (sighting in pendingSightings) {
                                    if (sighting.embedding.isNotEmpty()) {
                                        val sim = extractor.calculateSimilarity(sighting.embedding.map { it.toFloat() }.toFloatArray(), embeddingList.map{ it.toFloat() }.toFloatArray())
                                        val conf = extractor.calculateConfidenceScore(sim)
                                        if (conf > 0.80f) {
                                            sighting.aiConfidenceScore = (conf * 100).roundToInt()
                                            foundMatches.add(sighting)
                                        }
                                    }
                                }

                                withContext(Dispatchers.Main) {
                                    isUploading = false
                                    if (foundMatches.isNotEmpty()) {
                                        historicalMatches = foundMatches.sortedByDescending { it.aiConfidenceScore }
                                        showMatchDialog = true
                                    } else {
                                        Toast.makeText(context, "✅ Report Published Successfully!", Toast.LENGTH_LONG).show()
                                        onNavigateBack()
                                    }
                                }
                            } finally {
                                yolo.close()
                                extractor.close()
                            }
                        } catch (e: Exception) { 
                            withContext(Dispatchers.Main) { Toast.makeText(context, "💥 Error: ${e.message}", Toast.LENGTH_LONG).show(); isUploading = false } 
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(55.dp), enabled = !isUploading
            ) {
                if (isUploading) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                else Text("Publish Missing Person", fontSize = MaterialTheme.typography.titleMedium.fontSize)
            }
            Spacer(modifier = Modifier.height(80.dp))
        }
    }

    if (showMatchDialog && historicalMatches.isNotEmpty()) {
        val topMatch = historicalMatches.first()
        val matchBitmap = remember(topMatch.photoBase64) { decodeBase64ToBitmap(topMatch.photoBase64) }

        Dialog(onDismissRequest = { showMatchDialog = false }) {
            Card(modifier = Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("⚠️ Wait! Potential Match Found", color = Color.Red, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Someone reported seeing a similar person previously. Is this them?", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(16.dp))
                    if (matchBitmap != null) Image(bitmap = matchBitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(120.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Date: ${topMatch.sightingDate}")
                    Text("Location: ${topMatch.location}", fontWeight = FontWeight.Bold)
                    Text("AI Match Score: ${topMatch.aiConfidenceScore}%", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(24.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        OutlinedButton(onClick = {
                            showMatchDialog = false
                            Toast.makeText(context, "Report Published. (Match ignored)", Toast.LENGTH_SHORT).show()
                            onNavigateBack()
                        }, modifier = Modifier.weight(1f)) { Text("No") }

                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = {
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val db = FirebaseFirestore.getInstance()
                                    db.runTransaction { transaction ->
                                        val mpRef = db.collection("MissingPersons").document(currentMissingPersonId)
                                        val sgRef = db.collection("Sightings").document(topMatch.id)
                                        
                                        transaction.update(mpRef, "status", MPStatus.PENDING_VERIFICATION.name)
                                        transaction.update(mpRef, "linkedSightingIds", FieldValue.arrayUnion(topMatch.id))
                                        transaction.update(sgRef, "status", SightingStatus.LINKED.name)
                                        transaction.update(sgRef, "linkedMissingPersonId", currentMissingPersonId)

                                        val thanksRef = db.collection("Notifications").document()
                                        val currentUserId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""
                                        val thankYouNote = NotificationRecord(
                                            id = thanksRef.id,
                                            receiverId = topMatch.ownerId, 
                                            senderId = currentUserId,      
                                            title = "🙏 Clue Confirmed!",
                                            message = "A family just matched your orphan sighting to their missing report. Thank you for your help!",
                                            type = "THANK_YOU",
                                            relatedSightingId = topMatch.id,
                                            relatedMissingPersonId = currentMissingPersonId
                                        )
                                        transaction.set(thanksRef, thankYouNote)

                                    }.await()
                                    withContext(Dispatchers.Main) {
                                        showMatchDialog = false
                                        Toast.makeText(context, "✅ Successfully linked & Samaritan notified!", Toast.LENGTH_LONG).show()
                                        onNavigateBack()
                                    }
                                } catch(e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "Error linking: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red), modifier = Modifier.weight(1f)) { Text("Yes, Link It!") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapLocationPickerDialog(
    context: Context,
    onDismiss: () -> Unit,
    onConfirm: (LatLng, String) -> Unit
) {
    var selectedLatLng by remember { mutableStateOf<LatLng?>(null) }
    var addressText by remember { mutableStateOf("Tap on the map to select a location") }
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(3.1390, 101.6869), 6f)
    }
    val scope = rememberCoroutineScope()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Select Location") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.ArrowBack, "Close") }
                    },
                    actions = {
                        TextButton(onClick = { 
                            if(selectedLatLng != null) onConfirm(selectedLatLng!!, addressText) 
                        }, enabled = selectedLatLng != null) {
                            Text("Confirm")
                        }
                    }
                )
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = cameraPositionState,
                    onMapClick = { latLng ->
                        selectedLatLng = latLng
                        scope.launch(Dispatchers.IO) {
                            val geocoder = Geocoder(context, Locale.getDefault())
                            try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1) { addresses ->
                                        val addr = addresses.firstOrNull()?.getAddressLine(0) ?: "${latLng.latitude}, ${latLng.longitude}"
                                        addressText = addr
                                    }
                                } else {
                                    @Suppress("DEPRECATION")
                                    val addresses = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1)
                                    val addr = addresses?.firstOrNull()?.getAddressLine(0) ?: "${latLng.latitude}, ${latLng.longitude}"
                                    addressText = addr
                                }
                            } catch (e: Exception) {
                                addressText = "${latLng.latitude}, ${latLng.longitude}"
                            }
                        }
                    }
                ) {
                    selectedLatLng?.let { Marker(state = MarkerState(position = it)) }
                }
                
                Card(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Text(addressText, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
