package com.ouor.localchat.chat

import com.ouor.localchat.chat.run.ToolExecutor
import com.ouor.localchat.tools.ToolResultFormat
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * S-03 (architecture 5.7): for models without tool calling, pages linked in the question are read through the read
 * chain ([ToolExecutor.prefetchRead], policies included; A-m10) and attached to the question as text documents.
 */
@Singleton
class LinkPrefetcher @Inject constructor(
    private val attachments: AttachmentPort,
) {
    /** Returns the new attachment ids for [text]'s links, skipping [alreadyRead] ones and pages that fail or are refused. [onReading] shows progress per URL. */
    suspend fun prefetch(text: String, alreadyRead: Set<String>, ctx: ToolExecutor, onReading: (String) -> Unit): List<String> {
        val urls = URL.findAll(text).map { it.value.trimEnd('.', ',', ';', ':', '!', '?') }.distinct()
            .filter { it !in alreadyRead }.take(MAX_LINKS).toList()
        return urls.mapNotNull { url ->
            onReading(url)
            try {
                ctx.prefetchRead(url)?.let { attachments.saveText(url, ToolResultFormat.render(it)) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null // Unreadable page: the question goes without it.
            }
        }
    }

    private companion object {
        val URL = Regex("""https?://[^\s<>"'`)\]]+""")
        const val MAX_LINKS = 3
    }
}
