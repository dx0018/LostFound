package com.example.lostfound

// ==========================================
// 1. 状态机定义 (State Machine Enums)
// ==========================================

/** Missing Person (主案件) 的生命周期状态 */
enum class MPStatus {
    ACTIVE,                 // 🟢 寻找中
    PENDING_VERIFICATION,   // 🟡 核实线索中 (系统匹配到高度相似的 Sighting)
    FOUND,                  // ✅ 已寻回 (结案)
    CANCELLED               // ⚪ 已取消 (误报)
}

/** Sighting (目击线索) 的生命周期状态 */
enum class SightingStatus {
    PENDING,                // 🔵 待匹配 (孤立线索，未被家属认领)
    LINKED,                 // 🟣 已关联 (家属确认是本人，此时路人失去撤销权限)
    REJECTED,               // ❌ 无效线索 (家属否认，或路人自行撤销)
    RESOLVED                // 🏆 协助结案 (主案件变更为 FOUND 时自动触发)
}


// ==========================================
// 2. 数据表定义 (Firestore Collections)
// ==========================================

/**
 * 集合 A: MissingPersons (主案件库 - 由家属/警方发布)
 */
data class MissingPerson(
    var id: String = "",
    // 发布者信息 (未来关联 Chat/Notification 用)
    val ownerId: String = "",

    // 基础资料
    val name: String = "",
    val age: String = "",
    val gender: String = "",
    val height: String = "",
    val weight: String = "",
    val clothingDescription: String = "",
    val lastSeenLocation: String = "",
    val contactPhone: String = "",

    // 核心 AI 与媒体
    val photoBase64: String = "",
    val embedding: List<Double> = emptyList(),

    // 状态与时间线
    var status: String = MPStatus.ACTIVE.name,
    val timestamp: Long = System.currentTimeMillis(),

    // 🔗 核心逻辑：记录有哪些 Sighting 成功关联到了这个案件
    var linkedSightingIds: List<String> = emptyList()
)

/**
 * 集合 B: Sightings (目击线索库 - 由路人发布)
 */
data class SightingRecord(
    var id: String = "",
    // 发布者信息
    val ownerId: String = "",

    // 目击情报 (全部为非必填的模糊描述)
    val location: String = "",          // 比如 "Near UTM Skudai" (后续接入 GPS)
    val estimatedFeatures: String = "", // 比如 "大概 160cm，很瘦"
    val clothingAppearance: String = "",// 比如 "红色帽子"

    // 核心 AI 与媒体
    val photoBase64: String = "",
    val embedding: List<Double> = emptyList(),

    // 状态与时间线
    var status: String = SightingStatus.PENDING.name,
    val timestamp: Long = System.currentTimeMillis(),

    // 🔗 核心逻辑：如果被认领，记录属于哪个主案件；如果未认领，此项为空
    var linkedMissingPersonId: String? = null,

    // AI 初步评估的相似度 (辅助家属判断)
    var aiConfidenceScore: Int = 0
)

// 💡 通知消息的数据模型
data class NotificationRecord(
    val id: String = "",
    val receiverId: String = "", // 接收者 UID（比如家属）
    val senderId: String = "", // 发送者 UID（拍到照片的路人）
    val title: String = "", // 标题，比如 "Potential Match Found!"
    val message: String = "", // 详细内容，包含位置和时间
    val photoBase64: String = "", // 附带路人拍的照片，方便家属立刻确认
    val relatedSightingId: String = "", // 关联的线索 ID
    val relatedMissingPersonId: String = "", // 关联的失踪者 ID
    val type: String = "SIGHTING_MATCH", // 通知类型
    val isRead: Boolean = false, // 是否已读
    val timestamp: Long = System.currentTimeMillis()
)

data class UserProfile(
    val uid: String = "",
    val email: String = "",
    val name: String = "",
    val phone: String = "",
    val profilePicBase64: String = ""
)