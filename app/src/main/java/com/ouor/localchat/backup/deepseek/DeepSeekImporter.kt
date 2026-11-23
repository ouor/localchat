package com.ouor.localchat.backup.deepseek

import com.ouor.localchat.backup.BackupContents
import com.ouor.localchat.backup.BackupError
import com.ouor.localchat.backup.BackupException
import com.ouor.localchat.backup.ImportSink
import com.ouor.localchat.backup.RestorePlan
import com.ouor.localchat.backup.Validator
import com.ouor.localchat.backup.ZipImporter
import com.ouor.localchat.backup.format.SessionFile
import com.ouor.localchat.core.AppJson
import com.ouor.localchat.core.Ids
import com.ouor.localchat.core.model.Attachment
import com.ouor.localchat.core.model.AttachmentKind
import com.ouor.localchat.core.model.Block
import com.ouor.localchat.core.model.ChatError
import com.ouor.localchat.core.model.ChatErrorKind
import com.ouor.localchat.core.model.GenOptions
import com.ouor.localchat.core.model.MessageBody
import com.ouor.localchat.core.model.MessageNode
import com.ouor.localchat.core.model.PrepState
import com.ouor.localchat.core.model.ReplyStatus
import com.ouor.localchat.core.model.Role
import com.ouor.localchat.core.model.Session
import com.ouor.localchat.tools.Source
import com.ouor.localchat.core.model.TextBlock
import com.ouor.localchat.core.model.ThinkingBlock
import com.ouor.localchat.core.model.TitleSource
import com.ouor.localchat.core.model.ToolCallBlock
import com.ouor.localchat.core.model.ToolCallState
import com.ouor.localchat.core.model.ToolResultBlock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import java.io.BufferedInputStream
import java.net.URI
import java.net.URLConnection
import java.text.Normalizer
import java.time.Instant
import java.util.UUID

/**
 * K-08 DeepSeek history import (architecture 9.6), mapping rules ported from chat-demo
 * `history/toLocal.ts` and `importArchive.ts`. Everything gets new ids, so it only ever adds.
 */
internal object DeepSeekImporter {
    const val VERSION_KEY = "export_format_version"
    private const val VERSION = 3
    private const val INDEX = "chat_session_index.json"
    private const val USER = "user.json"
    private val SESSION = Regex("""chat_sessions/([^/]+)\.json""")

