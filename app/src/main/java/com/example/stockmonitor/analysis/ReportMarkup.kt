package com.example.stockmonitor.analysis

/**
 * 报告里的重点标注。
 *
 * 报告本体是纯文本：要能整段复制、分享，也要能在单测里直接比对字符串，所以这里不用 Span，
 * 而是用一对标记把关键数字括起来，由 UI 层决定怎么显示——展示时染成红色，
 * 复制/分享前用 [plain] 去掉标记，粘到聊天窗口里还是一条干净文本。
 */
object ReportMarkup {

    const val OPEN = "«"
    const val CLOSE = "»"

    /** 把一段文本标记成「关键数据」。 */
    fun red(value: String): String = OPEN + value + CLOSE

    /** 只给估值文本中的价格、区间、比例和倍数等数字加标记。 */
    fun highlightValuationNumbers(text: String): String {
        if (text.isBlank() || text.contains(OPEN) || text.contains(CLOSE)) return text
        val pattern = Regex(
            "(?<![A-Za-z0-9])[-+]?\\d+(?:[.,]\\d+)?" +
                "(?:\\s*(?:~|～|至|-)\\s*[-+]?\\d+(?:[.,]\\d+)?)?" +
                "(?:\\s*(?:%|％|倍|元|美元|港元|人民币|USD|HKD|CNY))?",
            RegexOption.IGNORE_CASE,
        )
        return pattern.replace(text) { match ->
            val value = match.value
            if (value.matches(Regex("\\d{4}-\\d{1,2}-\\d{1,2}"))) value else red(value)
        }
    }

    /** 去掉标记，得到可直接复制、分享的纯文本。 */
    fun plain(text: String): String = text.replace(OPEN, "").replace(CLOSE, "")

    /** 一段文本；[highlight] 为 true 表示它是被标红的关键数据。 */
    data class Segment(val text: String, val highlight: Boolean)

    /** 按标记切段。标记不成对时把后半段整体当重点处理，避免显示出半截标记。 */
    fun parse(text: String): List<Segment> {
        val segments = mutableListOf<Segment>()
        var index = 0
        while (index < text.length) {
            val open = text.indexOf(OPEN, index)
            if (open < 0) {
                segments.add(Segment(text.substring(index), false))
                break
            }
            if (open > index) segments.add(Segment(text.substring(index, open), false))
            val close = text.indexOf(CLOSE, open + OPEN.length)
            if (close < 0) {
                segments.add(Segment(text.substring(open + OPEN.length), true))
                break
            }
            segments.add(Segment(text.substring(open + OPEN.length, close), true))
            index = close + CLOSE.length
        }
        return segments
    }
}
