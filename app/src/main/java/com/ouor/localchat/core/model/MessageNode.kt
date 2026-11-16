package com.ouor.localchat.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One message in the branch tree. Siblings share [parentId] and are ordered by [createdAt]. */
@Serializable
data class MessageNode(
    val id: String,
    val sessionId: String,
    /** null = root level. */
    val parentId: String?,
    /** M-06: the last selected child is kept per branch. */
    val selectedChildId: String?,
    val role: Role,
    val createdAt: Long,
    val body: MessageBody,
)

@Serializable
enum class Role { USER, ASSISTANT }

@Serializable
sealed interface MessageBody {

    @Serializable
    @SerialName("user")
    data class User(
        val text: String,
        val attachmentIds: List<String> = emptyList(),
    ) : MessageBody

    @Serializable
    @SerialName("assistant")
    data class Assistant(
        val blocks: List<Block>,
        val status: ReplyStatus,
        /** "stop" | "length" | "tool_calls" | "content_filter" ... as sent by the server. */
        val finishReason: String? = null,
        /** E-04 refusal or content filter. */
        val refusal: Boolean = false,
        /** E-01..E-03. */
        val error: ChatError? = null,
        val model: ModelRef?,
        val options: GenOptions,
        /** A-08 system context snapshot for this reply version. */
        val systemContext: String? = null,
        /** M-11, A-07. */
        val usage: Usage? = null,
        /** K-08: imported replies cannot be continued. */
        val imported: Boolean = false,
        /** Number of agent loop steps taken. */
        val steps: Int = 0,
    ) : MessageBody
}

@Serializable
enum class ReplyStatus { PENDING, STREAMING, DONE, STOPPED, ERROR }
