package com.example.stockmonitor.analysis

data class EvidenceCard(
    val label: String,
    val value: String?,
    val source: String? = null,
    val quoteDate: String? = null,
    val scope: String? = null,
    val fresh: Boolean = true,
    val available: Boolean = true,
) {
    fun line(): String {
        val text = if (!available || value == null) {
            "$label：暂缺"
        } else {
            "$label：$value${if (fresh) "" else "（已过期）"}"
        }
        val metadata = listOfNotNull(
            source?.takeIf { it.isNotBlank() }?.let { "来源 $it" },
            quoteDate?.takeIf { it.isNotBlank() }?.let { "数据日 ${it.take(10)}" },
            scope?.takeIf { it.isNotBlank() }?.let { "范围 $it" },
        )
        return if (metadata.isEmpty()) text else "$text｜${metadata.joinToString("、")}"
    }

    companion object {
        fun format(cards: List<EvidenceCard>): List<String> = cards.map { it.line() }
    }
}
