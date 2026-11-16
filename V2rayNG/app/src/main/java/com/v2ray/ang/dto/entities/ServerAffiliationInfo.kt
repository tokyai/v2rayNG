package com.v2ray.ang.dto.entities

data class ServerAffiliationInfo(
    var testDelayMillis: Long = 0L,
    // 下载速度按字节/秒保存；0 表示未测，-1 表示失败，旧记录缺少该字段时仍为未测。
    var testSpeedBytesPerSecond: Long = 0L,
    // 标记本轮测试，防止取消或重测后旧任务覆盖新结果。
    var testRequestId: String = "",
)
