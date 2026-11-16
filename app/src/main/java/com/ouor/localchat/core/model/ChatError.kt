package com.ouor.localchat.core.model

import kotlinx.serialization.Serializable

/** A stored reply error. ui chooses text and buttons from [kind] only (architecture 4.5). */
@Serializable
data class ChatError(
    val kind: ChatErrorKind,
    val httpStatus: Int? = null,
    /** Server message, shown as detail only. */
    val message: String? = null,
)

@Serializable
enum class ChatErrorKind {
    /** E-01: 401/403, "change key". */
    AUTH,

    /** E-02: 402, "top up". */
    BALANCE,

    /** E-03 group: retried first, then "try again". */
    RATE_LIMITED,
    SERVER_BUSY,
    TIMEOUT,
    NETWORK,
    EMPTY_RESPONSE,

    /** Context too long even after compaction. */
    CONTEXT_OVERFLOW,

    /** 400/404/413/422; if the request used optional features ui shows the P-05 hint. */
    BAD_REQUEST,

    /** Unparseable response. */
    PROTOCOL,
}
