package com.ouor.localchat.chat

import com.ouor.localchat.tools.ToolContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * S-03 (architecture 5.7): for models without tool calling, pages linked in the question are read
 * in a hidden AI tab with page_text and attached to the question as text documents.
 */
@Singleton
class LinkPrefetcher @Inject constructor(
    private val tools: ToolPort,
    private val browser: BrowserPort,
    private val attachments: AttachmentPort,
) {
    /**
     * Returns the new attachment ids for [text]'s links, skipping sensitive URLs, [alreadyRead] ones
     * and pages that fail to load. [onReading] shows progress per URL.
     */
    suspend fun prefetch(text: String, alreadyRead: Set<String>, ctx: ToolContext, onReading: (String) -> Unit): List<String> {
        val pageText = tools.byName("page_text") ?: return emptyList()
        val urls = URL.findAll(text).map { it.value.trimEnd('.', ',', ';', ':', '!', '?') }.distinct()
            .filter { it !in alreadyRead && !browser.isSensitive(it) }.take(MAX_LINKS).toList()
        return urls.mapNotNull { url ->
            onReading(url)
            val tabId = browser.openAiTab(ctx.sessionId, url)
            try {
                val outcome = pageText.run(buildJsonObject { put("tabId", tabId) }, ctx)
                if (outcome.isError) null
                else attachments.saveText(url, "<untrusted_page_content>\n${outcome.text}\n</untrusted_page_content>")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null // Unreadable page: the question goes without it.
            } finally {
                withContext(NonCancellable) { browser.close(tabId) }
            }
        }
    }

    private companion object {
        val URL = Regex("""https?://[^\s<>"'`)\]]+""")
        const val MAX_LINKS = 3
    }
}
