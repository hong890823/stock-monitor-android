package com.example.stockmonitor

import org.junit.Assert.assertTrue

/**
 * 报告排版检查工具。
 *
 * 报告用「一、二、三……」分节，每节最后一行要给出「结论：」。这条约定靠测试锁住：
 * 以后新增一节忘了写结论会直接测试失败，而不是等人翻到报告末尾才发现某节只堆了数字。
 */
object ReportSectionAssertions {

    private val HEADER = Regex("^[一二三四五六七八九十]+、")

    /** 按章节标题切分，返回「标题 → 该节正文行」。 */
    fun sections(report: String): Map<String, List<String>> {
        val sections = linkedMapOf<String, MutableList<String>>()
        var current: String? = null
        report.lines().forEach { line ->
            if (HEADER.containsMatchIn(line)) {
                current = line
                sections[line] = mutableListOf()
            } else if (current != null) {
                sections[current]!!.add(line)
            }
        }
        return sections
    }

    /**
     * 每节至少有一行以「结论」开头；标题本身就叫「……综合结论」的那一节例外，
     * 它的正文是一串「· xxx」，标题已经说明了这是什么。
     *
     * 同时要求节数不少于 [leastSections]：标题写坏了会让两节并成一节，
     * 并进去的那节照样能通过「有结论」的检查，只有数节数才发现得了。
     */
    fun assertEverySectionHasConclusion(report: String, leastSections: Int) {
        val sections = sections(report)
        assertTrue(
            "章节数不应少于 $leastSections，实际 ${sections.size} 节：${sections.keys}",
            sections.size >= leastSections,
        )
        sections.forEach { (title, body) ->
            val conclusive = title.contains("综合结论") || body.any { it.startsWith("结论") }
            assertTrue("$title 缺少结论行", conclusive)
        }
    }
}
