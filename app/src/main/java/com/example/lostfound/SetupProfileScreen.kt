package com.example.lostfound

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
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
import androidx.compose.material.icons.filled.ArrowBack
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
import coil.compose.AsyncImage
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

@Composable
fun SetupProfileScreen(
    navController: NavController,
    onProfileCreated: () -> Unit = {}
) {
    RequireAuth { user ->
        SetupProfileScreenContent(
            navController = navController,
            currentUser = user,
            onProfileCreated = onProfileCreated
        )
    }
}

@Composable
private fun SetupProfileScreenContent(
    navController: NavController,
    currentUser: FirebaseUser,
    onProfileCreated: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var profilePicUrl by remember { mutableStateOf("") }
    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var isInitialLoading by remember { mutableStateOf(true) }

    LaunchedEffect(currentUser.uid) {
        try {
            val doc = FirebaseFirestore.getInstance("lostfound")
                .collection("Users")
                .document(currentUser.uid)
                .get()
                .await()
            if (doc.exists()) {
                name = doc.getString("name").orEmpty()
                phone = doc.getString("phone").orEmpty()
                profilePicUrl = doc.getString("profilePicUrl").orEmpty()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isInitialLoading = false
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
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "Image load failed: ${e.message ?: "Unknown error"}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    if (isInitialLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    val canNavigateBack = navController.previousBackStackEntry != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (canNavigateBack) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (canNavigateBack) "Edit Profile Info" else "Complete Your Profile",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = if (canNavigateBack) "Update your profile details below." else "We need a few details to verify your identity.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { galleryLauncher.launch("image/*") },
            contentAlignment = Alignment.Center
        ) {
            if (selectedBitmap != null) {
                Image(
                    bitmap = selectedBitmap!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else if (profilePicUrl.isNotBlank()) {
                AsyncImage(
                    model = profilePicUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = Color.Gray
                    )
                    Text(
                        text = "Add Photo",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            }
        }

        Text(
            text = "(Optional) Tap to change photo",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Full Name *") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it },
            label = { Text("Phone Number *") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = {
                if (name.isBlank() || phone.isBlank()) {
                    Toast.makeText(
                        context,
                        "Name and Phone are required!",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@Button
                }

                isSaving = true

                scope.launch(Dispatchers.IO) {
                    var uploadedPath: String? = null

                    try {
                        var currentPicUrl = profilePicUrl

                        if (selectedBitmap != null) {
                            val (url, storagePath) = StorageRepository.uploadBitmap(
                                bitmap = selectedBitmap!!,
                                folder = "profile_pics",
                                userId = currentUser.uid,
                                maxDim = 400,
                                quality = 80
                            )
                            currentPicUrl = url
                            uploadedPath = storagePath
                        }

                        val userProfile = UserProfile(
                            uid = currentUser.uid,
                            email = currentUser.email.orEmpty(),
                            name = name,
                            phone = phone,
                            profilePicUrl = currentPicUrl
                        )

                        FirebaseFirestore.getInstance("lostfound")
                            .collection("Users")
                            .document(currentUser.uid)
                            .set(userProfile)
                            .await()

                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                "Profile Saved!",
                                Toast.LENGTH_SHORT
                            ).show()

                             if (canNavigateBack) {
                                 navController.popBackStack()
                             } else {
                                 onProfileCreated()
                                 navController.navigate("main") {
                                     popUpTo("setup_profile") {
                                         inclusive = true
                                     }
                                 }
                             }
                        }
                    } catch (e: Exception) {
                        uploadedPath?.let {
                            StorageRepository.deleteByPath(it)
                        }

                        withContext(Dispatchers.Main) {
                            isSaving = false
                            Toast.makeText(
                                context,
                                "Error saving profile: ${e.message ?: "Unknown error"}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            enabled = !isSaving
        ) {
            if (isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text(
                    text = if (canNavigateBack) "Save Changes" else "Save & Continue",
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}
