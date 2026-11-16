package com.ouor.localchat.attach

import android.net.Uri
import com.ouor.localchat.core.model.Attachment
import com.ouor.localchat.core.model.ModelCapabilities
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Attachment intake (public surface, architecture 10.2). Type/size failures surface as PrepState.Failed. */
@Singleton
class AttachmentIngest @Inject constructor() {

    /** Copies, checks and prepares [uri]: emits PREPARING, then READY or FAILED (I-05). */
    fun ingest(uri: Uri): Flow<Attachment> = TODO("track D")

    /** Re-runs preparation of a FAILED attachment. */
    fun retry(attachmentId: String): Flow<Attachment> = TODO("track D")

    /** Send-time check against the target model (I-03). */
    fun validate(attachments: List<Attachment>, caps: ModelCapabilities): ValidationResult = TODO("track D")
}

data class ValidationResult(val issues: List<ValidationIssue>) {
    val ok: Boolean get() = issues.isEmpty()
}

/** [attachmentId] null = applies to the whole message. */
data class ValidationIssue(val kind: Kind, val attachmentId: String?) {
    enum class Kind { TOO_MANY, NOT_READY, IMAGES_UNSUPPORTED }
}
