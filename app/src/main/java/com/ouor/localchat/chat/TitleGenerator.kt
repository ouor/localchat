package com.ouor.localchat.chat

import com.ouor.localchat.chat.context.ContextAssembler
import com.ouor.localchat.chat.context.SystemPrompt
import com.ouor.localchat.core.model.AppLanguage
import com.ouor.localchat.core.model.TitleSource
import com.ouor.localchat.llm.LlmResult
import com.ouor.localchat.llm.toChatError
import com.ouor.localchat.storage.SettingsStore
import kotlinx.coroutines.flow.first
import java.util.Locale
import java.util.logging.Logger
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
            "Question:\n${question.take(1000)}\n\nAnswer:\n${answer.take(1000)}\n\nWrite the title in $language." +
                // FB4 F3: "the language of the question" still drifted to Chinese twice in a row (then the question
                // became the title); the rule [fits] checks is now said, not left implied.
                if (language in CJK) "" else " Use no Chinese, Japanese or Korean characters.",
        )
        // QA1c: Flash still answered English questions with Chinese titles; retry once, then use the question.
        var title: String? = null
        repeat(2) {
            if (title == null) {
                val result = llm.complete(model.target, request)
                if (result !is LlmResult.Ok) return log.warning("title request failed: ${(result as LlmResult.Failed).error.toChatError().kind}")
                title = clean(result.text)?.takeIf { fits(it, language) }
            }
        }
        // FB4 F3: on screen the fallback looks like no title at all; the log tells them apart.
        if (title == null) log.warning("title not in $language twice; the question is the title")
        val chosen = title ?: firstPrompt(question).ifEmpty { return }
        store.updateSession(sessionId) {
            // Another reply's title or a user rename may have landed while the request ran.
            if (replaceable(it.titleSource)) it.copy(title = chosen, titleSource = TitleSource.AUTO) else it
        }
    }

    private suspend fun appLanguage(): String = when (settings.settings.first().language) {
        AppLanguage.KO -> "ko"
        AppLanguage.EN -> "en"
        AppLanguage.SYSTEM -> Locale.getDefault().language
    }

    companion object {
        const val MAX = 30
        private val CJK = setOf("Korean", "Japanese", "Chinese")
        private val log = Logger.getLogger("localchat")

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

        /** Whether [title]'s script matches [language] from [titleLanguage]; non-CJK languages only reject CJK letters. */
        fun fits(title: String, language: String): Boolean {
            val s = title.codePoints().toArray().filter(Character::isLetter).map(Character.UnicodeScript::of).toSet()
            val kana = Character.UnicodeScript.HIRAGANA in s || Character.UnicodeScript.KATAKANA in s
            val hangul = Character.UnicodeScript.HANGUL in s
            val han = Character.UnicodeScript.HAN in s
            return when (language) {
                "Korean" -> hangul
                "Japanese" -> (kana || han) && !hangul
                "Chinese" -> han && !kana && !hangul
                else -> !kana && !hangul && !han
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
