package com.example.stockmonitor

import com.example.stockmonitor.analysis.*
import com.example.stockmonitor.core.GuardedAction
import com.example.stockmonitor.core.GuardedActionLevel
import com.example.stockmonitor.core.IntradayVolumeContext
import com.example.stockmonitor.core.TimeframeEvidence
import com.example.stockmonitor.ui.MonitoringCopy
import org.junit.Assert.*
import org.junit.Test

class TradeNotificationTest {
    @Test fun estimatesPartialSalesWithoutRoundingUpOrInventingSellableShares() {
        assertEquals(300.0, TradeNotification.sellQuantity(1000.0, false), 0.0)
        assertEquals(100.0, TradeNotification.sellQuantity(500.0, false), 0.0)
        assertEquals(0.0, TradeNotification.sellQuantity(200.0, false), 0.0)
        assertEquals(0.0, TradeNotification.sellQuantity(Double.NaN, false), 0.0)
        assertEquals(0.0, TradeNotification.sellQuantity(-100.0, false), 0.0)
        assertEquals(1000.0, TradeNotification.sellQuantity(1050.0, true), 0.0)
        assertEquals(0.0, TradeNotification.sellQuantity(50.0, true), 0.0)
        assertTrue(
            TradeNotification.exit(HoldingExitStage.EXIT, 50.0, 18.0, 19.0)
                .contains("不足100股"),
        )
    }

    @Test fun sellQuantityIsCappedByTodaysSellableShares() {
        assertEquals(100.0, TradeNotification.sellQuantity(600.0, 500.0, false), 0.0)
        assertEquals(500.0, TradeNotification.sellQuantity(600.0, 500.0, true), 0.0)
    }

    @Test fun riskCopySeparatesSameDayLockedSharesFromSellableShares() {
        val text = TradeNotification.exit(
            HoldingExitStage.REDUCE,
            totalQuantity = 600.0,
            sellableQuantity = 500.0,
            price = 18.8,
            support = 19.0,
            lockedToday = 100.0,
        )

        assertTrue(text.contains("原有可卖500股"))
        assertTrue(text.contains("今日新增100股受T+1锁定"))
        assertFalse(text.contains("卖出约600"))
    }

    @Test fun sameDayOnlyRiskSaysStopAddingAndDeferDisposal() {
        val text = TradeNotification.exit(
            HoldingExitStage.REDUCE,
            totalQuantity = 100.0,
            sellableQuantity = 0.0,
            price = 18.8,
            support = 19.0,
            lockedToday = 100.0,
        )

        assertTrue(text.contains("停止加仓"))
        assertTrue(text.contains("下一交易日复核处置"))
        assertFalse(text.contains("卖出"))
    }

    @Test fun saleTextDistinguishesRiskExitFromSmallTPosition() {
        val partial = TradeNotification.exit(HoldingExitStage.REDUCE, 1000.0, 18.8, 19.0)
        assertTrue(partial, partial.contains("300股"))
        assertTrue(partial, partial.contains("估算") && partial.contains("实际可卖"))
        assertFalse(partial.contains("基本面"))
        assertTrue(partial.length <= 120)
        val small = TradeNotification.exit(HoldingExitStage.REDUCE, 200.0, 18.8, 19.0)
        assertFalse(small.contains("卖出约200"))
        assertTrue(small.contains("不足100股"))
        val exit = TradeNotification.exit(HoldingExitStage.EXIT, 1050.0, 18.0, 19.0)
        assertTrue(exit.contains("1000股"))
        assertTrue(exit.contains("退出"))
    }

    @Test fun everyHoldingNotificationIsBriefAndOmitsFundamentalProse() {
        val input = IntradayTactics.Input("sz000001", "测试", 20.0, 3.0, 1.5, 10.0,
            19.0, 21.0, 3, 1000.0, 22.0, 100000.0, 20000.0, 5.0, 20.0)
        IntradaySignalType.values().forEach { type ->
            val text = TradeNotification.intraday(input, IntradaySignal(type, "测试", "基本面亮红灯：旧长文"))
            assertFalse("$type: $text", text.contains("基本面") || text.contains("旧长文"))
            assertTrue("$type: $text", text.length <= 120)
            assertTrue("$type: $text", text.contains("观察") || text.contains("风险"))
        }
        val buy = TradeNotification.intraday(input, IntradaySignal(IntradaySignalType.T_BUY, "", ""))
        assertTrue(buy.contains("仅观察"))
        assertTrue(buy.contains("行情交易日期"))
        assertFalse(buy.contains("买入约"))
    }

