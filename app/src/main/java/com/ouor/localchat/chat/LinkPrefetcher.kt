package com.ouor.localchat.chat

import com.ouor.localchat.chat.run.ToolExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * S-03 (architecture 5.7): for models without tool calling, pages linked in the question are read with the read tool
 * (a hidden tab per page, opened, checked and closed by [ToolExecutor.hiddenTab]) and attached to the question as
 * text documents. The hidden tab shares the in-app browser's cookies: WebView has one process-wide CookieManager.
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
    suspend fun prefetch(text: String, alreadyRead: Set<String>, ctx: ToolExecutor, onReading: (String) -> Unit): List<String> {
        val read = tools.byName("read") ?: return emptyList()
        val urls = URL.findAll(text).map { it.value.trimEnd('.', ',', ';', ':', '!', '?') }.distinct()
            .filter { it !in alreadyRead && !browser.isSensitive(it) }.take(MAX_LINKS).toList()
        return urls.mapNotNull { url ->
            onReading(url)
            try {
                val outcome = read.run(buildJsonObject { put("url", url) }, ctx)
                if (outcome.isError) null else attachments.saveText(url, ctx.seal(outcome.text, null))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null // Unreadable page (or a redirect to a sensitive one): the question goes without it.
            }
        }
    }

    private companion object {
        val URL = Regex("""https?://[^\s<>"'`)\]]+""")
        const val MAX_LINKS = 3
    }
}
