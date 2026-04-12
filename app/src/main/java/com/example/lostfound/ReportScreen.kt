package com.example.lostfound

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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
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
import com.google.android.gms.location.LocationServices
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
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
    var contact by remember { mutableStateOf("") }

    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isUploading by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf("") }

    var showMatchDialog by remember { mutableStateOf(false) }
    var historicalMatches by remember { mutableStateOf<List<SightingRecord>>(emptyList()) }
    var currentMissingPersonId by remember { mutableStateOf("") }

    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    val locationPermissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] == true || permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            Toast.makeText(context, "Fetching real GPS location...", Toast.LENGTH_SHORT).show()
            try {
                fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                    if (loc != null) {
                        try {
                            val geocoder = Geocoder(context, Locale.getDefault())
                            val addresses = geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
                            if (!addresses.isNullOrEmpty()) location = addresses[0].getAddressLine(0)
                            else location = "${loc.latitude}, ${loc.longitude}"
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
        imageUri = uri
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

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("📝 Report Missing Person", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))

        if (selectedBitmap != null) Image(bitmap = selectedBitmap!!.asImageBitmap(), contentDescription = null, modifier = Modifier.size(150.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = { galleryLauncher.launch("image/*") }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) {
            Text(if (selectedBitmap == null) "📸 Select Clear Face Photo" else "🔄 Change Photo")
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Full Name *") }, modifier = Modifier.fillMaxWidth())
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = age, onValueChange = { age = it }, label = { Text("Age") }, modifier = Modifier.weight(1f))
            OutlinedTextField(value = gender, onValueChange = { gender = it }, label = { Text("Gender") }, modifier = Modifier.weight(1f))
        }

        OutlinedTextField(
            value = location, onValueChange = { location = it },
            label = { Text("Last Seen Location * (Tap icon for GPS)") },
            trailingIcon = {
                IconButton(onClick = { locationPermissionRequest.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)) }) {
                    Icon(Icons.Default.LocationOn, "Get GPS", tint = MaterialTheme.colorScheme.primary)
                }
            },
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        OutlinedTextField(value = contact, onValueChange = { contact = it }, label = { Text("Emergency Contact *") }, modifier = Modifier.fillMaxWidth())

        Spacer(modifier = Modifier.height(30.dp))

        if (isUploading) { Text(progressText, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium); Spacer(modifier = Modifier.height(8.dp)) }

        Button(
            onClick = {
                if (name.isBlank() || location.isBlank() || selectedBitmap == null) { Toast.makeText(context, "Please fill required fields", Toast.LENGTH_SHORT).show(); return@Button }
                isUploading = true
                scope.launch(Dispatchers.Default) {
                    try {
                        withContext(Dispatchers.Main) { progressText = "🧠 Edge AI is analyzing..." }
                        val yolo = YoloFaceDetector(context); val extractor = MobileFaceNetExtractor(context)
                        val faces = yolo.detect(selectedBitmap!!)
                        if (faces.isEmpty()) { withContext(Dispatchers.Main) { Toast.makeText(context, "❌ No face detected!", Toast.LENGTH_LONG).show(); isUploading = false }; return@launch }

                        val crop = ImageUtils.cropFaceWithPadding(selectedBitmap!!, faces[0].boundingBox) ?: return@launch
                        val embeddingList = extractor.extractFeature(crop).map { it.toDouble() }

                        withContext(Dispatchers.Main) { progressText = "🗜️ Compressing image..." }
                        val scaleRatio = 400f / selectedBitmap!!.width
                        val scaledBitmap = Bitmap.createScaledBitmap(selectedBitmap!!, 400, (selectedBitmap!!.height * scaleRatio).toInt(), true)
                        val baos = ByteArrayOutputStream()

                        // 💡 修复 1：降低压缩率至 50，防止因家属上传原图导致 Firestore 1MB Payload OOM 崩溃
                        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 50, baos)
                        val base64Image = Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT)

                        val db = FirebaseFirestore.getInstance()
                        withContext(Dispatchers.Main) { progressText = "💾 Saving to Missing Persons DB..." }

                        val currentUserId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""

                        // 💡 修复 2：原子化生成 ID 并单次保存，取代之前容易产生脏数据的双重写入
                        val newMpRef = db.collection("MissingPersons").document()
                        currentMissingPersonId = newMpRef.id

                        val personData = MissingPerson(
                            id = currentMissingPersonId,
                            ownerId = currentUserId,
                            name = name, age = age, gender = gender, height = height, weight = weight,
                            lastSeenLocation = location, contactPhone = contact, photoBase64 = base64Image,
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
                                // 💡 配合新的严谨置信度算法，这里只需 > 0.80f 即可放行
                                if (conf > 0.80f) {
                                    sighting.aiConfidenceScore = (conf * 100).roundToInt()
                                    foundMatches.add(sighting)
                                }
                            }
                        }
                        yolo.close(); extractor.close()

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
                    } catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(context, "💥 Error: ${e.message}", Toast.LENGTH_LONG).show(); isUploading = false } }
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp), enabled = !isUploading
        ) {
            if (isUploading) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
            else Text("Publish Missing Person")
        }
        Spacer(modifier = Modifier.height(80.dp))
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
                                val db = FirebaseFirestore.getInstance()
                                db.runTransaction { transaction ->
                                    val mpRef = db.collection("MissingPersons").document(currentMissingPersonId)
                                    val sgRef = db.collection("Sightings").document(topMatch.id)
                                    val currentLinked = (transaction.get(mpRef).get("linkedSightingIds") as? List<String>) ?: emptyList()

                                    transaction.update(mpRef, "status", MPStatus.PENDING_VERIFICATION.name)
                                    transaction.update(mpRef, "linkedSightingIds", currentLinked + topMatch.id)
                                    transaction.update(sgRef, "status", SightingStatus.LINKED.name)
                                    transaction.update(sgRef, "linkedMissingPersonId", currentMissingPersonId)

                                    // 💡 修复 3：构建闭环！家属确认匹配孤立线索后，立刻派发感谢信给当初的好心人
                                    val thanksRef = db.collection("Notifications").document()
                                    val currentUserId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""
                                    val thankYouNote = NotificationRecord(
                                        id = thanksRef.id,
                                        receiverId = topMatch.ownerId, // 接收者：当年上传孤立线索的路人
                                        senderId = currentUserId,      // 发送者：当前家属
                                        title = "🙏 Clue Confirmed!",
                                        message = "A family just matched your orphan sighting to their missing report. Thank you for your help!",
                                        type = "THANK_YOU"
                                    )
                                    transaction.set(thanksRef, thankYouNote)

                                }.await()
                                withContext(Dispatchers.Main) {
                                    showMatchDialog = false
                                    Toast.makeText(context, "✅ Successfully linked & Samaritan notified!", Toast.LENGTH_LONG).show()
                                    onNavigateBack()
                                }
                            }
                        }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red), modifier = Modifier.weight(1f)) { Text("Yes, Link It!") }
                    }
                }
            }
        }
    }
}