    @Test fun intradayNotificationUsesGuardedTQuantity() {
        val input = IntradayTactics.Input("sz000001", "测试", 20.0, 3.0, 1.5, 10.0,
            19.0, 21.0, 3, 1000.0, 22.0, 100000.0, 20000.0, 5.0, 20.0)
            .copy(
                tradeDate = "2026-10-23",
                sellableQuantity = 600.0,
                timeframeEvidence = TimeframeEvidence(
                    m15Confirmations = 1,
                    m30Aligned = true,
                    complete = true,
                ),
            )
        val text = TradeNotification.intraday(input, IntradaySignal(IntradaySignalType.T_SELL, "", ""))
        assertTrue(text.contains("最多减300股"))
        assertTrue(text.contains("T+1"))
    }

    @Test fun intradayRiskCopyUsesLedgerAvailabilityInsteadOfTotalPosition() {
        val input = IntradayTactics.Input(
            "sz000001", "测试", 18.8, -3.0, 1.5, 10.0,
            19.0, 21.0, 3, 600.0, 20.0, 100000.0, 20000.0, 5.0, 20.0,
        ).copy(
            sellableQuantity = 500.0,
            lockedQuantity = 100.0,
            tradeDate = "2026-10-23",
            timeframeEvidence = TimeframeEvidence(
                riskComplete = true,
                riskConfirmed = true,
                riskConfirmations = 2,
            ),
        )

        val text = TradeNotification.intraday(
            input,
            IntradaySignal(IntradaySignalType.REDUCE_POSITION, "确认破位·分批减仓", ""),
        )

        assertTrue(text.contains("首批最多减100股"))
        assertTrue(text.contains("T+1"))
        assertFalse(text.contains("600股"))
    }

    @Test fun pureObservationAndNoticeDoNotBecomeActionableIntradayActions() {
        val input = IntradayTactics.Input(
            "sz000001", "测试", 18.8, -3.0, 1.5, 10.0,
            19.0, 21.0, 3, 600.0, 20.0, 100000.0, 20000.0, 5.0, 20.0,
        ).copy(tradeDate = "2026-10-23")

        val deep = TradeNotification.intradayGuarded(
            input,
            IntradaySignal(
                IntradaySignalType.BREAK_SUPPORT,
                "深套风险观察",
                "",
                holdingExitStage = HoldingExitStage.DEEP_T,
            ),
        )
        val notice = TradeNotification.intradayGuarded(
            input,
            IntradaySignal(
                IntradaySignalType.BREAK_NOTICE,
                "破位风险提示",
                "",
                holdingExitStage = HoldingExitStage.NOTICE,
            ),
        )
        val trend = TradeNotification.intradayGuarded(
            input,
            IntradaySignal(IntradaySignalType.TREND_WATCH, "趋势条件待确认", ""),
        )

        assertEquals(GuardedActionLevel.OBSERVATION, deep.level)
        assertEquals(GuardedActionLevel.RISK_FACT, notice.level)
        assertEquals(GuardedActionLevel.OBSERVATION, trend.level)
        assertEquals(GuardedAction.HOLD, deep.action)
        assertEquals(GuardedAction.HOLD, notice.action)
        assertEquals(GuardedAction.HOLD, trend.action)
    }

    @Test fun legacyNoticeCopyDoesNotGenerateDeterministicSellQuantity() {
        val text = TradeNotification.exit(
            HoldingExitStage.NOTICE,
            totalQuantity = 1000.0,
            sellableQuantity = 900.0,
            price = 18.8,
            support = 19.0,
        )

        assertTrue(text.contains("破位风险提示"))
        assertTrue(text.contains("去留自定"))
        assertFalse(text.contains("股"))
    }

    @Test fun reduceStyleIntradaySignalsNeverUpgradeToFullExit() {
        val input = IntradayTactics.Input(
            "sz000001", "测试", 18.8, -3.0, 1.5, 10.0,
            19.0, 21.0, 3, 1000.0, 20.0, 100000.0, 20000.0, 5.0, 20.0,
        ).copy(
            tradeDate = "2026-10-23",
            sellableQuantity = 900.0,
            timeframeEvidence = TimeframeEvidence(
                riskComplete = true,
                riskConfirmed = true,
                riskConfirmations = 3,
                riskLevel = DailyRiskLevel.CRITICAL,
            ),
        )

        listOf(
            IntradaySignalType.REDUCE_POSITION,
            IntradaySignalType.HIGH_REDUCE_POSITION,
            IntradaySignalType.BREAK_SUPPORT,
            IntradaySignalType.BREAKOUT_REVERSAL_RISK,
        ).forEach { type ->
            val guarded = TradeNotification.intradayGuarded(input, IntradaySignal(type, "", ""))
            assertEquals(type.name, GuardedAction.RISK_REDUCE, guarded.action)
            assertEquals(type.name, 300.0, guarded.maxSellQuantity, 0.0)
        }
    }

