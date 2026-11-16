package com.ouor.localchat.browser

/**
 * Something a page asks of the user (B-06, B-S2, B-S6). Listed in [BrowserEngine.prompts] until answered with
 * the matching `BrowserEngine.answer*` call. Downloads, permissions and file choosers always go to the user,
 * even on AI tabs; JS dialogs on AI tabs are answered by the dialog_handle tool (UI shows only user-tab dialogs).
 */
sealed interface BrowserPrompt {
    val id: Long
    val tabId: Int

    data class Dialog(
        override val id: Long,
        override val tabId: Int,
        val kind: DialogKind,
        val message: String,
        /** prompt(): the page's default text. */
        val defaultText: String?,
        val url: String,
    ) : BrowserPrompt

    /** Answer with [BrowserEngine.answerFileChooser]; launch the system picker with [acceptTypes]. */
    data class FileChooser(
        override val id: Long,
        override val tabId: Int,
        val acceptTypes: List<String>,
        val multiple: Boolean,
    ) : BrowserPrompt

    /** Allowed downloads go to the public Downloads folder through DownloadManager. */
    data class Download(
        override val id: Long,
        override val tabId: Int,
        val url: String,
        val fileName: String,
        val mime: String?,
        /** -1 = unknown. */
        val contentLength: Long,
    ) : BrowserPrompt

    /** Camera / microphone / protected media for [origin]. */
    data class Permission(
        override val id: Long,
        override val tabId: Int,
        val origin: String,
        val resources: List<String>,
    ) : BrowserPrompt
}

enum class DialogKind { ALERT, CONFIRM, PROMPT }

/** A model-facing browser failure (stale ref, page not answering, tab limit). The message goes to the model. */
class PageError(message: String) : Exception(message)
