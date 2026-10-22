package com.example.stockmonitor.analysis

class IntradaySignalState(
    private val cooldownMs: Long,
    private val maxPerDay: Int,
) {
    private data class PendingSignal(val type: IntradaySignalType, val rounds: Int)

    private val activeByCode = mutableMapOf<String, IntradaySignalType>()
    private val pendingByCode = mutableMapOf<String, PendingSignal>()
    private val lastAtByKey = mutableMapOf<String, Long>()
    private val countByCode = mutableMapOf<String, Int>()

    fun retainCodes(codes: Set<String>) {
        activeByCode.keys.retainAll(codes)
        pendingByCode.keys.retainAll(codes)
    }

    fun clear() {
        activeByCode.clear()
        pendingByCode.clear()
        lastAtByKey.clear()
        countByCode.clear()
    }

    fun clearActive(code: String) {
        activeByCode.remove(code)
    }

    fun clearPending(code: String) {
        pendingByCode.remove(code)
    }

    fun clearCode(code: String) {
        activeByCode.remove(code)
        pendingByCode.remove(code)
    }

    fun confirmed(code: String, type: IntradaySignalType, requiredRounds: Int): Boolean {
        val pending = pendingByCode[code]
        val rounds = if (pending?.type == type) pending.rounds + 1 else 1
        if (rounds < requiredRounds) {
            pendingByCode[code] = PendingSignal(type, rounds)
            return false
        }
        pendingByCode[code] = PendingSignal(type, rounds)
        return true
    }

    fun canEmit(code: String, type: IntradaySignalType, nowMs: Long, risk: Boolean): Boolean {
        if (activeByCode[code] == type) return false
        if (risk) return true
        val key = signalKey(code, type)
        return nowMs - (lastAtByKey[key] ?: 0L) >= cooldownMs &&
            (countByCode[code] ?: 0) < maxPerDay
    }

    fun commit(code: String, type: IntradaySignalType, nowMs: Long, risk: Boolean) {
        activeByCode[code] = type
        pendingByCode.remove(code)
        lastAtByKey[signalKey(code, type)] = nowMs
        if (!risk) countByCode[code] = (countByCode[code] ?: 0) + 1
    }

    private fun signalKey(code: String, type: IntradaySignalType): String = "$code|${type.name}"
}
