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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.android.gms.location.LocationServices
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.roundToInt

data class FaceScanResult(
    val isMatch: Boolean,
    val matchedPerson: MissingPerson?,
    val confidence: Int,
    val faceFeature: List<Double>
)

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

    // 🔒 自动上传防重锁：防止 Compose 重绘导致连续发几十条通知
    var hasAutoUploaded by remember { mutableStateOf(false) }

    var sightingLocation by remember { mutableStateOf("") }
    var estimatedFeatures by remember { mutableStateOf("") }
    var clothingAppearance by remember { mutableStateOf("") }

    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    val locationPermissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] == true || permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            Toast.makeText(context, "Fetching real GPS location...", Toast.LENGTH_SHORT).show()
            try {
                fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                    if (location != null) {
                        try {
                            val geocoder = Geocoder(context, Locale.getDefault())
                            val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                            if (!addresses.isNullOrEmpty()) {
                                sightingLocation = addresses[0].getAddressLine(0)
                            } else {
                                sightingLocation = "${location.latitude}, ${location.longitude}"
                            }
                        } catch (e: Exception) {
                            sightingLocation = "${location.latitude}, ${location.longitude}"
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

    // 重置状态与防重锁
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap: Bitmap? ->
        if (bitmap != null) { selectedBitmap = bitmap; resultBitmap = null; scanResults = emptyList(); statusText = "Image loaded."; hasAutoUploaded = false }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, _, _ -> decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE; decoder.isMutableRequired = true }
            } else {
                @Suppress("DEPRECATION") MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }
            withContext(Dispatchers.Main) { selectedBitmap = bitmap; resultBitmap = null; scanResults = emptyList(); statusText = "Image loaded."; hasAutoUploaded = false }
        }
    }

    // ==========================================
    // 🚨 核心逻辑：AI 匹配成功后，自动触发安全批量上传
    // ==========================================
    LaunchedEffect(scanResults) {
        val matchResult = scanResults.firstOrNull { it.isMatch && it.matchedPerson != null }
        if (matchResult != null && !hasAutoUploaded && selectedBitmap != null) {
            hasAutoUploaded = true
            isUploading = true

            try {
                // 1. 强力压缩：JPEG 50 防止超 Firestore 1MB 限制
                val scaleRatio = 400f / selectedBitmap!!.width
                val scaledBitmap = Bitmap.createScaledBitmap(selectedBitmap!!, 400, (selectedBitmap!!.height * scaleRatio).toInt(), true)
                val baos = ByteArrayOutputStream()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 50, baos)
                val base64Image = Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT)

                val db = FirebaseFirestore.getInstance()
                val currentUserId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""
                val batch = db.batch()

                val newSightingRef = db.collection("Sightings").document()
                val matchedMP = matchResult.matchedPerson!!

                // 2. 存入 Sighting
                val sightingData = SightingRecord(
                    id = newSightingRef.id,
                    ownerId = currentUserId,
                    location = sightingLocation.ifBlank { "Location not provided" },
                    estimatedFeatures = estimatedFeatures,
                    clothingAppearance = clothingAppearance,
                    photoBase64 = base64Image,
                    embedding = matchResult.faceFeature,
                    status = SightingStatus.PENDING.name,
                    linkedMissingPersonId = matchedMP.id,
                    aiConfidenceScore = matchResult.confidence
                )
                batch.set(newSightingRef, sightingData)

                // 3. 准备通知
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

                // 4. 更新主案件状态并加入关联 ID
                val mpRef = db.collection("MissingPersons").document(matchedMP.id)
                batch.update(mpRef, "status", MPStatus.PENDING_VERIFICATION.name)
                batch.update(mpRef, "linkedSightingIds", FieldValue.arrayUnion(sightingData.id))

                // 5. 原子化执行
                batch.commit().await()

                isUploading = false
                Toast.makeText(context, "✅ Match detected! Tip auto-sent to family.", Toast.LENGTH_LONG).show()
                onNavigateBack() // 自动退回上一页

            } catch (e: Exception) {
                isUploading = false
                hasAutoUploaded = false // 如果断网失败，允许下次重新触发
                Toast.makeText(context, "Auto-upload failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🔍 Report Sighting", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))

        Box(modifier = Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            val displayBitmap = resultBitmap ?: selectedBitmap
            if (displayBitmap != null) Image(bitmap = displayBitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
            else Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant) { Box(contentAlignment = Alignment.Center) { Text("No Image Selected") } }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            OutlinedButton(onClick = { galleryLauncher.launch("image/*") }, modifier = Modifier.weight(1f), enabled = !isProcessing && !isUploading) { Text("🖼️ Gallery") }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(onClick = { cameraLauncher.launch(null) }, modifier = Modifier.weight(1f), enabled = !isProcessing && !isUploading) { Text("📸 Camera") }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Context Details (Optional)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = sightingLocation, onValueChange = { sightingLocation = it },
                    label = { Text("Location (Tap icon for Real GPS)") },
                    trailingIcon = {
                        IconButton(onClick = {
                            locationPermissionRequest.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
                        }) { Icon(Icons.Default.LocationOn, "Get GPS", tint = MaterialTheme.colorScheme.primary) }
                    },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = estimatedFeatures, onValueChange = { estimatedFeatures = it }, label = { Text("Est. Height/Age") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = clothingAppearance, onValueChange = { clothingAppearance = it }, label = { Text("Clothing") }, modifier = Modifier.weight(1f), singleLine = true)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
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
                            drawList.add(ImageUtils.MatchInfo(faceBox.boundingBox, isMatch, if(isMatch) "${bestMatch!!.name} $percentage%" else "Unknown"))
                        }

                        val finalImg = ImageUtils.drawBoundingBoxes(selectedBitmap!!, drawList)
                        withContext(Dispatchers.Main) {
                            resultBitmap = finalImg
                            scanResults = currentResults.distinctBy { it.matchedPerson?.id ?: it.faceFeature.hashCode() }
                            isProcessing = false; statusText = "✅ Scan Complete! Found ${faces.size} face(s)."
                        }
                        yolo.close(); extractor.close()
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { statusText = "💥 Error: ${e.message}"; isProcessing = false }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(55.dp), enabled = !isProcessing && selectedBitmap != null && !isUploading
        ) {
            if (isProcessing) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
            else Text("Run AI Recognition", fontWeight = FontWeight.Bold)
        }

        if (scanResults.isNotEmpty()) {
            Spacer(modifier = Modifier.height(24.dp))
            Text("AI Analysis Results", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)

            scanResults.forEach { result ->
                val cardColor = if (result.isMatch) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = cardColor)) {
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
                                    onClick = { context.startActivity(Intent(Intent.ACTION_DIAL).apply { data = Uri.parse("tel:${result.matchedPerson?.contactPhone}") }) },
                                    modifier = Modifier.background(Color.White, shape = RoundedCornerShape(50))
                                ) { Icon(Icons.Default.Call, "Call", tint = Color.Green) }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        if (result.isMatch) {
                            // 匹配成功时：按钮禁用并显示自动发送状态
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
                            // 未匹配时：保留手动上传功能，存入孤立线索库
                            Button(
                                onClick = {
                                    isUploading = true
                                    scope.launch(Dispatchers.Default) {
                                        try {
                                            val scaleRatio = 400f / selectedBitmap!!.width
                                            val scaledBitmap = Bitmap.createScaledBitmap(selectedBitmap!!, 400, (selectedBitmap!!.height * scaleRatio).toInt(), true)
                                            val baos = ByteArrayOutputStream()
                                            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 50, baos) // 同样使用 50 压缩率
                                            val base64Image = Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT)

                                            val db = FirebaseFirestore.getInstance()
                                            val currentUserId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""
                                            val sightingData = SightingRecord(
                                                id = db.collection("Sightings").document().id,
                                                ownerId = currentUserId,
                                                location = sightingLocation.ifBlank { "Location not provided" },
                                                estimatedFeatures = estimatedFeatures, clothingAppearance = clothingAppearance,
                                                photoBase64 = base64Image, embedding = result.faceFeature,
                                                status = SightingStatus.PENDING.name,
                                                linkedMissingPersonId = null,
                                                aiConfidenceScore = result.confidence
                                            )

                                            // 手动上传孤立线索不涉及通知派发
                                            db.collection("Sightings").document(sightingData.id).set(sightingData).await()

                                            withContext(Dispatchers.Main) {
                                                isUploading = false
                                                Toast.makeText(context, "✅ Saved to Orphan Clue Database", Toast.LENGTH_LONG).show()
                                                onNavigateBack()
                                            }
                                        } catch (e: Exception) {
                                            withContext(Dispatchers.Main) { isUploading = false; Toast.makeText(context, "Upload failed: ${e.message}", Toast.LENGTH_SHORT).show() }
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                enabled = !isUploading
                            ) {
                                if (isUploading) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                                else {
                                    Icon(Icons.Default.Send, null, modifier = Modifier.size(18.dp)); Spacer(modifier = Modifier.width(8.dp))
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