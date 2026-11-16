package com.ouor.localchat.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Session(
    val id: String,
    val title: String,
    val titleSource: TitleSource,
    val createdAt: Long,
    val updatedAt: Long,
    /** Selected node among root-level siblings (editing the first question branches here). */
    val rootSelectedId: String?,
    /** P-07: the conversation remembers its last used model. */
    val lastModel: ModelRef?,
    /** D10: only tab URLs survive; tabs themselves live in memory. */
    val aiTabUrls: List<String> = emptyList(),
)

@Serializable
enum class TitleSource {
    NONE,
    AUTO,
    FIRST_PROMPT,

    /** Renamed by the user; C-02 never overwrites it. */
    USER,
}

/** A-06 compaction result. Applies only while [coversUpToId] is on the visible path. */
@Serializable
data class Summary(
    val id: String,
    val sessionId: String,
    val coversUpToId: String,
    val text: String,
    val createdAt: Long,
)

/** I-07 composer draft. [key] is a session id or [NEW_CHAT]. */
@Serializable
data class Draft(
    val key: String,
    val text: String,
    val attachmentIds: List<String>,
    val updatedAt: Long,
) {
    companion object {
        const val NEW_CHAT = "new"
    }
}
