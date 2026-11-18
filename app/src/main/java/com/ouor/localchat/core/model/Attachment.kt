package com.ouor.localchat.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Attachment metadata. Files live under filesDir/attachments/{id} (original), {id}.txt (extracted
 * text) and {id}.req.jpg (downscaled request image); only storage.AttachmentFiles knows the paths.
 */
@Serializable
data class Attachment(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val kind: AttachmentKind,
    val state: PrepState,
    /** false = imported data with the name only (I-06); excluded from requests. */
    val hasOriginal: Boolean,
    val sha256: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val createdAt: Long,
)

@Serializable
enum class AttachmentKind {
    IMAGE,
    DOCUMENT,

    /** Tool output (page picture, approval preview, PDF). */
    ARTIFACT,
}

/** I-05 preparation state. */
@Serializable
sealed interface PrepState {
    @Serializable
    @SerialName("preparing")
    data object Preparing : PrepState

    @Serializable
    @SerialName("ready")
    data object Ready : PrepState

    /** [reason] is a machine code defined by attach and mapped to text in ui. */
    @Serializable
    @SerialName("failed")
    data class Failed(val reason: String) : PrepState
}
