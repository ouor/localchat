package com.ouor.localchat.llm

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.random.Random

/** P-09 retry rules (architecture 4.6). Pure; the client applies them before the first content delta only. */
object RetryPolicy {

    const val MAX_DELAY_MS = 10_000L
    const val MAX_RETRY_AFTER_MS = 60_000L

    fun isRetryable(error: LlmError): Boolean = when (error) {
        is LlmError.RateLimited, is LlmError.ServerBusy, LlmError.Timeout, LlmError.Network, LlmError.EmptyResponse -> true
        else -> false
    }

    /** [attempt] starts at 1. A server Retry-After wins (capped at 60 s); otherwise 0.5 s × 2^(n-1) up to 10 s, ±10 %. */
    fun delayMs(attempt: Int, retryAfterMs: Long?, random: Random = Random): Long {
        if (retryAfterMs != null) return retryAfterMs.coerceIn(0, MAX_RETRY_AFTER_MS)
        val base = min(MAX_DELAY_MS.toDouble(), 500.0 * 2.0.pow(attempt - 1))
        return (base * (1 + random.nextDouble(-0.1, 0.1))).roundToLong()
    }

    /** `Retry-After` as seconds or an HTTP-date; null when absent or unreadable. */
    fun parseRetryAfter(value: String?, nowMs: Long): Long? {
        val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        v.toLongOrNull()?.let { return it * 1000 }
        v.toDoubleOrNull()?.let { return (it * 1000).roundToLong() }
        return runCatching {
            ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMs
        }.getOrNull()?.coerceAtLeast(0)
    }
}
