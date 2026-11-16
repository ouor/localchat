package com.ouor.localchat.chat.context

/**
 * A-05 (architecture 5.4 rule 9): bulky page-reading results except the latest [KEEP] are cut to
 * head + marker + tail when sent. The stored original never changes.
 */
object ToolResultTrimmer {
    val BULKY = setOf("snapshot", "page_text", "console_logs", "execute")
    const val KEEP = 2
    const val LIMIT = 2000
    const val HEAD = 1500
    const val TAIL = 500

    /**
     * [results] in request order as (ToolCallBlock.key, toolName, content). Returns key → content to send.
     */
    fun trim(results: List<Triple<String, String, String>>): Map<String, String> {
        val bulky = results.filter { it.second in BULKY }
        val protected = bulky.takeLast(KEEP).map { it.first }.toSet()
        return results.associate { (id, name, content) ->
            id to if (name in BULKY && id !in protected) cut(content) else content
        }
    }

    fun cut(content: String): String {
        if (content.length <= LIMIT) return content
        val removed = content.length - HEAD - TAIL
        return content.take(HEAD) + "…[중략 ${removed}자]…" + content.takeLast(TAIL)
    }
}
