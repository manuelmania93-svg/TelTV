package com.velastudio.teltv.worker

object IdleCleanupPolicy {
    const val DEFAULT_INTERVAL_HOURS = 4L
    const val DEFAULT_RETENTION_SECONDS = 4L * 60 * 60
    val intervals = listOf(2L, 4L, 8L)
    val retentions = listOf(4L * 60 * 60, 2L * 24 * 60 * 60, 7L * 24 * 60 * 60, 30L * 24 * 60 * 60, Long.MAX_VALUE)
    fun interval(value: Long) = value.takeIf { it in intervals } ?: DEFAULT_INTERVAL_HOURS
    fun retention(value: Long) = value.takeIf { it in retentions } ?: DEFAULT_RETENTION_SECONDS
    fun due(now: Long, last: Long?, hours: Long): Boolean =
        last == null || (now >= last && now - last >= interval(hours) * 3_600_000L)
    fun retentionLabel(value: Long): String = when (retention(value)) {
        14_400L -> "4 hours"
        172_800L -> "2 days"
        604_800L -> "1 week"
        2_592_000L -> "1 month"
        else -> "Never (size limit only)"
    }
}
