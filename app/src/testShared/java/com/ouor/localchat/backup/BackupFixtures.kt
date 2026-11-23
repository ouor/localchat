package com.ouor.localchat.backup

import com.ouor.localchat.backup.format.ModelRecord
import com.ouor.localchat.backup.format.ProvidersFile
import com.ouor.localchat.backup.format.SessionFile
import com.ouor.localchat.core.model.ApiKey
import com.ouor.localchat.core.model.Attachment
import com.ouor.localchat.core.model.AttachmentKind
import com.ouor.localchat.core.model.ChatError
import com.ouor.localchat.core.model.ChatErrorKind
import com.ouor.localchat.core.model.Draft
import com.ouor.localchat.core.model.Endpoint
import com.ouor.localchat.core.model.GenOptions
import com.ouor.localchat.core.model.MessageBody
import com.ouor.localchat.core.model.MessageNode
import com.ouor.localchat.core.model.ModelOverrides
import com.ouor.localchat.core.model.ModelRef
import com.ouor.localchat.core.model.PrepState
import com.ouor.localchat.core.model.PresetId
import com.ouor.localchat.core.model.ReplyStatus
import com.ouor.localchat.core.model.Role
import com.ouor.localchat.core.model.Session
import com.ouor.localchat.tools.Source
import com.ouor.localchat.core.model.Summary
import com.ouor.localchat.core.model.TextBlock
import com.ouor.localchat.core.model.ThinkingBlock
import com.ouor.localchat.core.model.TitleSource
import com.ouor.localchat.core.model.ToolCallBlock
import com.ouor.localchat.core.model.ToolCallState
import com.ouor.localchat.core.model.ToolResultBlock
import com.ouor.localchat.core.model.Usage
import com.ouor.localchat.storage.AttachmentFiles
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.security.MessageDigest

/** Sample data with every optional field set, so the JSON key snapshot sees every key. */
object BackupFixtures {
    val model = ModelRef("ep1", "deepseek-flash", "Flash")

    fun bytes(id: String) = "original bytes of $id".encodeToByteArray()

    fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    fun attachment(id: String, kind: AttachmentKind = AttachmentKind.IMAGE) = Attachment(
        id = id, name = "$id.png", mime = "image/png", size = bytes(id).size.toLong(), kind = kind,
        state = PrepState.Ready, hasOriginal = true, sha256 = sha(bytes(id)), width = 10, height = 20, createdAt = 1,
    )

    fun session(id: String, attachmentId: String, title: String = "Chat $id"): SessionFile {
        val u1 = "$id-u1"
        val a1 = "$id-a1"
        val a2 = "$id-a2"
        val user = MessageNode(u1, id, null, a2, Role.USER, 10, MessageBody.User("hello", listOf(attachmentId)))
        fun reply(mid: String, at: Long) = MessageNode(
            mid, id, u1, null, Role.ASSISTANT, at,
            MessageBody.Assistant(
                blocks = listOf(
                    ThinkingBlock("why", 0, 11, 12, "reasoning_content"),
                    ToolCallBlock("c1", "web_search", """{"query":"q"}""", 0, ToolCallState.DONE, 12, 13),
                    ToolResultBlock("c1", 0, "result", false, "summary", listOf(Source(1, "https://a.b", "A", "snip", "site")), listOf(attachmentId), attachmentId),
                    TextBlock("answer [1]", 1, isTemplate = false),
                ),
                status = ReplyStatus.DONE, finishReason = "stop", refusal = false,
                error = ChatError(ChatErrorKind.TIMEOUT, 504, "slow"),
                model = model, options = GenOptions(true, "high", true, true), systemContext = "ctx",
                usage = Usage(10, 5, 2), imported = false, steps = 2,
            ),
        )
        return SessionFile(
            session = Session(id, title, TitleSource.USER, 1, 2, u1, model, listOf("https://x.y")),
            messages = listOf(user, reply(a1, 20), reply(a2, 30)),
            summaries = listOf(Summary("$id-s1", id, a2, "summary", 40)),
            draft = Draft(id, "draft text", listOf(attachmentId), 50),
        )
    }

    val providers = ProvidersFile(
        endpoints = listOf(Endpoint("ep1", "DeepSeek", "https://api.deepseek.com", PresetId.DEEPSEEK, true, 5, false, true)),
        models = listOf(
            ModelRecord(
                "ep1", "deepseek-flash", buildJsonObject { put("id", JsonPrimitive("deepseek-flash")) }, true, false, "Flash", false,
                ModelOverrides("Flash", true, false, true, listOf("high"), 128000, 8000), 99,
            ),
        ),
    )
}

/** In-memory BackupStore; transactions are "all or nothing" by staging into copies. */
class FakeStore(private val files: AttachmentFiles) : BackupStore {
    val sessions = linkedMapOf<String, SessionFile>()
    val attachments = linkedMapOf<String, Attachment>()
    var providers = ProvidersFile(emptyList(), emptyList())
    var keys = mapOf<String, ApiKey>()

    fun put(f: SessionFile, vararg atts: Attachment) {
        sessions[f.session.id] = f
        atts.forEach {
            attachments[it.id] = it
            files.original(it.id).writeBytes(BackupFixtures.bytes(it.id))
        }
    }

    override suspend fun sessionIds() = sessions.keys.toList()
    override suspend fun sessionFile(sessionId: String) = sessions[sessionId]
    override suspend fun attachmentsFor(sessionIds: List<String>): List<Attachment> {
        val ids = sessionIds.flatMap { sessions[it]?.let(Validator::attachmentIds).orEmpty() }.toSet()
        return attachments.values.filter { it.id in ids }
    }
    override suspend fun providers() = providers
    override suspend fun secrets() = keys
    override suspend fun existingSessionIds(ids: Collection<String>) = ids.filter { it in sessions }.toSet()

    override fun replaceAll(providers: ProvidersFile, attachments: List<Attachment>, sessions: ((SessionFile) -> Unit) -> Unit) {
        val staged = linkedMapOf<String, SessionFile>()
        sessions { staged[it.session.id] = it }
        this.sessions.clear()
        this.sessions.putAll(staged)
        this.providers = providers
        attachments.forEach { this.attachments[it.id] = it }
    }

    override fun addSessions(attachments: List<Attachment>, sessions: ((SessionFile) -> Unit) -> Unit) {
        val staged = linkedMapOf<String, SessionFile>()
        sessions { staged[it.session.id] = it }
        this.sessions.putAll(staged)
        attachments.forEach { this.attachments[it.id] = it }
    }

    override suspend fun replaceSecrets(keys: Map<String, ApiKey>) {
        this.keys = keys
    }
}

fun tempDir(): File = kotlin.io.path.createTempDirectory("backup-test").toFile().apply { deleteOnExit() }
