package com.example.stockmonitor

import com.example.stockmonitor.analysis.IntradaySignalState
import com.example.stockmonitor.analysis.IntradaySignalType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntradaySignalStateTest {
    @Test
    fun budgetRejectionDoesNotConsumeSignalState() {
        val state = IntradaySignalState(cooldownMs = 10_000, maxPerDay = 8)

        assertTrue(state.confirmed("sh600000", IntradaySignalType.T_SELL, requiredRounds = 1))
        assertTrue(state.canEmit("sh600000", IntradaySignalType.T_SELL, nowMs = 100_000, risk = false))
        assertTrue(state.canEmit("sh600000", IntradaySignalType.T_SELL, nowMs = 100_000, risk = false))

        state.commit("sh600000", IntradaySignalType.T_SELL, nowMs = 100_000, risk = false)

        assertFalse(state.canEmit("sh600000", IntradaySignalType.T_SELL, nowMs = 100_001, risk = false))
    }

    @Test
    fun nonRiskSignalNeedsConsecutiveConfirmation() {
        val state = IntradaySignalState(cooldownMs = 10_000, maxPerDay = 8)

        assertFalse(state.confirmed("sh600000", IntradaySignalType.T_BUY, requiredRounds = 2))
        assertFalse(
            state.confirmed("sh600000", IntradaySignalType.T_SELL, requiredRounds = 2),
        )
        assertFalse(state.confirmed("sh600000", IntradaySignalType.T_BUY, requiredRounds = 2))
        assertTrue(state.confirmed("sh600000", IntradaySignalType.T_BUY, requiredRounds = 2))
    }

    @Test
    fun confirmedCandidateRemainsReadyWhenDeliveryBudgetRejectsIt() {
        val state = IntradaySignalState(cooldownMs = 10_000, maxPerDay = 8)

        assertFalse(state.confirmed("sh600000", IntradaySignalType.T_BUY, requiredRounds = 2))
        assertTrue(state.confirmed("sh600000", IntradaySignalType.T_BUY, requiredRounds = 2))
        assertTrue(state.confirmed("sh600000", IntradaySignalType.T_BUY, requiredRounds = 2))
        assertTrue(state.canEmit("sh600000", IntradaySignalType.T_BUY, nowMs = 100_000, risk = false))
    }

    @Test
    fun riskSignalConfirmsImmediatelyAndTracksNewRiskType() {
        val state = IntradaySignalState(cooldownMs = 10_000, maxPerDay = 8)

        assertTrue(state.confirmed("sh600000", IntradaySignalType.BREAK_NOTICE, requiredRounds = 1))
        state.commit("sh600000", IntradaySignalType.BREAK_NOTICE, nowMs = 1_000, risk = true)
        assertTrue(state.confirmed("sh600000", IntradaySignalType.REDUCE_POSITION, requiredRounds = 1))
        assertTrue(state.canEmit("sh600000", IntradaySignalType.REDUCE_POSITION, nowMs = 1_001, risk = true))
    }

    @Test
    fun clearingActiveStateRearmsSameSignal() {
        val state = IntradaySignalState(cooldownMs = 10_000, maxPerDay = 8)
        state.confirmed("sh600000", IntradaySignalType.T_SELL, requiredRounds = 1)
        state.commit("sh600000", IntradaySignalType.T_SELL, nowMs = 1_000, risk = false)

        state.clearActive("sh600000")

        assertTrue(state.canEmit("sh600000", IntradaySignalType.T_SELL, nowMs = 11_001, risk = false))
    }

    @Test
    fun riskStageEscalationRearmsWithoutRepeatingSameStage() {
        val state = IntradaySignalState(cooldownMs = 10_000, maxPerDay = 8)
        state.confirmed("sh600000", IntradaySignalType.BREAK_NOTICE, requiredRounds = 1)
        state.commit("sh600000", IntradaySignalType.BREAK_NOTICE, nowMs = 1_000, risk = true, riskStage = 1)

        assertFalse(state.canEmit("sh600000", IntradaySignalType.BREAK_NOTICE, nowMs = 1_001, risk = true, riskStage = 1))
        assertTrue(state.canEmit("sh600000", IntradaySignalType.BREAK_NOTICE, nowMs = 1_001, risk = true, riskStage = 2))

        state.commit("sh600000", IntradaySignalType.BREAK_NOTICE, nowMs = 1_002, risk = true, riskStage = 2)
        assertFalse(state.canEmit("sh600000", IntradaySignalType.BREAK_NOTICE, nowMs = 1_003, risk = true, riskStage = 2))
    }
}
