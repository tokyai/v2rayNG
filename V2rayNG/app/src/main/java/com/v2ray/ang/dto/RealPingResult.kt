package com.v2ray.ang.dto

import java.io.Serializable

/** 测试进程发给界面的节点结果；仅测延迟时不改变已有下载速度。 */
data class RealPingResult(
    val guid: String,
    val delayMillis: Long,
    val speedBytesPerSecond: Long? = null,
) : Serializable {
    companion object {
        const val SPEED_UNSUPPORTED = -2L
    }
}
