package com.ouor.localchat.policy

import com.ouor.localchat.browser.Answer
import com.ouor.localchat.browser.BrowserEngine
import com.ouor.localchat.browser.BrowserEvent
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.tools.Untrusted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/**
 * The two policy lists, in order (browser-layers 4-1), and what they contribute. BrowserToolRunner runs the call chain
 * through here, the app attaches the browser policies, its prompt, tool list, drawer and settings read the
 * contributions. With both lists empty every answer is the tool's or layer 1's own.
 */
class Policies(
    private val tools: List<ToolPolicy>,
    private val browser: List<BrowserPolicy>,
) {
    private val all: List<Policy> = tools + browser

    /** The system prompt lines: the page boundary's ([Untrusted.PROMPT_LINE]) first, then each policy's. */
    fun promptLines(): List<String> = listOf(Untrusted.PROMPT_LINE) + all.flatMap { it.promptLines() }

    fun hiddenTools(): Set<String> = all.flatMapTo(mutableSetOf()) { it.hiddenTools() }

    fun settings(): List<SettingItem> = all.flatMap { it.settings() }

    val notices: Flow<List<Notice>> =
        if (all.isEmpty()) flowOf(emptyList()) else combine(all.map { it.notices }) { lists -> lists.flatMap { it } }

    /** App start: the browser policies get the engine's narrow view, and one engine-wide listener folds [onEvent]. */
    fun attach(engine: BrowserEngine, scope: CoroutineScope) {
        browser.forEach { it.attach(engine, scope) }
        if (browser.isNotEmpty()) engine.listenAll(::onEvent)
    }

    fun onEvent(e: BrowserEvent, proposed: Answer): Answer? {
        var a: Answer? = null
        browser.forEach { p -> p.onEvent(e, a ?: proposed)?.let { a = it } }
        return a
    }

    fun turnStart(sessionId: String) = tools.forEach { it.onTurnStart(sessionId) }

    fun turnEnd(sessionId: String) = tools.forEach { it.onTurnEnd(sessionId) }

    suspend fun before(call: PolicyCall): Verdict {
        for (p in tools) {
            val v = p.before(call)
            if (v != Verdict.Allow) return v
        }
        return Verdict.Allow
    }

    fun after(call: PolicyCall, facts: CallFacts, result: ToolOutcome): ToolOutcome =
        tools.fold(result) { r, p -> p.after(call, facts, r) }
}
