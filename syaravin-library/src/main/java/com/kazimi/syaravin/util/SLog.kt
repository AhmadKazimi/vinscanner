package com.kazimi.syaravin.util

import android.util.Log
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Library-wide structured logger that keeps a single Android log tag and emits
 * OpenTelemetry-style JSON records for easier parsing.
 */
internal object SLog {
    private const val MAX_VALUE_LENGTH = 8_192
    private const val MAX_STACKTRACE_LENGTH = 16_384
    private val TIMESTAMP_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd hh:mm:ss a")

    private const val DEFAULT_MIN_LEVEL = Log.WARN

    private fun shouldLog(
        tag: String,
        level: Int,
    ): Boolean {
        // Host app can override per tag via: setprop log.tag.SYARAHVIN DEBUG
        val configured = Log.isLoggable(tag, level)
        return configured || level >= DEFAULT_MIN_LEVEL
    }

    fun v(
        tag: String,
        message: String,
    ): Int = if (shouldLog(tag, Log.VERBOSE)) Log.v(tag, buildRecord("TRACE", message, null)) else 0

    fun d(
        tag: String,
        message: String,
    ): Int = if (shouldLog(tag, Log.DEBUG)) Log.d(tag, buildRecord("DEBUG", message, null)) else 0

    fun i(
        tag: String,
        message: String,
    ): Int = if (shouldLog(tag, Log.INFO)) Log.i(tag, buildRecord("INFO", message, null)) else 0

    fun w(
        tag: String,
        message: String,
    ): Int = if (shouldLog(tag, Log.WARN)) Log.w(tag, buildRecord("WARN", message, null)) else 0

    fun w(
        tag: String,
        message: String,
        throwable: Throwable,
    ): Int = if (shouldLog(tag, Log.WARN)) Log.w(tag, buildRecord("WARN", message, throwable)) else 0

    fun e(
        tag: String,
        message: String,
    ): Int = if (shouldLog(tag, Log.ERROR)) Log.e(tag, buildRecord("ERROR", message, null)) else 0

    fun e(
        tag: String,
        message: String,
        throwable: Throwable,
    ): Int = if (shouldLog(tag, Log.ERROR)) Log.e(tag, buildRecord("ERROR", message, throwable)) else 0

    private fun buildRecord(
        severityText: String,
        message: String,
        throwable: Throwable?,
    ): String {
        val module = detectCallerModule()
        val escapedMessage = escapeJson(message.take(MAX_VALUE_LENGTH))
        val escapedModule = escapeJson(module.take(MAX_VALUE_LENGTH))
        val escapedThread = escapeJson(Thread.currentThread().name.take(MAX_VALUE_LENGTH))
        val escapedThrowable =
            escapeJson(
                throwable?.stackTraceToString().orEmpty().take(MAX_STACKTRACE_LENGTH),
            )
        val timestamp = TIMESTAMP_FORMATTER.format(Instant.now().atZone(ZoneId.systemDefault()))

        // OTel-style shape: severityText + body + attributes
        return if (throwable == null) {
            "{" +
                "\"timestamp\":\"$timestamp\"," +
                "\"severityText\":\"$severityText\"," +
                "\"body\":\"[module=$escapedModule] $escapedMessage\"," +
                "\"attributes\":{" +
                "\"library\":\"Syaravin\"," +
                "\"module\":\"$escapedModule\"," +
                "\"thread.name\":\"$escapedThread\"" +
                "}" +
                "}"
        } else {
            "{" +
                "\"timestamp\":\"$timestamp\"," +
                "\"severityText\":\"$severityText\"," +
                "\"body\":\"[module=$escapedModule] $escapedMessage\"," +
                "\"attributes\":{" +
                "\"library\":\"Syaravin\"," +
                "\"module\":\"$escapedModule\"," +
                "\"thread.name\":\"$escapedThread\"," +
                "\"exception.stacktrace\":\"$escapedThrowable\"" +
                "}" +
                "}"
        }
    }

    private fun detectCallerModule(): String {
        val frames = Throwable().stackTrace
        for (frame in frames) {
            val className = frame.className
            if (
                !className.startsWith("com.kazimi.syaravin.util.SLog") &&
                className.startsWith("com.kazimi.syaravin")
            ) {
                return className.substringAfterLast('.')
            }
        }
        return "UnknownModule"
    }

    private fun escapeJson(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
}
