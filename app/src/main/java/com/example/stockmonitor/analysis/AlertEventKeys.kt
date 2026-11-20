package com.example.stockmonitor.analysis

import java.util.Locale

/**
 * 盘中提醒的「当日事件身份」。
 *
 * 引擎本身允许同一代码同日多次时，去重键必须带上事件身份，不能只用 `类型:代码`
 * 的每日一次键，否则第 2 次起的提醒（包括“反弹失效/诱多”这类风险事件）会被静默丢弃。
 */
object AlertEventKeys {
    /** 下探回升：同一个坑（低点）只发一次；创更深的新低是新事件。 */
    fun dipRebound(lowPct: Double): String =
        String.format(Locale.CHINA, "dipRebound@%.2f", lowPct)

    /** 反弹跟踪：企稳确认 / 缩量观察 / 抛压 / 诱多失效各自独立去重。 */
    fun reboundTrack(kind: ReboundTracker.Kind): String = "reboundTrack-${kind.name}"
}