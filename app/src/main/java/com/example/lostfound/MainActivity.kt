package com.example.lostfound

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.lostfound.ui.theme.LostFoundTheme
import com.google.firebase.auth.FirebaseAuth

class MainActivity : ComponentActivity() {

    // 🚨 申请 Android 13+ 通知权限
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Permission granted, start listening
            SystemNotificationUtils.startListeningForNotifications(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 初始化系统通知渠道 (Android 8.0+)
        SystemNotificationUtils.createNotificationChannel(this)

        // 2. 检查权限并启动通知监听器
        askNotificationPermission()

        setContent {
            LostFoundTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RootNavigation(onLoginSuccess = {
                        // 登录成功后重新启动监听（防止之前没监听）
                        SystemNotificationUtils.startListeningForNotifications(this)
                    })
                }
            }
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                SystemNotificationUtils.startListeningForNotifications(this)
            } else {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            SystemNotificationUtils.startListeningForNotifications(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 可选：如果不希望后台监听，可以 stop。但为了满足你的"后台通知"需求，我们可以让它继续存活一段时间
        // SystemNotificationUtils.stopListening()
    }
}

@Composable
fun RootNavigation(onLoginSuccess: () -> Unit = {}) {
    val rootNavController = rememberNavController()
    val auth = remember { FirebaseAuth.getInstance() }

    val startDestination = if (auth.currentUser != null) {
        onLoginSuccess() // 启动时已登录，直接监听
        "main"
    } else "login"

    NavHost(navController = rootNavController, startDestination = startDestination) {

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
            MainScreen(
                onLogout = {
                    auth.signOut() 
                    SystemNotificationUtils.stopListening() // 退出登录时停止监听
                    rootNavController.navigate("login") { 
                        popUpTo(0) { inclusive = true } 
                    }
                }
            )
        }
    }
}
