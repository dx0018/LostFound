package com.example.lostfound

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.lostfound.ui.theme.LostFoundTheme
import com.google.firebase.auth.FirebaseAuth

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LostFoundTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // 👈 启动全局总开关
                    RootNavigation()
                }
            }
        }
    }
}

@Composable
fun RootNavigation() {
    val rootNavController = rememberNavController()
    // 获取 Firebase 的身份验证实例
    val auth = remember { FirebaseAuth.getInstance() }

    // 💡 核心神仙逻辑：系统自动判断当前是否有记住的用户
    // 如果 currentUser 不是 null，说明以前登录过，直接跳去主页 "main"
    // 如果是 null，说明是第一次来或者退出了，去登录页 "login"
    val startDestination = if (auth.currentUser != null) "main" else "login"

    // 这是 App 最外层的大路由
    NavHost(navController = rootNavController, startDestination = startDestination) {

        // 1. 登录页
        composable("login") {
            LoginScreen(navController = rootNavController)
        }

        // 2. 注册页
        composable("register") {
            RegisterScreen(navController = rootNavController)
        }

        composable("setup_profile") {
            SetupProfileScreen(navController = rootNavController)
        }

        // 3. 我们之前写好的主架构
        composable("main") {
            MainScreen(
                onLogout = {
                    auth.signOut() // 1. 让 Firebase 退出登录
                    rootNavController.navigate("login") { // 2. 跳回登录页
                        popUpTo(0) { inclusive = true } // 3. 彻底清空所有历史页面（防止按返回键又回到主页）
                    }
                }
            )
        }


    }
}