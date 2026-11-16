package com.ouor.localchat.backup.deepseek

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * DeepSeek history export, `export_format_version: 3` (chat-demo `history/format.ts`, MIT).
 * Only the fields the import uses. Fragments stay JsonObject: an unknown or broken fragment is
 * dropped on its own, while broken containers fail the import (chat-demo `schema.ts` rule).
 */
@Serializable
internal data class DsSession(
    val id: String,
    val title: String? = null,
    @SerialName("current_message_id") val currentMessageId: Long? = null,
    @SerialName("inserted_at") val insertedAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val messages: List<DsMessage>,
)

@Serializable
internal data class DsMessage(
    val id: Long,
    @SerialName("parent_id") val parentId: Long? = null,
    /** USER | ASSISTANT */
    val role: String,
    /** WIP | FINISHED | CONTENT_FILTER | FAILED | INCOMPLETE | CONTEXT_LENGTH_EXCEEDED | TIMEOUT */
    val status: String,
    @SerialName("thinking_enabled") val thinkingEnabled: Boolean = false,
    @SerialName("search_enabled") val searchEnabled: Boolean = false,
    @SerialName("inserted_at") val insertedAt: String,
    val fragments: List<JsonObject> = emptyList(),
)

@Serializable
internal data class DsIndexEntry(val id: String)

@Serializable
internal data class DsText(val content: String)

@Serializable
internal data class DsThink(val content: String, @SerialName("elapsed_secs") val elapsedSecs: Double? = null)

@Serializable
internal data class DsSearch(val queries: List<String> = emptyList(), val results: List<DsSearchResult> = emptyList(), val status: String? = null)

@Serializable
internal data class DsSearchResult(
    val url: String,
    val title: String,
    val snippet: String = "",
    @SerialName("site_name") val siteName: String? = null,
)

@Serializable
internal data class DsOpen(val status: String, val url: String? = null, val title: String? = null)

@Serializable
internal data class DsReadLink(val results: List<DsReadLinkResult> = emptyList())

@Serializable
internal data class DsReadLinkResult(val url: String? = null, val title: String? = null)

@Serializable
internal data class DsFiles(val files: List<DsFile>)

@Serializable
internal data class DsFile(
    val id: String,
    @SerialName("file_name") val fileName: String,
    @SerialName("file_size") val fileSize: Long,
    @SerialName("inserted_at") val insertedAt: String,
    @SerialName("export_path") val exportPath: String? = null,
    @SerialName("is_image") val isImage: Boolean,
    val width: Int? = null,
    val height: Int? = null,
)
