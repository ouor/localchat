package com.ouor.localchat.browser

import android.net.Uri

/**
 * Something that happened in a tab and may need an answer (browser-layers 2-4). Layer 1 asks the tab's listeners, then
 * the engine-wide ones, then answers by itself the way Chrome does ([BrowserEngine.defaultAnswer]). An event answered
 * [Answer.ToUser] waits in [BrowserEngine.prompts] until [BrowserEngine.answer].
 */
sealed interface BrowserEvent {
    val id: Long
    val tabId: Int
    /** Uptime ms. */
    val at: Long

    data class Dialog(
        override val id: Long, override val tabId: Int, override val at: Long,
        val kind: DialogKind, val message: String, val defaultText: String?, val url: String,
    ) : BrowserEvent

    /** A link to another app (tel:, mailto:, intent:); [userInput] = a real touch of the user started it, not an injected one. */
    data class ExternalLink(override val id: Long, override val tabId: Int, override val at: Long, val uri: String, val userInput: Boolean) : BrowserEvent

    /** `window.open` / `target=_blank`: [newTabId] is already open. */
    data class Popup(override val id: Long, override val tabId: Int, override val at: Long, val newTabId: Int, val userInput: Boolean) : BrowserEvent

    /** [size] -1 = unknown. */
    data class Download(
        override val id: Long, override val tabId: Int, override val at: Long,
        val url: String, val mime: String?, val size: Long, val fileName: String,
    ) : BrowserEvent

    /** Camera / microphone / protected media for [origin]. */
    data class Permission(override val id: Long, override val tabId: Int, override val at: Long, val origin: String, val resources: List<String>) : BrowserEvent

    data class FileChooser(override val id: Long, override val tabId: Int, override val at: Long, val acceptTypes: List<String>, val multiple: Boolean) : BrowserEvent

    /** [crashed] false = the system killed the renderer for memory; [repeated] = the same URL died twice in a row. */
    data class RendererGone(override val id: Long, override val tabId: Int, override val at: Long, val crashed: Boolean, val repeated: Boolean) : BrowserEvent

    /** App.onTrimMemory [level]; no tab ([tabId] 0) and no answer. */
    data class MemoryPressure(override val id: Long, override val tabId: Int, override val at: Long, val level: Int) : BrowserEvent
}

/**
 * Dialog: Accept = OK ([Accept.text] a prompt's answer, null = its default), Dismiss = Cancel. ExternalLink: Accept opens the
 * app. Popup: Accept keeps the new tab (the user's own gesture also shows it), Dismiss closes it. Download: Accept saves.
 * Permission: Accept grants. FileChooser: [Files], Dismiss = none. RendererGone: Accept reloads now, Dismiss waits for the
 * next use. ToUser: the user answers.
 */
sealed interface Answer {
    data class Accept(val text: String? = null) : Answer
    data object Dismiss : Answer
    data object ToUser : Answer
    data class Files(val uris: List<Uri>) : Answer
}

/** Answers right in the callback (never waits); null = no opinion, the next one is asked. */
fun interface EventListener {
    fun onEvent(e: BrowserEvent, proposed: Answer): Answer?
}

/** An event and how it was answered: the tab's record ([BrowserTab.eventsSince]) and [BrowserEngine.events]. */
data class Handled(val event: BrowserEvent, val answer: Answer)

enum class DialogKind { ALERT, CONFIRM, PROMPT, BEFOREUNLOAD }

/** A model-facing browser failure (page not answering, a script error). The message may carry page text. */
open class PageError(message: String) : Exception(message)

/** agent.js refused a ref: not a ref, from an earlier page of the tab, or gone. The tool answers with the current list. */
class StaleRef(message: String) : PageError(message)

/** An app-written browser failure (no tab open): sent to the model as is. */
class BrowserRefusal(message: String) : Exception(message)

/** [BrowserEngine.tab] of a tab that is gone. */
class TabClosed(tabId: Int) : Exception("tab $tabId closed")
