package com.ouor.localchat.chat.context

import com.ouor.localchat.browser.TabRules
import com.ouor.localchat.core.Untrusted
import com.ouor.localchat.core.model.ToolResultBlock

/**
 * A-05 (browser-layers 5-3): what older tool results look like when sent. The stored original never changes.
 * - Element lists ([LIST], and execute when it holds one): per open tab only the newest result that holds a list is
 *   sent whole; an error or a refusal after it does not count (G-M2). Older ones, and every list of a closed tab, keep
 *   their head (the page text up to the first blank line), since their refs are stale.
 * - read: whole while the newest-first total stays within [TabRules.readKeep] (the newest always), else cut.
 * - execute: the newest [KEEP] whole, older ones cut.
 */
object ToolResultTrimmer {
    val LIST = setOf("open", "navigate", "look", "click", "type", "scroll", "ask_user")
    const val READ = "read"
    const val EXECUTE = "execute"
    const val KEEP = 2
    const val LIMIT = 2000
    const val HEAD = 1500
    const val TAIL = 500
    const val STALE = "[older element list omitted; its refs are stale]"
    private const val APP_NOTE = "[App note:"

    /** A page header line followed by a blank line and the list (Support.pageResult). */
    private val LIST_HEAD = Regex("(?m)^Page: .*\n\n")

    /** `Page: … (tab 3, …)`: the tab a list is of. */
    private val LIST_TAB = Regex("(?m)^Page: .* \\(tab (\\d+)[,)]")

    /**
     * [results] in request order as (unique call key, toolName, result); [openTabs] the tabs open now (null = unknown,
     * every tab counts as open); [contextWindow] in tokens (null = unknown). Returns key → text to send.
     */
    fun trim(results: List<Triple<String, String, ToolResultBlock?>>, contextWindow: Int? = null, openTabs: Set<Int>? = null): Map<String, String> {
        val wholeLists = HashSet<String>()
        val seenTabs = HashSet<Int?>()
        for ((id, name, block) in results.asReversed()) {
            if (block == null || !listed(name, block)) continue
            val tab = tabOf(block.page)
            if (tab != null && openTabs != null && tab !in openTabs) continue
            if (seenTabs.add(tab)) wholeLists += id
        }
        val wholeExec = results.filter { it.second == EXECUTE }.takeLast(KEEP).map { it.first }.toSet()
        val wholeRead = HashSet<String>()
        var used = 0
        val keep = TabRules.readKeep(contextWindow)
        for ((id, _, block) in results.filter { it.second == READ }.asReversed()) {
            val len = block?.modelText?.length ?: 0
            if (wholeRead.isNotEmpty() && used + len > keep) break
            wholeRead += id
            used += len
        }
        return results.associate { (id, name, block) ->
            id to when {
                block == null -> SystemPrompt.ABORTED
                listed(name, block) && id !in wholeLists -> head(block)
                name == READ && id !in wholeRead -> cut(block.modelText)
                name == EXECUTE && id !in wholeExec -> cut(block.modelText)
                else -> block.modelText
            }
        }
    }

    /** A list result; execute holds one when its code opened a new page (G-m7). */
    private fun listed(name: String, b: ToolResultBlock) = (name in LIST || name == EXECUTE) && hasList(b.page)

    /** The page text holds an element list. */
    fun hasList(page: String): Boolean = LIST_HEAD.containsMatchIn(page)

    fun tabOf(page: String): Int? = LIST_TAB.find(page)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * The result's head without the list: the lead, the page text before the first blank line (dialogs, the Page line)
     * and any [App note] lines of the note. The notes about the old list are dropped.
     */
    fun head(b: ToolResultBlock): String {
        val blank = b.page.indexOf("\n\n")
        if (blank < 0) return b.modelText
        val notes = b.note.lines().filter { it.startsWith(APP_NOTE) }.joinToString("\n")
        return Untrusted.result(b.lead, b.page.substring(0, blank) + "\n" + STALE, notes)
    }

    fun cut(content: String): String {
        if (content.length <= LIMIT) return content
        val removed = content.length - HEAD - TAIL
        return content.take(HEAD) + "…[${removed} characters omitted]…" + content.takeLast(TAIL)
    }
}
