package com.ouor.localchat.browser.script

import com.ouor.localchat.browser.safety.ElementInfo
import kotlinx.serialization.Serializable

/** Return shapes of assets/browser/agent.js and search.js functions (same names as there). */

@Serializable
data class CssRect(val left: Float, val top: Float, val width: Float, val height: Float)

/** `locate`: the element after scrollIntoView; [cx]/[cy] are top-viewport CSS px, [hit] = elementFromPoint lands on it. */
@Serializable
data class Located(
    val info: ElementInfo,
    val rect: CssRect,
    val innerWidth: Float,
    val cx: Float,
    val cy: Float,
    val hit: Boolean,
    val sensitive: Boolean,
    val editable: Boolean,
    val disabled: Boolean,
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

/**
 * `pageList`: element lines within the budget; [above]/[below] count the left-out ones by side of the visible part;
 * [continued] = these are the lines the last list of the page left out.
 */
@Serializable
data class PageList(val text: String, val listed: Int, val above: Int = 0, val below: Int = 0, val continued: Boolean = false)

/** `readText`: main text; [flags] = "bot" (bot check) and/or "login" (sign-in page). */
@Serializable
data class ReadText(val title: String, val url: String, val text: String, val flags: List<String> = emptyList())

/** `type`: the field's value after typing ([selected] = the chosen option of a select); [submitRef] = the form's submit control. */
@Serializable
data class TypeResult(val value: String, val selected: String? = null, val submitRef: String? = null)

/** `suggestions`: visible ARIA options and listboxes (an autocomplete list opening after type). */
@Serializable
data class Suggestions(val n: Int)

@Serializable
data class SearchResult(val title: String, val url: String, val snippet: String? = null)

/** search.js: [blocked] = the page is a bot check or consent screen (then [results] is empty). */
@Serializable
data class SearchPage(val results: List<SearchResult>, val blocked: Boolean = false)
