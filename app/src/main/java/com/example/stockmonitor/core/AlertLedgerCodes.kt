package com.example.stockmonitor.core

/**
 * 提醒去重账本 → “今天提醒过哪些股票”的解析。
 *
 * 买点/尾盘账本用 `交易日:代码`；通用事件账本是 `交易日:事件:代码`。
 * 只解析当天前缀，历史键不参与。
 */
object AlertLedgerCodes {
    fun codes(today: String, codeKeys: Set<String>?, eventKeys: Set<String>?): Set<String> {
        val prefix = "$today:"
        return buildSet {
            codeKeys.orEmpty().forEach { key ->
                if (key.startsWith(prefix)) add(key.substringAfter(prefix))
            }
            eventKeys.orEmpty().forEach { key ->
                if (key.startsWith(prefix)) add(key.substringAfterLast(':'))
            }
        }.filterTo(linkedSetOf()) { it.isNotBlank() }
    }
}