package com.ouor.localchat.backup

import com.ouor.localchat.backup.format.SessionFile
import com.ouor.localchat.core.Ids
import com.ouor.localchat.core.model.MessageBody
import com.ouor.localchat.core.model.ToolResultBlock

internal object SessionRemap {

    /**
     * K-05 "add as copy": new session and message ids everywhere they are referenced
     * (architecture 9.5). Attachment ids stay: same id, same content.
     */
    fun asCopy(f: SessionFile, titleSuffix: String, newId: () -> String = Ids::new): SessionFile {
        val sid = newId()
        val ids = f.messages.associate { it.id to newId() }
        fun map(id: String?) = id?.let { ids[it] ?: it }
        return SessionFile(
            session = f.session.copy(id = sid, title = f.session.title + titleSuffix, rootSelectedId = map(f.session.rootSelectedId)),
            messages = f.messages.map { it.copy(id = ids.getValue(it.id), sessionId = sid, parentId = map(it.parentId), selectedChildId = map(it.selectedChildId)) },
            summaries = f.summaries.map { it.copy(id = newId(), sessionId = sid, coversUpToId = map(it.coversUpToId)!!) },
            draft = f.draft?.copy(key = sid),
        )
    }

    /**
     * Drops attachment ids outside [allowed]: a message sent while the export runs may reference an
     * attachment the start-time snapshot (K-01) does not include, and a backup must never dangle.
     */
    fun keepAttachments(f: SessionFile, allowed: Set<String>): SessionFile {
        if (Validator.attachmentIds(f).all { it in allowed }) return f
        return f.copy(
            messages = f.messages.map { m ->
                when (val b = m.body) {
                    is MessageBody.User -> m.copy(body = b.copy(attachmentIds = b.attachmentIds.filter { it in allowed }))
                    is MessageBody.Assistant -> m.copy(
                        body = b.copy(
                            blocks = b.blocks.map { block ->
                                if (block !is ToolResultBlock) block
                                else block.copy(artifactIds = block.artifactIds.filter { it in allowed }, imageForModelId = block.imageForModelId?.takeIf { it in allowed })
                            },
                        ),
                    )
                }
            },
            draft = f.draft?.let { it.copy(attachmentIds = it.attachmentIds.filter { id -> id in allowed }) },
        )
    }
}
