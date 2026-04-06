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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onLogout: () -> Unit = {}) {
    val navController = rememberNavController()
    // 💡 替换为你设计的三个核心功能栏
    val bottomBarItems = listOf(Screen.Home, Screen.AddAction, Screen.Alerts)

    var showBottomSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
                                // 💡 落实 Point 1：强制跳转并清空所有中间页面的状态！
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = false // 👈 绝对不保存状态，保证输入框被清空
                                    }
                                    launchSingleTop = true
                                    restoreState = false // 👈 不恢复历史状态，永远展现全新的页面
                                }
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(navController, startDestination = Screen.Home.route, Modifier.padding(innerPadding)) {

            // 📍 1. Explore 地图主页
            composable(Screen.Home.route) {
                // 💡 2. 把跳转到 Profile 的权力交给 HomeScreen
                HomeScreen(onNavigateToProfile = { navController.navigate(Screen.Profile.route) })
            }

            // 📍 2. Alerts 通知页
            composable(Screen.Alerts.route) {
                AlertsScreen()
            }

            // --- 隐藏的子页面 ---
            composable(Screen.Report.route) {
                ReportScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(Screen.Sighting.route) {
                SightingScreen(onNavigateBack = { navController.popBackStack() })
            }

            composable(Screen.Profile.route) {
                ProfileScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onLogout = onLogout // 把退出指令传给 Profile 页面
                )
            }
        }

        // 🎨 弹出式底部菜单
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