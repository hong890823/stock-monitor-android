package com.example.stockmonitor.core

import java.io.ByteArrayInputStream
import java.time.Instant
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

object TechNewsFeedParser {
    private val rfc1123Patterns = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm:ss z",
        "EEE, dd MMM yy HH:mm:ss Z",
    )

    fun parse(text: String, profile: NewsSourceProfile): List<NewsItem> {
        val document = runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isXIncludeAware = false
                isExpandEntityReferences = false
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            factory.newDocumentBuilder().apply {
                setErrorHandler(
                    object : DefaultHandler() {
                        override fun error(error: SAXParseException) = throw error
                        override fun fatalError(error: SAXParseException) = throw error
                    },
                )
            }.parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
        }.getOrElse { error -> throw IllegalArgumentException("新闻 Feed 不是有效 XML", error) }

        val entries = buildList {
            addAll(document.getElementsByTagName("item").elements())
            addAll(document.getElementsByTagName("entry").elements())
        }
        return entries.mapNotNull { entry ->
            val title = childText(entry, "title")?.trim().orEmpty()
            val publishedAtMs = parseTimestamp(
                childText(entry, "pubDate") ?: childText(entry, "published") ?: childText(entry, "updated"),
            )
            if (title.isBlank() || publishedAtMs <= 0L) {
                null
            } else {
                NewsItem(
                    title = title,
                    publishedAtMs = publishedAtMs,
                    source = childText(entry, "source")?.trim()?.takeIf { it.isNotBlank() } ?: profile.label,
                    url = entryLink(entry),
                    rawCategory = childText(entry, "category")?.trim(),
                    sourceId = profile.id,
                    track = profile.track,
                    trustScore = profile.trustScore,
                )
            }
        }
    }

    private fun org.w3c.dom.NodeList.elements(): List<Element> =
        (0 until getLength()).mapNotNull { item(it) as? Element }

    private fun childText(element: Element, name: String): String? {
        for (index in 0 until element.childNodes.length) {
            val child = element.childNodes.item(index)
            val childName = (child as? Element)?.let { it.localName ?: it.nodeName }?.substringAfter(':')
            if (childName == name) {
                return child.textContent?.trim()?.takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private fun entryLink(element: Element): String? {
        var fallback: String? = null
        for (index in 0 until element.childNodes.length) {
            val child = element.childNodes.item(index)
            if (child !is Element) continue
            val childName = (child.localName ?: child.nodeName).substringAfter(':')
            if (childName != "link") continue
            val href = child.getAttribute("href")?.trim().orEmpty()
            val value = href.ifBlank { child.textContent?.trim().orEmpty() }
            if (value.isBlank()) continue
            val relation = child.getAttribute("rel").lowercase(Locale.ROOT)
            if (relation.isBlank() || relation == "alternate") return normalizeUrl(value)
            if (fallback == null) fallback = value
        }
        return normalizeUrl(fallback)
    }

    private fun parseTimestamp(value: String?): Long {
        val text = value?.trim().orEmpty()
        if (text.isBlank()) return 0L
        text.toLongOrNull()?.let { raw ->
            return when {
                raw >= 100_000_000_000L -> raw
                raw > 0 -> raw * 1_000L
                else -> 0L
            }
        }
        runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()?.let { return it }
        rfc1123Patterns.forEach { pattern ->
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.ENGLISH).apply {
                    timeZone = TimeZone.getTimeZone("GMT")
                    isLenient = false
                }.parse(text)
            }.getOrNull()
            if (parsed != null) return parsed.time
        }
        return 0L
    }

    private fun normalizeUrl(value: String?): String? {
        val text = value?.trim().orEmpty()
        return when {
            text.isBlank() -> null
            text.startsWith("//") -> "https:$text"
            else -> text
        }
    }
}