    fun read(zip: ZipArchiveInputStream, manifest: JsonObject, sink: ImportSink): BackupContents {
        val version = runCatching { manifest[VERSION_KEY]!!.jsonPrimitive.intOrNull }.getOrNull()
        if (version != VERSION) throw BackupException(BackupError.BAD_VERSION)

        val attachments = LinkedHashMap<String, Attachment>()
        /** NFC zip path -> attachment id, for images that should be in the zip. */
        val imagePaths = HashMap<String, String>()
        val sessionIds = mutableListOf<String>()
        val sourceIds = mutableSetOf<String>()
        var index: Set<String>? = null

        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.isDirectory) continue
            if (!zip.canReadEntryData(entry)) throw BackupException(BackupError.INCOMPLETE)
            val path = Normalizer.normalize(entry.name, Normalizer.Form.NFC)
            val sessionMatch = SESSION.matchEntire(path)
            when {
                path == USER -> Unit
                path == INDEX -> {
                    val list = decode(ListSerializer(DsIndexEntry.serializer()), ZipImporter.readJson(zip, entry))
                    index = list.map { it.id }.toSet().also { if (it.size != list.size) throw BackupException(BackupError.INCOMPLETE) }
                }
                sessionMatch != null -> {
                    val ds = decode(DsSession.serializer(), ZipImporter.readJson(zip, entry))
                    if (ds.id != sessionMatch.groupValues[1] || !sourceIds.add(ds.id)) throw BackupException(BackupError.INCOMPLETE)
                    val file = convert(ds, attachments, imagePaths)
                    Validator.checkTree(file)
                    sessionIds += file.session.id
                    sink.session(file)
                }
                path in imagePaths -> {
                    val id = imagePaths.getValue(path)
                    val meta = attachments.getValue(id)
                    // The same image may sit in several session folders; keep the first.
                    if (!meta.hasOriginal) {
                        val data = BufferedInputStream(zip)
                        data.mark(16)
                        val head = ByteArray(16).also { b -> data.read(b) }
                        data.reset()
                        val withMime = meta.copy(mime = imageMime(head) ?: meta.mime, hasOriginal = true)
                        val sha = ZipImporter.checkedFile(data, withMime, sink)
                        attachments[id] = withMime.copy(sha256 = sha)
                    }
                }
                // Unreferenced images and unknown entries are skipped.
            }
        }
        if (index == null || index != sourceIds) throw BackupException(BackupError.INCOMPLETE)
        return BackupContents(RestorePlan.Kind.DEEPSEEK, attachments = attachments.values.toList(), sessionIds = sessionIds)
    }

    /** One archived session to a SessionFile with new ids. Attachment metadata is added to [attachments]. */
    fun convert(ds: DsSession, attachments: MutableMap<String, Attachment>, imagePaths: MutableMap<String, String>): SessionFile {
        val sid = Ids.new()
        val sorted = ds.messages.sortedBy { it.id }
        val ids = HashMap<Long, String>()
        val roles = HashMap<Long, String>()
        fun broken(): Nothing = throw BackupException(BackupError.BROKEN_TREE)

        // Parents must have smaller ids, roots are questions, roles alternate (chat-demo buildTree).
        for (m in sorted) {
            if (m.id in ids || m.role !in setOf("USER", "ASSISTANT")) broken()
            val parentRole = m.parentId?.let { roles[it] ?: broken() }
            if (parentRole == null && m.role != "USER") broken()
            if (parentRole == m.role) broken()
            ids[m.id] = Ids.new()
            roles[m.id] = m.role
        }
        // Default selection: the highest-id child. Then the current message's path wins.
        val selected = HashMap<Long?, Long>()
        sorted.forEach { m -> m.parentId?.let { selected[it] = m.id } }
        var root: Long? = null
        if (sorted.isNotEmpty()) {
            var cur = ds.currentMessageId?.takeIf { it in ids } ?: broken()
            selected.remove(cur)
            val byId = sorted.associateBy { it.id }
            while (true) {
                val parent = byId.getValue(cur).parentId ?: break
                selected[parent] = cur
                cur = parent
            }
            root = cur
        }

        val nodes = sorted.mapIndexed { index, m ->
            val createdAt = time(m.insertedAt)
            MessageNode(
                id = ids.getValue(m.id),
                sessionId = sid,
                parentId = m.parentId?.let(ids::getValue),
                selectedChildId = selected[m.id]?.let(ids::getValue),
                role = if (m.role == "USER") Role.USER else Role.ASSISTANT,
                createdAt = createdAt,
                body = if (m.role == "USER") user(m, attachments, imagePaths) else assistant(m, index, createdAt),
            )
        }
        val session = Session(
            id = sid,
            title = ds.title.orEmpty(),
            titleSource = if (ds.title.isNullOrBlank()) TitleSource.NONE else TitleSource.AUTO,
            createdAt = time(ds.insertedAt),
            updatedAt = time(ds.updatedAt),
            rootSelectedId = root?.let(ids::getValue),
            lastModel = null,
        )
        return SessionFile(session, nodes)
    }

    private fun user(m: DsMessage, attachments: MutableMap<String, Attachment>, imagePaths: MutableMap<String, String>): MessageBody.User {
        val text = m.fragments.filter { it.type == "REQUEST" }.mapNotNull { fragment(DsText.serializer(), it)?.content }.joinToString("")
        val ids = m.fragments.filter { it.type == "FILE" }.flatMap { fragment(DsFiles.serializer(), it)?.files.orEmpty() }.map { f ->
            // Stable per DeepSeek file id: both restore passes and repeated imports agree.
            val id = UUID.nameUUIDFromBytes("deepseek-file:${f.id}".encodeToByteArray()).toString()
            attachments.getOrPut(id) {
                Attachment(
                    id = id, name = f.fileName,
                    mime = URLConnection.guessContentTypeFromName(f.fileName) ?: "application/octet-stream",
                    size = f.fileSize, kind = if (f.isImage) AttachmentKind.IMAGE else AttachmentKind.DOCUMENT,
                    state = PrepState.Ready, hasOriginal = false, width = f.width, height = f.height,
                    createdAt = time(f.insertedAt),
                )
            }
            if (f.isImage) f.exportPath?.let { imagePaths[Normalizer.normalize(it, Normalizer.Form.NFC)] = id }
            id
        }
        return MessageBody.User(text, ids.distinct())
    }

    private fun assistant(m: DsMessage, index: Int, createdAt: Long): MessageBody.Assistant {
        val blocks = mutableListOf<Block>()
        var sourceN = 0
        m.fragments.forEachIndexed { i, f ->
            val callId = "history-$index-$i"
            when (f.type) {
                "RESPONSE" -> fragment(DsText.serializer(), f)?.let { blocks += TextBlock(it.content, 0) }
                "TEMPLATE_RESPONSE" -> fragment(DsText.serializer(), f)?.let { blocks += TextBlock(it.content, 0, isTemplate = true) }
                "THINK" -> fragment(DsThink.serializer(), f)?.let {
                    blocks += ThinkingBlock(it.content, 0, startedAt = createdAt, endedAt = it.elapsedSecs?.let { s -> createdAt + (s * 1000).toLong() })
                }
                "TOOL_SEARCH" -> fragment(DsSearch.serializer(), f)?.let { s ->
                    val sources = s.results.filter { isWebUrl(it.url) }.map { Source(++sourceN, it.url, it.title, it.snippet, it.siteName) }
                    val args = buildJsonObject {
                        put("query", s.queries.firstOrNull().orEmpty())
                        putJsonArray("queries") { s.queries.forEach { q -> add(kotlinx.serialization.json.JsonPrimitive(q)) } }
                    }
                    val content = sources.joinToString("\n\n") { "[${it.n}] ${it.title}\n${it.url}\n${it.snippet.orEmpty()}" }
                    blocks += tool(callId, "web_search", args, s.status ?: "FINISHED", createdAt, content, sources)
                }
                "TOOL_OPEN" -> fragment(DsOpen.serializer(), f)?.takeIf { it.url != null && isWebUrl(it.url) }?.let { o ->
                    blocks += tool(callId, "read", buildJsonObject { put("url", o.url) }, o.status, createdAt, listOfNotNull(o.title, o.url).joinToString("\n"))
                }
                "READ_LINK" -> fragment(DsReadLink.serializer(), f)?.results.orEmpty().forEachIndexed { j, r ->
                    // Signature-only results mean nothing outside DeepSeek's server; they are dropped.
                    if (r.url != null && isWebUrl(r.url)) {
                        blocks += tool("$callId-$j", "read", buildJsonObject { put("url", r.url) }, "FINISHED", createdAt, listOfNotNull(r.title, r.url).joinToString("\n"))
                    }
                }
                // TIP, CONTENT_RISK, PARTIAL_FILE_READ, TOOL_FIND, TOOL_IMAGE_SEARCH ... have no local form.
            }
        }
        val refused = m.status == "CONTENT_FILTER"
        val (status, error) = when (m.status) {
            "FINISHED", "CONTENT_FILTER" -> ReplyStatus.DONE to null
            "INCOMPLETE", "WIP" -> ReplyStatus.STOPPED to null
            "CONTEXT_LENGTH_EXCEEDED" -> ReplyStatus.ERROR to ChatError(ChatErrorKind.CONTEXT_OVERFLOW)
            "TIMEOUT" -> ReplyStatus.ERROR to ChatError(ChatErrorKind.TIMEOUT)
            else -> ReplyStatus.ERROR to ChatError(ChatErrorKind.SERVER_BUSY)
        }
        return MessageBody.Assistant(
            // A refusal keeps only template text and tool steps (chat-demo toAssistant).
            blocks = if (refused) blocks.filter { it !is ThinkingBlock && (it !is TextBlock || it.isTemplate) } else blocks,
            status = status,
            refusal = refused,
            error = error,
            model = null,
            options = GenOptions(reasoning = m.thinkingEnabled, search = m.searchEnabled),
            imported = true,
        )
    }

    private fun tool(callId: String, name: String, args: JsonObject, status: String, at: Long, content: String, sources: List<Source> = emptyList()): List<Block> {
        val state = when (status) {
            "FINISHED" -> ToolCallState.DONE
            "WIP" -> ToolCallState.CANCELLED
            else -> ToolCallState.FAILED
        }
        val ok = state == ToolCallState.DONE
        return listOf(
            ToolCallBlock(callId, name, args.toString(), 0, state, at, at),
            ToolResultBlock(callId, 0, if (ok) content else "unavailable", isError = !ok, sources = if (ok) sources else emptyList()),
        )
    }

    private val JsonObject.type: String? get() = runCatching { this["type"]?.jsonPrimitive?.content }.getOrNull()

    /** A fragment that does not decode is dropped, not fatal. */
    private fun <T> fragment(serializer: KSerializer<T>, o: JsonObject): T? = runCatching { AppJson.decodeFromJsonElement(serializer, o) }.getOrNull()

    private fun <T> decode(serializer: KSerializer<T>, e: kotlinx.serialization.json.JsonElement): T = ZipImporter.decode(serializer, e)

    private fun time(iso: String): Long = try {
        Instant.parse(iso).toEpochMilli()
    } catch (e: java.time.format.DateTimeParseException) {
        throw BackupException(BackupError.INCOMPLETE, e)
    }

    private fun isWebUrl(url: String): Boolean = runCatching { URI(url).scheme?.lowercase() in setOf("http", "https") }.getOrDefault(false)

    /** Magic bytes, as chat-demo `imageType`; null keeps the name-based guess. */
    private fun imageMime(b: ByteArray): String? {
        fun starts(vararg p: Int) = b.size >= p.size && p.indices.all { b[it] == p[it].toByte() }
        val ascii = String(b, Charsets.ISO_8859_1)
        return when {
            starts(0xff, 0xd8, 0xff) -> "image/jpeg"
            starts(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> "image/png"
            ascii.startsWith("GIF87a") || ascii.startsWith("GIF89a") -> "image/gif"
            ascii.startsWith("RIFF") && ascii.substring(8, 12) == "WEBP" -> "image/webp"
            starts(0x42, 0x4d) -> "image/bmp"
            ascii.length >= 12 && ascii.substring(4, 8) == "ftyp" && ascii.substring(8, 12) in setOf("avif", "avis") -> "image/avif"
            else -> null
        }
    }
}
