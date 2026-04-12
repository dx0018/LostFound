package com.example.lostfound

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 密封类：定义 App 的页面路由与图标
 */
sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    // ⬇️ 底部导航栏常驻的 3 个按钮
    object Home : Screen("home", "Home", Icons.Default.Home)
    object AddAction : Screen("add_action", "Publish", Icons.Default.AddCircle) // 这是一个假路由，用来触发弹窗
    object Alerts : Screen("alerts", "Alerts", Icons.Default.Notifications)

    // ⬇️ 隐藏的子页面（通过弹窗进入）
    object Report : Screen("report", "Report Missing", Icons.Default.Edit)
    object Sighting : Screen("sighting", "Report Sighting", Icons.Default.Search)

    object Profile : Screen("profile", "Profile", Icons.Default.Person)

    // ... 你的其他 Screen ...

    // 💡 新增：时间线页面（带 ID 参数）
    object CaseTimeline : Screen("case_timeline/{id}", "Case Timeline", Icons.Default.Info) {
        // 辅助函数：用于生成跳转时的具体 URL
        fun createRoute(id: String) = "case_timeline/$id"
    }
}