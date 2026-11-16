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

@Serializable
data class SnapshotResult(val text: String, val nodes: Int, val truncated: Boolean)

@Serializable
data class PageTextResult(val title: String, val url: String, val page: Int, val pages: Int, val text: String)

@Serializable
data class PageState(val url: String, val title: String, val readyState: String)

@Serializable
data class QueryResult(val attached: Boolean, val visible: Boolean, val stale: Boolean = false)

@Serializable
data class ScrollResult(val x: Int, val y: Int, val maxY: Int, val elementY: Int? = null, val elementMaxY: Int? = null)

@Serializable
data class FocusResult(val info: ElementInfo? = null, val sensitive: Boolean = false)

@Serializable
data class TypeResult(val value: String)

@Serializable
data class SelectResult(val selected: List<String>)

@Serializable
data class SearchResult(val title: String, val url: String, val snippet: String? = null)
