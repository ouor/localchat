package com.ouor.localchat.chat

import com.ouor.localchat.chat.context.ContextAssembler
import com.ouor.localchat.chat.context.SystemPrompt
import com.ouor.localchat.core.model.TitleSource
import com.ouor.localchat.llm.LlmResult
import javax.inject.Inject
import javax.inject.Singleton

/** C-02 (architecture 5.7): runs once after the first reply; failures keep the FIRST_PROMPT title. */
@Singleton
class TitleGenerator @Inject constructor(
    private val store: ChatStore,
    private val llm: LlmPort,
) {
    suspend fun generate(sessionId: String, model: ResolvedModel, question: String, answer: String) {
        if (store.session(sessionId)?.titleSource == TitleSource.USER) return
        val request = ContextAssembler.plain(
            model, SystemPrompt.TITLE, "Question:\n${question.take(1000)}\n\nAnswer:\n${answer.take(1000)}",
        )
        val result = llm.complete(model.target, request) as? LlmResult.Ok ?: return
        val title = clean(result.text) ?: return
        store.updateSession(sessionId) {
            // The user may have renamed it while the request ran.
            if (it.titleSource == TitleSource.USER) it else it.copy(title = title, titleSource = TitleSource.AUTO)
        }
    }

    companion object {
        const val MAX = 30

        /** First line, without quotes, markdown or a "Title:" label, cut to [MAX] characters. */
        fun clean(raw: String): String? {
            var t = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
            t = t.removePrefix("#").trim()
            t = t.replace(Regex("^(title|제목)\\s*[:：]\\s*", RegexOption.IGNORE_CASE), "")
            t = t.trim('"', '\'', '`', '“', '”', '‘', '’', '「', '」', '*', ' ')
            t = t.trimEnd('.', '。', '!', '?').trim()
            t = t.replace(Regex("\\s+"), " ")
            return firstPrompt(t).ifEmpty { null }
        }

        /** Also the FIRST_PROMPT title: first [MAX] characters, not splitting surrogate pairs. */
        fun firstPrompt(text: String): String {
            val oneLine = text.trim().replace(Regex("\\s+"), " ")
            if (oneLine.codePointCount(0, oneLine.length) <= MAX) return oneLine
            return oneLine.substring(0, oneLine.offsetByCodePoints(0, MAX)).trimEnd()
        }
    }
}