    @Test fun timeframeWatchUsesExplicitReasonButNeverEchoesOldActionBody() {
        val input = IntradayTactics.Input(
            "sz000001", "测试", 20.0, 3.0, 1.5, 10.0,
            19.0, 21.0, 3, 1000.0, 22.0, 100000.0, 20000.0, 5.0, 20.0,
        )

        val text = TradeNotification.intraday(
            input,
            IntradaySignal(IntradaySignalType.TIMEFRAME_WATCH, "测试｜仅观察", "旧的可执行股数：卖出300股"),
            timeframeReason = "30分钟方向向下，仅观察",
        )

        assertTrue(text.contains("30分钟方向向下"))
        assertTrue(text.contains("不执行买入"))
        assertFalse(text.contains("卖出300股"))
    }

    @Test fun reclaimCopyStatesPriceAndExecutionConsequenceInsteadOfCancelWord() {
        val input = IntradayTactics.Input(
            "sz000001", "测试", 20.0, 3.0, 1.5, 10.0,
            19.0, 21.0, 3, 1000.0, 22.0, 100000.0, 20000.0, 5.0, 20.0,
        ).copy(
            tradeDate = "2026-10-23",
            reentryCapacityQuantity = 300.0,
            timeframeEvidence = TimeframeEvidence(
                m15Confirmations = 2,
                m30Aligned = true,
                complete = true,
                clearanceConfirmed = true,
            ),
            volumeContext = IntradayVolumeContext(
                marketExpanding = true,
                stockVolumeConfirming = true,
                barVolumeConfirming = true,
            ),
        )

        val text = TradeNotification.intraday(
            input,
            IntradaySignal(IntradaySignalType.RECLAIM_SUPPORT, "测试·站回支撑", ""),
        )

        assertTrue(text.contains("失效价19.00"))
        assertTrue(text.contains("跌破即停止执行"))
        assertFalse(text.contains("取消"))
    }

    @Test fun monitoringCopyExplainsT1DualTimeframeAndLeaderReferenceBoundary() {
        val text = MonitoringCopy.rules().joinToString(" ") { it.label + it.text }

        assertTrue(text.contains("T+1"))
        assertTrue(text.contains("15分钟"))
        assertTrue(text.contains("30分钟"))
        assertTrue(text.contains("板块状态参考"))
        assertTrue(text.contains("非买卖指令"))
    }

    /** 回归：手工加仓没记批次的残股不能说成「今天买入的」，它不会自动解锁。 */
    @Test fun untrackedLockedSharesAreNotCalledTodaysBuys() {
        val text = TradeNotification.exit(
            HoldingExitStage.REDUCE,
            totalQuantity = 300.0,
            sellableQuantity = 0.0,
            price = 18.8,
            support = 19.0,
            lockedToday = 300.0,
            untrackedQuantity = 300.0,
        )

        assertTrue(text.contains("未记录买入日期"))
        assertTrue(text.contains("补记买入日期"))
        assertFalse(text.contains("今天买入的"))
        assertFalse(text.contains("今日新增"))
        assertFalse(text.contains("下一交易日复核处置"))
    }

    @Test fun mixedLockedCopyNamesBothSourcesSeparately() {
        val text = TradeNotification.exit(
            HoldingExitStage.REDUCE,
            totalQuantity = 700.0,
            sellableQuantity = 400.0,
            price = 18.8,
            support = 19.0,
            lockedToday = 300.0,
            untrackedQuantity = 200.0,
        )

        assertTrue(text.contains("原有可卖400股"))
        assertTrue(text.contains("今日新增100股"))
        assertTrue(text.contains("未记录买入日期200股"))
    }

    @Test fun intradayT1BlockedCopyDistinguishesUntrackedFromTodaysBuy() {
        val input = IntradayTactics.Input(
            "sz000001", "测试", 18.8, -3.0, 1.5, 10.0,
            19.0, 21.0, 3, 300.0, 20.0, 100000.0, 20000.0, 5.0, 20.0,
        ).copy(
            sellableQuantity = 0.0,
            lockedQuantity = 300.0,
            untrackedQuantity = 300.0,
            ledgerConsistent = true,
            tradeDate = "2026-10-23",
            timeframeEvidence = TimeframeEvidence(
                m15Confirmations = 1,
                m30Aligned = true,
                complete = true,
            ),
        )

        val text = TradeNotification.intraday(input, IntradaySignal(IntradaySignalType.T_SELL, "", ""))

        assertTrue(text.contains("未记录买入日期"))
        assertFalse(text.contains("今天买入的"))
    }
}
