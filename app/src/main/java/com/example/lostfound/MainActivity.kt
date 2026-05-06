package com.example.lostfound

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.lostfound.ui.theme.LostFoundTheme
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.firestoreSettings
import com.google.firebase.firestore.persistentCacheSettings

class MainActivity : ComponentActivity() {

    private var notificationPermissionGranted = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        notificationPermissionGranted = isGranted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setupFirestoreOffline()
        SystemNotificationUtils.createNotificationChannel(this)
        askNotificationPermission()

        setContent {
            LostFoundTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RootNavigation(
                        notificationPermissionGranted = notificationPermissionGranted,
                        activity = this
                    )
                }
            }
        }
    }

    private fun setupFirestoreOffline() {
        try {
            val settings = firestoreSettings {
                setLocalCacheSettings(
                    persistentCacheSettings {
                        setSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
                    }
                )
            }
            FirebaseFirestore.getInstance().firestoreSettings = settings
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            notificationPermissionGranted = granted

            if (!granted) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            notificationPermissionGranted = true
        }
    }
}

@Composable
fun RootNavigation(
    notificationPermissionGranted: Boolean,
    activity: MainActivity
) {
    val rootNavController = rememberNavController()
    val auth = remember { FirebaseAuth.getInstance() }

    var authReady by remember { mutableStateOf(false) }
    var currentUser by remember { mutableStateOf<FirebaseUser?>(null) }

    DisposableEffect(auth) {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            currentUser = firebaseAuth.currentUser
            authReady = true
        }
        auth.addAuthStateListener(listener)
        onDispose {
            auth.removeAuthStateListener(listener)
        }
    }

    LaunchedEffect(currentUser?.uid, notificationPermissionGranted) {
        val uid = currentUser?.uid
        if (uid.isNullOrBlank()) {
            SystemNotificationUtils.stopListening()
        } else if (notificationPermissionGranted) {
            SystemNotificationUtils.startListeningForNotifications(activity, uid)
        }
    }

    if (!authReady) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val startDestination = if (currentUser != null) "main" else "login"

    NavHost(
        navController = rootNavController,
        startDestination = startDestination
    ) {
        composable("login") {
            LoginScreen(navController = rootNavController)
        }

        composable("register") {
            RegisterScreen(navController = rootNavController)
        }

        composable("setup_profile") {
            SetupProfileScreen(navController = rootNavController)
        }

        composable("main") {
            val user = currentUser
            if (user == null) {
                LaunchedEffect(Unit) {
                    rootNavController.navigate("login") {
                        popUpTo(0) { inclusive = true }
                    }
                }
            } else {
                MainScreen(
                    currentUserId = user.uid,
                    userEmail = user.email.orEmpty(),
                    onLogout = {
                        auth.signOut()
                        SystemNotificationUtils.stopListening()
                        rootNavController.navigate("login") {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                )
            }
        }
    }
}
