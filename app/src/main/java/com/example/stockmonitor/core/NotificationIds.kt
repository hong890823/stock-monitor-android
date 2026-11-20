package com.example.stockmonitor.core

import java.util.zip.CRC32

/**
 * 个股类通知 ID：`类型前缀 × 100000 + CRC32(代码) % 100000`。
 *
 * 原实现用 `code.hashCode()` 直接加前缀：不同代码可能撞同一 ID 互相覆盖，
 * 且前缀间距只有 200，哈希进位后会跨到相邻类型。CRC32 稳定、跨进程一致，
 * 10 万桶也远大于实际持仓与观察池规模。
 */
object NotificationIds {
    fun of(prefix: Int, key: String): Int {
        require(prefix >= 0) { "通知 ID 前缀不能为负：$prefix" }
        val crc = CRC32().apply { update(key.toByteArray(Charsets.UTF_8)) }.value
        val raw = prefix.toLong() * BUCKET_SIZE + crc % BUCKET_SIZE
        // 前缀 × 10 万必须落在 Int 内；当前最大前缀 8600，留出足够余量后再拒绝，避免静默溢出撞 ID。
        require(raw <= Int.MAX_VALUE) { "通知 ID 前缀过大：$prefix" }
        return raw.toInt()
    }

    private const val BUCKET_SIZE = 100_000L
}
