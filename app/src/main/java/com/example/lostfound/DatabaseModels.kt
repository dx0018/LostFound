package com.example.lostfound

// ==========================================
// 1. 状态机定义 (State Machine Enums)
// ==========================================

enum class MPStatus {
    ACTIVE,
    PENDING_VERIFICATION,
    FOUND,
    CANCELLED
}

enum class SightingStatus {
    PENDING,
    LINKED,
    REJECTED,
    RESOLVED
}

// ==========================================
// 2. 数据表定义 (Firestore Collections)
// ==========================================

/**
 * 集合 A: MissingPersons (主案件库 - 由家属/警方发布)
 */
data class MissingPerson(
    var id: String = "",
    val ownerId: String = "",

    // 基础资料
    val name: String = "",
    val age: String = "",
    val gender: String = "",
    val height: String = "",
    val weight: String = "",
    val clothingDescription: String = "",

    val lastSeenDate: String = "",
    val lastSeenLocation: String = "",
    val locationLat: Double? = null,
    val locationLng: Double? = null,

    val contactPhone: String = "",

    // 🆕 升级：Storage URL 替代 Base64
    val photoUrl: String = "",
    // 🆕 保留 Storage 路径，方便删除时清理
    val photoStoragePath: String = "",
    val thumbnailUrl: String = "",
    val thumbnailStoragePath: String = "",

    val embedding: List<Double> = emptyList(),

    var status: String = MPStatus.ACTIVE.name,
    val timestamp: Long = System.currentTimeMillis(),

    var linkedSightingIds: List<String> = emptyList()
)

/**
 * 集合 B: Sightings (目击线索库 - 由路人发布)
 */
data class SightingRecord(
    var id: String = "",
    val ownerId: String = "",

    val sightingDate: String = "",
    val location: String = "",
    val locationLat: Double? = null,
    val locationLng: Double? = null,

    val estimatedFeatures: String = "",
    val clothingAppearance: String = "",

    val photoUrl: String = "",
    val photoStoragePath: String = "",
    val thumbnailUrl: String = "",
    val thumbnailStoragePath: String = "",
    val matchedFaceUrl: String = "",
    val matchedFaceStoragePath: String = "",

    val embedding: List<Double> = emptyList(),

    var status: String = SightingStatus.PENDING.name,
    val timestamp: Long = System.currentTimeMillis(),

    var linkedMissingPersonId: String? = null,

    var aiConfidenceScore: Int = 0,

    var matchLevel: String = "NO_MATCH"
)

data class NotificationRecord(
    val id: String = "",
    val receiverId: String = "",
    val senderId: String = "",
    val title: String = "",
    val message: String = "",
    val photoUrl: String = "",
    val thumbnailUrl: String = "",
    val matchedFaceUrl: String = "",
    val relatedSightingId: String = "",
    val relatedMissingPersonId: String = "",
    val type: String = "SIGHTING_MATCH",
    val isRead: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

data class UserProfile(
    val uid: String = "",
    val email: String = "",
    val name: String = "",
    val phone: String = "",
    // 🆕 升级
    val profilePicUrl: String = ""
)
