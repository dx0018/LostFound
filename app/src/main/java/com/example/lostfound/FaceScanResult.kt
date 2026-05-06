package com.example.lostfound

/**
 * 单个人脸的 AI 识别结果
 *
 * @param isMatch       是否匹配上 MissingPerson 数据库里的某个人
 * @param matchedPerson 匹配到的失踪人口对象（未匹配时为 null）
 * @param similarity    Cosine similarity 分数，范围 -1.0 ~ 1.0（实际匹配区间约 0 ~ 1）
 * @param faceFeature   该人脸的 192 维 embedding（用 Double 列表方便存 Firestore）
 */
data class FaceScanResult(
    val isMatch: Boolean = false,
    val matchedPerson: MissingPerson? = null,
    val similarity: Float = 0f,
    val faceFeature: List<Double> = emptyList()
)
