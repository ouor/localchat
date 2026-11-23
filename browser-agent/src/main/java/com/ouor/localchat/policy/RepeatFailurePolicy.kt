package com.ouor.localchat.policy

import com.ouor.localchat.tools.ToolOutcome
import java.util.concurrent.ConcurrentHashMap

/**
 * A warning about the model's behaviour (browser-layers 4-2, S3): the second failure of the very same call in a turn
 * gets a note. Counted per chat, since several chats run at once; a new turn counts again.
 */
class RepeatFailurePolicy : ToolPolicy {
    private val failures = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>()

    override fun onTurnStart(sessionId: String) { failures[sessionId] = ConcurrentHashMap() }

    override fun onTurnEnd(sessionId: String) { failures.remove(sessionId) }

    override fun after(call: PolicyCall, facts: CallFacts, result: ToolOutcome): ToolOutcome {
        if (!result.isError) return result
        val counts = failures.getOrPut(call.sessionId) { ConcurrentHashMap() }
        val n = counts.merge("${call.tool} ${call.args}", 1, Int::plus)
        return if (n == 2) result.copy(note = listOf(result.note, NOTE).filter { it.isNotEmpty() }.joinToString("\n")) else result
    }

    companion object {
        const val NOTE = "[App note: this exact call failed twice. Change the approach, or answer with what you have.]"
    }
}
