package com.example.stockmonitor

import com.example.stockmonitor.analysis.*
import com.example.stockmonitor.core.DailyRiskLevel
import com.example.stockmonitor.core.GuardedAction
import com.example.stockmonitor.core.GuardedActionLevel
import com.example.stockmonitor.core.IntradayVolumeContext
import com.example.stockmonitor.core.TimeframeEvidence
import com.example.stockmonitor.ui.MonitoringCopy
import org.junit.Assert.*
import org.junit.Test

class TradeNotificationTest {
    @Test
    fun sellEstimateIncludesCostPnlRecoveryAndRemainingPosition() {
        val estimate = TradeNotification.estimateSell(
            holdingQuantity = 1_000.0,
            averageCost = 10.0,
            price = 12.0,
            quantity = 300.0,
            feeRate = 0.001,
            minimumFee = 5.0,
        )!!

        assertEquals(10.0, estimate.referenceCost, 0.000001)
        assertEquals(300.0, estimate.quantity, 0.000001)
        assertEquals(5.0, estimate.estimatedFee, 0.000001)
        assertEquals(595.0, estimate.estimatedRealizedPnl, 0.000001)
        assertEquals(3_595.0, estimate.recoveredFunds, 0.000001)
        assertEquals(700.0, estimate.remainingQuantity, 0.000001)
        assertTrue(TradeNotification.sellEstimateText(estimate).contains("参考成本 10.00元"))
        assertTrue(TradeNotification.sellEstimateText(estimate).contains("卖后剩余 700股"))
    }

    @Test
    fun invalidSellEstimateDoesNotInventNumbers() {
        assertNull(
            TradeNotification.estimateSell(
                holdingQuantity = 100.0,
                averageCost = 10.0,
                price = 12.0,
                quantity = 101.0,
            ),
        )
    }
    // 卖量取整/封顶/T+1 语义随 TradeNotification.exit() 一并删除（旧短信文案路径已无生产调用），
    // 数量与文案断言迁移到 core/TradeActionGuardTest（现有生产路径）。

    @Test fun notificationsDoNotEchoUntrustedSignalBodyOrFundamentalProse() {
        val input = IntradayTactics.Input("sz000001", "测试", 20.0, 3.0, 1.5, 10.0,
            19.0, 21.0, 3, 1000.0, 22.0, 100000.0, 20000.0, 5.0, 20.0)
        IntradaySignalType.values().forEach { type ->
            val text = TradeNotification.intraday(input, IntradaySignal(type, "测试", "基本面亮红灯：旧长文"))
            assertFalse("$type: $text", text.contains("基本面") || text.contains("旧长文"))
            if (type == IntradaySignalType.EXIT_POSITION) {
                assertTrue(text.contains("重大风险待复核"))
                assertFalse(text.contains("分批离场"))
            }
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
        assertTrue(text.contains("仅观察"))
        assertTrue(text.contains("做T预计净收益未通过双边费用、滑点及最低收益门槛校验"))
        assertFalse(text.contains("最多减"))
    }

    @Test fun intradayRiskCopyUsesLedgerAvailabilityInsteadOfTotalPosition() {
        val input = IntradayTactics.Input(
            "sz000001", "测试", 18.8, -3.0, 1.5, 10.0,
            19.0, 21.0, 3, 600.0, 20.0, 100000.0, 20000.0, 5.0, 20.0,
        ).copy(
            sellableQuantity = 500.0,
            lockedQuantity = 100.0,
            positionAllocation = com.example.stockmonitor.core.PositionAllocationConfig.normal(300.0),
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

    // NOTICE「去留自定、不生成确定性卖量」的语义由
    // pureObservationAndNoticeDoNotBecomeActionableIntradayActions 在生产路径（intradayGuarded）上覆盖。

    @Test fun reduceStyleIntradaySignalsNeverUpgradeToFullExit() {
        val input = IntradayTactics.Input(
            "sz000001", "测试", 18.8, -3.0, 1.5, 10.0,
            19.0, 21.0, 3, 1000.0, 20.0, 100000.0, 20000.0, 5.0, 20.0,
        ).copy(
            tradeDate = "2026-10-23",
            sellableQuantity = 900.0,
            positionAllocation = com.example.stockmonitor.core.PositionAllocationConfig.normal(700.0),
            ledgerConsistent = true,
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
            assertEquals(type.name, 100.0, guarded.maxSellQuantity, 0.0)
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

        assertTrue(text.contains("仅观察"))
        assertTrue(text.contains("接回尚未通过支撑、MA5/MA20、短周期、市场板块及完成15分钟K的回踩阶段量能确认"))
        assertFalse(text.contains("可接回约"))
    }

    @Test fun monitoringCopyExplainsT1DualTimeframeAndLeaderReferenceBoundary() {
        val text = MonitoringCopy.rules().joinToString(" ") { it.label + it.text }

        assertTrue(text.contains("T+1"))
        assertTrue(text.contains("15分钟"))
        assertTrue(text.contains("30分钟"))
        assertTrue(text.contains("板块状态参考"))
        assertTrue(text.contains("非买卖指令"))
        assertTrue(text.contains("重大风险待复核"))
        assertTrue(text.contains("不自动清仓"))
        assertFalse(text.contains("结构失效时退出"))
    }

    // 「锁定残股不能说成今天买入」与「混同锁定要分开点名」的断言迁移到
    // core/TradeActionGuardTest（生产路径 TradeActionGuard.nextDay 的同一口径文案）。
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

    @Test fun riskReminderKeepsCostRecoveryAndConfirmedAllocationSeparateFromTechnicalRisk() {
        val input = IntradayTactics.Input(
            "sh600111", "北方稀土", 32.31, -6.0, 1.5, 10.0,
            34.09, 37.38, -7, 600.0, 50.80, 100_000.0, 20_000.0, 5.0, 20.0,
        ).copy(
            tradeDate = "2026-11-17",
            sellableQuantity = 600.0,
            positionAllocation = com.example.stockmonitor.core.PositionAllocationConfig.normal(400.0),
            ledgerConsistent = true,
            timeframeEvidence = TimeframeEvidence(riskComplete = true, riskConfirmed = true, riskConfirmations = 2),
        )
        val decision = TradeNotification.intradayGuarded(
            input, IntradaySignal(IntradaySignalType.REDUCE_POSITION, "确认破位", ""),
        )
        assertTrue(decision.text.contains("成本参考50.80"))
        assertTrue(decision.text.contains("距离回本参考价"))
        assertTrue(decision.text.contains("底仓目标400股"))
        assertTrue(decision.text.contains("技术风险不等于持有价值判断"))
        assertTrue(decision.text.contains("不代表已成交"))
        assertTrue(decision.maxSellQuantity <= 200.0)
    }

    @Test fun unsetAllocationIsNotRenderedAsAnAcceptedZeroCorePlan() {
        val input = IntradayTactics.Input(
            "sh600111", "北方稀土", 32.31, -6.0, 1.5, 10.0,
            34.09, 37.38, -7, 600.0, 50.80, 100_000.0, 20_000.0, 5.0, 20.0,
        ).copy(tradeDate = "2026-11-17", timeframeEvidence = TimeframeEvidence(riskConfirmations = 2))
        val decision = TradeNotification.intradayGuarded(
            input, IntradaySignal(IntradaySignalType.REDUCE_POSITION, "确认破位", ""),
        )
        assertTrue(decision.text.contains("分仓尚未配置"))
        assertFalse(decision.text.contains("底仓目标0股"))
        assertEquals(GuardedActionLevel.OBSERVATION, decision.level)
    }
}
