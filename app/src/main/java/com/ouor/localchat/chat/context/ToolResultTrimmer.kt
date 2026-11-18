package com.ouor.localchat.chat.context

import com.ouor.localchat.browser.TabRules

/**
 * A-05 (architecture 5.4 rule 9, tools-redesign 5-3): what older tool results look like when sent. The stored
 * original never changes.
 * - Element lists (browse, click, type, request_handoff): only the newest is sent whole; older ones keep their head
 *   (up to the first blank line), since their refs are stale anyway.
 * - read: whole while the newest-first total stays within [TabRules.readKeep] (the newest always), else cut.
 * - execute: the newest [KEEP] whole, older ones cut.
 */
object ToolResultTrimmer {
    val LIST = setOf("browse", "click", "type", "request_handoff")
    const val READ = "read"
    const val EXECUTE = "execute"
    const val KEEP = 2
    const val LIMIT = 2000
    const val HEAD = 1500
    const val TAIL = 500
    const val STALE = "[older element list omitted; its refs are stale]"
    private const val CLOSE = "</${Untrusted.TAG}>"
    private const val APP_NOTE = "\n\n[App note:"

    /**
     * [results] in request order as (unique call key, toolName, content); [contextWindow] in tokens (null = unknown).
     * Returns key → content to send.
     */
    fun trim(results: List<Triple<String, String, String>>, contextWindow: Int? = null): Map<String, String> {
        val newestList = results.lastOrNull { it.second in LIST }?.first
        val wholeExec = results.filter { it.second == EXECUTE }.takeLast(KEEP).map { it.first }.toSet()
        val wholeRead = HashSet<String>()
        var used = 0
        val keep = TabRules.readKeep(contextWindow)
        for ((id, _, content) in results.filter { it.second == READ }.asReversed()) {
            if (wholeRead.isNotEmpty() && used + content.length > keep) break
            wholeRead += id
            used += content.length
        }
        return results.associate { (id, name, content) ->
            id to when {
                name in LIST && id != newestList -> head(content)
                name == READ && id !in wholeRead -> cut(content)
                name == EXECUTE && id !in wholeExec -> cut(content)
                else -> content
            }
        }
    }

    /** The result's head (what was done, the Page line) without the list; boundary and app notes stay. */
    fun head(content: String): String {
        val blank = content.indexOf("\n\n")
        if (blank < 0) return content
        val note = content.indexOf(APP_NOTE).takeIf { it > blank }?.let { content.substring(it) }.orEmpty()
        val closed = if (content.startsWith("<${Untrusted.TAG}>")) "\n$CLOSE" else ""
        return content.substring(0, blank) + "\n" + STALE + closed + note
    }

    fun cut(content: String): String {
        if (content.length <= LIMIT) return content
        val removed = content.length - HEAD - TAIL
        return content.take(HEAD) + "…[${removed} characters omitted]…" + content.takeLast(TAIL)
    }
}
