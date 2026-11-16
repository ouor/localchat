package com.ouor.localchat.chat

import com.ouor.localchat.chat.context.ContextAssembler
import com.ouor.localchat.chat.context.SystemPrompt
import com.ouor.localchat.core.model.AppLanguage
import com.ouor.localchat.core.model.TitleSource
import com.ouor.localchat.llm.LlmResult
import com.ouor.localchat.storage.SettingsStore
import kotlinx.coroutines.flow.first
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** C-02 (architecture 5.7): runs once after the first reply; failures keep the FIRST_PROMPT title. */
@Singleton
class TitleGenerator @Inject constructor(
    private val store: ChatStore,
    private val llm: LlmPort,
    private val settings: SettingsStore,
) {
    suspend fun generate(sessionId: String, model: ResolvedModel, question: String, answer: String) {
        if (!replaceable(store.session(sessionId)?.titleSource)) return
        val language = titleLanguage(question, appLanguage())
        val request = ContextAssembler.plain(
            model, SystemPrompt.TITLE,
            // The language rule goes last: DeepSeek otherwise drifts to Chinese (BUG-02).
            "Question:\n${question.take(1000)}\n\nAnswer:\n${answer.take(1000)}\n\nWrite the title in $language.",
        )
        val result = llm.complete(model.target, request) as? LlmResult.Ok ?: return
        val title = clean(result.text) ?: return
        store.updateSession(sessionId) {
            // Another reply's title or a user rename may have landed while the request ran.
            if (replaceable(it.titleSource)) it.copy(title = title, titleSource = TitleSource.AUTO) else it
        }
    }

    private suspend fun appLanguage(): String = when (settings.settings.first().language) {
        AppLanguage.KO -> "ko"
        AppLanguage.EN -> "en"
        AppLanguage.SYSTEM -> Locale.getDefault().language
    }

    companion object {
        const val MAX = 30

        /** BUG-02: only the placeholder titles are replaced, once; AUTO, imported and USER titles stay. */
        fun replaceable(source: TitleSource?) = source == TitleSource.FIRST_PROMPT || source == TitleSource.NONE

        /**
         * The question's language by script; a question without letters (a URL, numbers) falls back to
         * [appLanguage], an ISO 639 code.
         */
        fun titleLanguage(question: String, appLanguage: String): String {
            val text = question.replace(Regex("https?://\\S+"), "")
            val scripts = text.codePoints().toArray().filter(Character::isLetter).map(Character.UnicodeScript::of).toSet()
            return when {
                Character.UnicodeScript.HANGUL in scripts -> "Korean"
                Character.UnicodeScript.HIRAGANA in scripts || Character.UnicodeScript.KATAKANA in scripts -> "Japanese"
                Character.UnicodeScript.HAN in scripts -> "Chinese"
                scripts.isNotEmpty() -> "the language of the question"
                else -> Locale.forLanguageTag(appLanguage).getDisplayLanguage(Locale.ENGLISH).ifEmpty { "English" }
            }
        }

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
