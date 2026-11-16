package com.ouor.localchat.ui.chat

import android.net.Uri
import com.ouor.localchat.chat.run.Resolution
import com.ouor.localchat.core.model.ModelRef

/** Chat callbacks; [ChatViewModel] implements them, Compose tests pass a fake (architecture 11). */
interface ChatActions {
    val sessionId: String?
    fun stop()
    fun selectVersion(messageId: String)
    fun regenerate(assistantId: String, model: ModelRef)
    fun continueReply(assistantId: String)
    fun editAndResend(userId: String, text: String, attachmentIds: List<String>, model: ModelRef)
    fun resolve(resolution: Resolution)
    fun retryWrites()
    fun rename(title: String)
    fun delete()
    fun compactNow()
    /** Step label and argument summary (F-01: ui does not branch on tool names for this). */
    fun toolStep(block: com.ouor.localchat.core.model.ToolCallBlock, result: com.ouor.localchat.core.model.ToolResultBlock?): com.ouor.localchat.chat.ToolStep? = null
    /** M-09: starts reading [text], or stops when [messageId] is already being read. */
    fun readAloud(messageId: String, text: String) {}
}

/** Composer callbacks; [ComposerViewModel] implements them. */
interface ComposerActions {
    fun onText(value: String)
    fun attach(uris: List<Uri>)
    fun retry(id: String)
    fun remove(id: String)
    fun selectModel(ref: ModelRef)
    fun toggleReasoning()
    fun setEffort(effort: String?)
    fun toggleSearch()
    fun toggleBrowser()
    fun startVoice()
    fun stopVoice()
    fun cancelVoice()
    /** Coil model for an image tile. */
    fun thumbnail(a: com.ouor.localchat.core.model.Attachment): Any?
    fun sendWithoutImages()
    fun send()
}
