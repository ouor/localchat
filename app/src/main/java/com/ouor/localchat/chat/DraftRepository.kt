package com.ouor.localchat.chat

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import com.ouor.localchat.attach.AttachmentIngest
import com.ouor.localchat.core.ApplicationScope
import com.ouor.localchat.core.Clock
import com.ouor.localchat.core.model.Draft
import com.ouor.localchat.storage.db.dao.ChatDao
import com.ouor.localchat.storage.db.toEntity
import com.ouor.localchat.storage.db.toModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** I-07 drafts per session, plus [Draft.NEW_CHAT] (public surface, architecture 10.2). */
@Singleton
class DraftRepository @Inject constructor(
    private val dao: ChatDao,
    private val ingest: AttachmentIngest,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val mutex = Mutex()

    /** Emits an empty draft when none is saved. */
    fun observe(key: String): Flow<Draft> =
        dao.observeDraft(key).map { it?.toModel() ?: Draft(key, "", emptyList(), 0) }

    /** Debouncing (300 ms) is the ViewModel's job. */
    suspend fun update(key: String, text: String, attachmentIds: List<String>) {
        mutex.withLock { save(Draft(key, text, attachmentIds, clock.now())) }
    }

    /**
     * I-02: called by MainActivity for ACTION_SEND/SEND_MULTIPLE; adds to the new-chat draft.
     * Text is appended now; each file joins the draft as soon as ingest emits its row, and keeps
     * preparing in the application scope (I-05 state is shown from the attachment row).
     */
    suspend fun addShared(uris: List<Uri>, text: String?) {
        if (!text.isNullOrBlank()) {
            modify { it.copy(text = if (it.text.isBlank()) text else it.text + "\n" + text) }
        }
        for (uri in uris) {
            scope.launch {
                var added = false
                ingest.ingest(uri).collect { attachment ->
                    if (!added) {
                        added = true
                        modify { it.copy(attachmentIds = it.attachmentIds + attachment.id) }
                    }
                }
            }
        }
    }

    private suspend fun modify(transform: (Draft) -> Draft) = mutex.withLock {
        val current = dao.draft(Draft.NEW_CHAT)?.toModel() ?: Draft(Draft.NEW_CHAT, "", emptyList(), 0)
        save(transform(current).copy(updatedAt = clock.now()))
    }

    private suspend fun save(draft: Draft) = dao.upsertDraft(draft.toEntity(), draft.attachmentIds)
}

/** I-02 share intent contents, or null for any other intent. */
object SharedContent {
    fun from(intent: Intent): Pair<List<Uri>, String?>? {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val uris = when (intent.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> return null
        }
        return if (uris.isEmpty() && text.isNullOrBlank()) null else uris to text
    }
}
