package com.ouor.localchat.tools

import com.ouor.localchat.tools.Untrusted

/**
 * What the model reads of a result (browser-layers 3-3): the app's lead, the page text inside the boundary, capped at
 * [MAX_PAGE], then the app's note. Marking where text came from and bounding its size are layer 2's own quality.
 */
object ToolResultFormat {
    const val MAX_PAGE = 50_000
    const val TRUNCATED = "\n…[truncated]"

    /** [page] capped, before it is stored. */
    fun cap(page: String): String = if (page.length > MAX_PAGE) page.take(MAX_PAGE) + TRUNCATED else page

    fun render(lead: String, page: String, note: String): String = Untrusted.result(lead, page, note)

    fun render(o: ToolOutcome): String = render(o.lead, cap(o.page), o.note)
}
