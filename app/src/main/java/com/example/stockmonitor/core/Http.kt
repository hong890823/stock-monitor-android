package com.example.stockmonitor.core

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/**
 * 极简 HTTP 客户端：只依赖 HttpURLConnection，避免额外网络库。
 * 新浪行情接口返回 GBK，必须按 GBK 解码。
 */
object Http {
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"

    class HttpError(message: String) : Exception(message)

    fun getText(
        url: String,
        charset: Charset = Charsets.UTF_8,
        referer: String? = null,
        timeoutMs: Int = 10_000,
        userAgent: String = UA,
    ): String = String(getBytes(url, referer, timeoutMs, userAgent), charset)

    fun getBytes(
        url: String,
        referer: String? = null,
        timeoutMs: Int = 10_000,
        userAgent: String = UA,
    ): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "GET"
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "*/*")
            referer?.let { setRequestProperty("Referer", it) }
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw HttpError("HTTP $code: $url")
            connection.inputStream.use { input ->
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(chunk)
                    if (read <= 0) break
                    buffer.write(chunk, 0, read)
                }
                return buffer.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    fun postJson(
        url: String,
        body: String,
        timeoutMs: Int = 30_000,
        userAgent: String = UA,
        authorization: String? = null,
    ): String {
        val payload = body.toByteArray(Charsets.UTF_8)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "POST"
            doOutput = true
            setFixedLengthStreamingMode(payload.size)
            jsonHeaders(userAgent, authorization).forEach { (name, value) ->
                setRequestProperty(name, value)
            }
        }
        try {
            connection.outputStream.use { output -> output.write(payload) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.use { input ->
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(chunk)
                    if (read <= 0) break
                    buffer.write(chunk, 0, read)
                }
                String(buffer.toByteArray(), Charsets.UTF_8)
            }.orEmpty()
            if (code !in 200..299) throw HttpError("HTTP $code: ${response.take(240)}")
            return response
        } finally {
            connection.disconnect()
        }
    }

    internal fun jsonHeaders(userAgent: String, authorization: String? = null): Map<String, String> =
        buildMap {
            put("User-Agent", userAgent)
            put("Accept", "application/json")
            put("Content-Type", "application/json; charset=utf-8")
            authorization?.trim()?.takeIf { it.isNotBlank() }?.let {
                put("Authorization", "Bearer $it")
            }
        }

    /**
     * 失败重试，等价于菜单栏脚本里的 _fetch_with_retry：
     * 抛异常重试，isValid 判定为空的返回值同样视为失败并重试。
     */
    fun <T> retry(
        retries: Int = 3,
        delayMs: Long = 1_000,
        label: String = "数据",
        isValid: (T) -> Boolean = { true },
        block: () -> T,
    ): T {
        var last: Exception? = null
        for (attempt in 1..retries) {
            try {
                val value = block()
                if (isValid(value)) return value
                last = HttpError("$label 返回空数据")
            } catch (error: Exception) {
                last = error
            }
            if (attempt < retries) Thread.sleep(delayMs)
        }
        throw last ?: HttpError("$label 请求失败")
    }
}
