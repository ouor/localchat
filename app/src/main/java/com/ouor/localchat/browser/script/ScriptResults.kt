package com.ouor.localchat.browser.script

import kotlinx.serialization.Serializable

/** Return shapes of assets/browser/agent.js and search.js functions (same names as there). */

@Serializable
data class CssRect(val left: Float, val top: Float, val width: Float, val height: Float)

/**
 * `locate`: the element (after scrollIntoView unless asked not to); [cx]/[cy] are top-viewport CSS px, [hit] =
 * elementFromPoint lands on it. [formSubmitLabel] = the label of its form's submit button, [frame] = where its page
 * comes from when not the tab's document (an iframe's src, a frame's URL).
 */
@Serializable
data class Located(
    val info: ElementInfo,
    val rect: CssRect,
    val innerWidth: Float,
    val cx: Float,
    val cy: Float,
    val hit: Boolean,
    /** What elementFromPoint finds at the centre when it is not the element (or "outside the viewport"). */
    val atPoint: String? = null,
    val editable: Boolean,
    val disabled: Boolean,
    val formSubmitLabel: String? = null,
    val frame: String? = null,
)

/**
 * `mark`: one look at the document for the readiness wait. [doc] is random per document, [n] counts DOM changes since
 * the agent was installed, [quietMs] is the time since the last DOM change or finished resource.
 */
@Serializable
data class PageMark(
    val doc: String,
    val n: Int,
    val quietMs: Long,
    val textLen: Int,
    val readyState: String,
    val url: String = "",
    val title: String = "",
)

/** `pageList`: element lines within the budget; [above]/[below] count the left-out ones by side of the visible part. */
@Serializable
data class PageList(
    val text: String,
    val listed: Int,
    /** Left out for the budget: scroll() reaches them. */
    val above: Int = 0,
    val below: Int = 0,
    /** Past the element bound of a huge page: no list reaches them. */
    val beyond: Int = 0,
)

/**
 * `readText`: main text; [flags] = "bot" (bot check) and/or "login" (sign-in page); [screenAt] = the character of
 * [text] at the top of the screen, null when no text block is on screen.
 */
@Serializable
data class ReadText(val title: String, val url: String, val text: String, val flags: List<String> = emptyList(), val screenAt: Int? = null)

/**
 * `scroll`: [y] / [max] of what moved (the document, or [box] = the ref of the pane it moved instead), [docHeight]
 * its scroll height and [count] the document's element count, which the app compares to tell the end from a late load.
 */
@Serializable
data class ScrollResult(val y: Int, val max: Int, val moved: Boolean, val docHeight: Int, val count: Int, val box: String? = null)

/** `type`: the field's value after typing ([selected] = the chosen option of a select); [submitRef] = the form's submit control. */
@Serializable
data class TypeResult(val value: String, val selected: String? = null, val submitRef: String? = null)

/** `suggestions`: visible ARIA options and listboxes (an autocomplete list opening after type). */
@Serializable
data class Suggestions(val linked: Boolean, val near: Int) {
    /** Suggestions opened by the typing that ended in [after]. */
    fun openedBy(after: Suggestions) = (after.linked && !linked) || after.near > near
}

@Serializable
data class SearchResult(val title: String, val url: String, val snippet: String? = null)

/** search.js: [blocked] = the page is a bot check or consent screen (then [results] is empty). */
@Serializable
data class SearchPage(val results: List<SearchResult>, val blocked: Boolean = false)
