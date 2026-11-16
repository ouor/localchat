package com.ouor.localchat.llm

import com.ouor.localchat.core.model.ChatError
import com.ouor.localchat.core.model.ChatErrorKind

/** Classified request failure (architecture 4.5). Retry rules live in RetryPolicy, not here. */
sealed interface LlmError {
    val httpStatus: Int?
    val serverMessage: String?

    /** [noKey]: the request carried no API key. */
    data class Auth(override val httpStatus: Int?, override val serverMessage: String?, val noKey: Boolean = false) : LlmError
    data class Balance(override val httpStatus: Int?, override val serverMessage: String?) : LlmError
    data class RateLimited(override val httpStatus: Int?, override val serverMessage: String?) : LlmError
    data class ServerBusy(override val httpStatus: Int?, override val serverMessage: String?) : LlmError
    data class ContextOverflow(override val httpStatus: Int?, override val serverMessage: String?) : LlmError
    data class BadRequest(override val httpStatus: Int?, override val serverMessage: String?) : LlmError
    data class Protocol(override val serverMessage: String?) : LlmError { override val httpStatus: Int? get() = null }

    /** Unusable endpoint setup, found before sending (F-11). */
    data class Config(override val serverMessage: String?) : LlmError { override val httpStatus: Int? get() = null }
    data object Timeout : LlmError { override val httpStatus: Int? get() = null; override val serverMessage: String? get() = null }
    data object Network : LlmError { override val httpStatus: Int? get() = null; override val serverMessage: String? get() = null }
    data object EmptyResponse : LlmError { override val httpStatus: Int? get() = null; override val serverMessage: String? get() = null }
}

/** The stored form of an error; the only mapping between llm and core error kinds. */
fun LlmError.toChatError(): ChatError {
    val kind = when (this) {
        is LlmError.Auth -> if (noKey) ChatErrorKind.NO_KEY else ChatErrorKind.AUTH
        is LlmError.Balance -> ChatErrorKind.BALANCE
        is LlmError.RateLimited -> ChatErrorKind.RATE_LIMITED
        is LlmError.ServerBusy -> ChatErrorKind.SERVER_BUSY
        is LlmError.ContextOverflow -> ChatErrorKind.CONTEXT_OVERFLOW
        is LlmError.BadRequest -> ChatErrorKind.BAD_REQUEST
        is LlmError.Protocol -> ChatErrorKind.PROTOCOL
        is LlmError.Config -> ChatErrorKind.CONFIG
        LlmError.Timeout -> ChatErrorKind.TIMEOUT
        LlmError.Network -> ChatErrorKind.NETWORK
        LlmError.EmptyResponse -> ChatErrorKind.EMPTY_RESPONSE
    }
    return ChatError(kind, httpStatus, serverMessage)
}

/** An auth failure of a request sent without a key is "no key", not "key rejected". */
internal fun LlmError.withKey(apiKey: String?): LlmError =
    if (this is LlmError.Auth && apiKey.isNullOrBlank()) copy(noKey = true) else this

/** Thrown by the non-streaming calls ([ChatCompletionsClient.listModels]). */
class LlmException(val error: LlmError) : Exception(error.toString())