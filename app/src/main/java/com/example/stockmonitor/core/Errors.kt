package com.example.stockmonitor.core

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** 把底层网络异常翻译成用户能看懂的中文提示，避免界面直接暴露堆栈信息。 */
object Errors {

    fun friendly(error: Throwable): String {
        val raw = error.message.orEmpty()
        return when {
            error is Http.HttpError -> raw.ifBlank { "接口返回异常" }
            error is UnknownHostException -> "网络不可用，请检查网络连接"
            error is SocketTimeoutException -> "请求超时，稍后自动重试"
            error is ConnectException -> "无法连接服务器，稍后自动重试"
            raw.contains("unexpected end of stream") -> "接口无响应，稍后自动重试"
            raw.contains("Canceled") || raw.contains("canceled") -> "请求已取消"
            raw.contains("HTTP 4") || raw.contains("HTTP 5") -> raw
            error is IOException -> "网络中断，稍后自动重试"
            raw.isNotBlank() -> raw
            else -> error.javaClass.simpleName
        }
    }
}
