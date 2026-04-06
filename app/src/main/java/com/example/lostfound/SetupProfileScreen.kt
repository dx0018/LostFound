package com.example.lostfound

import android.graphics.Bitmap
import android.graphics.ImageDecoder
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@Composable
fun SetupProfileScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val auth = remember { FirebaseAuth.getInstance() }

    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    // 💡 照片选择器
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, _, _ -> decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE; decoder.isMutableRequired = true }
            } else {
                @Suppress("DEPRECATION") MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }
            withContext(Dispatchers.Main) { selectedBitmap = bitmap }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))
        Text("Complete Your Profile", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("We need a few details to verify your identity.", color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(modifier = Modifier.height(32.dp))

        // 🖼️ 头像区 (选填)
        Box(
            modifier = Modifier.size(120.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).clickable { galleryLauncher.launch("image/*") },
            contentAlignment = Alignment.Center
        ) {
            if (selectedBitmap != null) {
                Image(bitmap = selectedBitmap!!.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(40.dp), tint = Color.Gray)
                    Text("Add Photo", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
        }
        Text("(Optional) Tap to add photo", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))

        Spacer(modifier = Modifier.height(32.dp))

        // 📝 必填资料区
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Full Name *") },
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = phone, onValueChange = { phone = it },
            label = { Text("Phone Number *") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )

        Spacer(modifier = Modifier.weight(1f))

        // 💾 保存按钮
        Button(
            onClick = {
                if (name.isBlank() || phone.isBlank()) {
                    Toast.makeText(context, "Name and Phone are required!", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                isSaving = true
                scope.launch(Dispatchers.IO) {
                    try {
                        val user = auth.currentUser ?: throw Exception("User not logged in")
                        var base64Image = ""

                        // 如果选了照片，就压缩并转成 Base64
                        if (selectedBitmap != null) {
                            val scaleRatio = 300f / selectedBitmap!!.width
                            val scaledBitmap = Bitmap.createScaledBitmap(selectedBitmap!!, 300, (selectedBitmap!!.height * scaleRatio).toInt(), true)
                            val baos = ByteArrayOutputStream()
                            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 70, baos)
                            base64Image = Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT)
                        }

                        // 组装数据并存入 Firebase
                        val userProfile = UserProfile(
                            uid = user.uid,
                            email = user.email ?: "",
                            name = name,
                            phone = phone,
                            profilePicBase64 = base64Image
                        )
                        FirebaseFirestore.getInstance().collection("Users").document(user.uid).set(userProfile).await()

                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Profile Saved!", Toast.LENGTH_SHORT).show()
                            // 💡 保存成功，正式进入主页！
                            navController.navigate("main") { popUpTo("setup_profile") { inclusive = true } }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            isSaving = false
                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            enabled = !isSaving
        ) {
            if (isSaving) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
            else Text("Save & Continue", fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(32.dp))
    }
}