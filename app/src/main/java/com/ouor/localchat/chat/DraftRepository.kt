package com.ouor.localchat.chat

import android.net.Uri
import com.ouor.localchat.core.model.Draft
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** I-07 drafts per session, plus [Draft.NEW_CHAT] (public surface, architecture 10.2). */
@Singleton
class DraftRepository @Inject constructor() {

    /** Emits an empty draft when none is saved. */
    fun observe(key: String): Flow<Draft> = TODO("track B")

    /** Debouncing (300 ms) is the ViewModel's job. */
    suspend fun update(key: String, text: String, attachmentIds: List<String>): Unit = TODO("track B")

    /** I-02: called by MainActivity for ACTION_SEND/SEND_MULTIPLE; adds to the new-chat draft. */
    suspend fun addShared(uris: List<Uri>, text: String?): Unit = TODO("track B")
}
