package com.qingsong.qishuiautomation

data class ScreenRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    fun contains(x: Int, y: Int): Boolean =
        x in left..right && y in top..bottom
}

data class OcrHit(
    val text: String,
    val centerX: Int,
    val centerY: Int,
)

enum class WaitStatus {
    READY,
    TIMEOUT,
    QUOTA_EXHAUSTED,
    STOPPED,
}

data class WaitResult(
    val status: WaitStatus,
    val claimHit: OcrHit? = null,
)
