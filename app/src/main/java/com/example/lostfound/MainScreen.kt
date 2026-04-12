package com.example.lostfound

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onLogout: () -> Unit = {}) {
    val navController = rememberNavController()
    val bottomBarItems = listOf(Screen.Home, Screen.AddAction, Screen.Alerts)

    var showBottomSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 🚨 模块 3：全局未读消息状态监听
    var hasUnreadAlerts by remember { mutableStateOf(false) }
    val currentUserId = FirebaseAuth.getInstance().currentUser?.uid

    DisposableEffect(currentUserId) {
        if (currentUserId == null) return@DisposableEffect onDispose {}

        // 挂载全局监听器，只筛选 receiverId，避免触发 Firebase 复合索引拦截
        val listener = FirebaseFirestore.getInstance().collection("Notifications")
            .whereEqualTo("receiverId", currentUserId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener

                if (snapshot != null) {
                    // 在本地内存中查找是否存在 isRead == false 的文档
                    hasUnreadAlerts = snapshot.documents.any { doc ->
                        doc.getBoolean("isRead") == false
                    }
                }
            }
        onDispose { listener.remove() }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                bottomBarItems.forEach { screen ->
                    NavigationBarItem(
                        icon = {
                            if (screen == Screen.AddAction) {
                                Icon(screen.icon, contentDescription = screen.title, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                            } else if (screen == Screen.Alerts) {
                                // 💡 引入 Material 3 的 BadgedBox 显示小红点
                                BadgedBox(
                                    badge = {
                                        if (hasUnreadAlerts) {
                                            Badge() // 空 Badge 就是一个小红点；如果里面加 Text("1") 就会显示数字
                                        }
                                    }
                                ) {
                                    Icon(screen.icon, contentDescription = screen.title)
                                }
                            } else {
                                Icon(screen.icon, contentDescription = screen.title)
                            }
                        },
                        label = { if (screen != Screen.AddAction) Text(screen.title) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            if (screen == Screen.AddAction) {
                                showBottomSheet = true
                            } else {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = false
                                    }
                                    launchSingleTop = true
                                    restoreState = false
                                }
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(navController, startDestination = Screen.Home.route, Modifier.padding(innerPadding)) {

            composable(Screen.Home.route) {
                HomeScreen(
                    onNavigateToProfile = { navController.navigate(Screen.Profile.route) },
                    // 💡 新增：把前往时间线的路书传给 HomeScreen
                    onNavigateToTimeline = { id ->
                        navController.navigate(Screen.CaseTimeline.createRoute(id))
                    }
                )
            }

            composable(Screen.Report.route) {
                ReportScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(Screen.Sighting.route) {
                SightingScreen(onNavigateBack = { navController.popBackStack() })
            }

            composable(Screen.Profile.route) {
                ProfileScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onLogout = onLogout
                )
            }
            // 📍 2. Alerts 通知页 (修改这一段)
            composable(Screen.Alerts.route) {
                AlertsScreen(
                    onNavigateToTimeline = { id ->
                        navController.navigate(Screen.CaseTimeline.createRoute(id))
                    }
                )
            }

            // ... 其他隐藏子页面保持原样 ...

            // 📍 3. 新增：案件时间线 (放到 NavHost 里的任何位置即可)
            composable(Screen.CaseTimeline.route) { backStackEntry ->
                // 解析传进来的 missingPersonId
                val missingPersonId = backStackEntry.arguments?.getString("id") ?: ""
                CaseTimelineScreen(
                    missingPersonId = missingPersonId,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }

        // --- Bottom Sheet 保留原样 ---
        if (showBottomSheet) {
            ModalBottomSheet(onDismissRequest = { showBottomSheet = false }, sheetState = sheetState) {
                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp, start = 16.dp, end = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("What would you like to do?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(24.dp))

                    Card(
                        onClick = { showBottomSheet = false; navController.navigate(Screen.Report.route) },
                        modifier = Modifier.fillMaxWidth().height(80.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Row(modifier = Modifier.fillMaxSize().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(32.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text("📝 Report Missing Person", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("Upload photo and details to database", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Card(
                        onClick = { showBottomSheet = false; navController.navigate(Screen.Sighting.route) },
                        modifier = Modifier.fillMaxWidth().height(80.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Row(modifier = Modifier.fillMaxSize().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(32.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text("🔍 Report Sighting (AI Scan)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("Use camera to match faces in the cloud", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}