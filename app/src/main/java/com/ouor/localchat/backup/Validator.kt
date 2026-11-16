package com.ouor.localchat.backup

import com.ouor.localchat.backup.format.SessionFile
import com.ouor.localchat.core.model.MessageBody
import com.ouor.localchat.core.model.Role
import com.ouor.localchat.core.model.ToolResultBlock

/** K-07 checks that need a decoded session (architecture 9.3). Format and count checks live in ZipImporter. */
object Validator {

    /** Throws BROKEN_TREE when [file] is not a well-formed branch tree of one session. */
    fun checkTree(file: SessionFile) {
        val sid = file.session.id
        val byId = file.messages.associateBy { it.id }
        fun broken(): Nothing = throw BackupException(BackupError.BROKEN_TREE)

        if (byId.size != file.messages.size) broken()
        val children = file.messages.groupBy { it.parentId }
        for (m in file.messages) {
            if (m.sessionId != sid) broken()
            val parent = m.parentId?.let { byId[it] ?: broken() }
            // Root is a question and roles alternate down every branch.
            if (parent == null && m.role != Role.USER) broken()
            if (parent != null && parent.role == m.role) broken()
            if ((m.role == Role.USER) != (m.body is MessageBody.User)) broken()
            m.selectedChildId?.let { c -> if (children[m.id].orEmpty().none { it.id == c }) broken() }
        }
        // Every node must reach a root; a parent cycle never does.
        for (m in file.messages) {
            var cur = m
            var steps = 0
            while (cur.parentId != null) {
                cur = byId.getValue(cur.parentId!!)
                if (++steps > file.messages.size) broken()
            }
        }
        file.session.rootSelectedId?.let { r -> if (byId[r]?.parentId != null || r !in byId) broken() }
        file.summaries.forEach { if (it.sessionId != sid || it.coversUpToId !in byId) broken() }
        file.draft?.let { if (it.key != sid) broken() }
    }

    /** Attachment ids a session refers to (messages, tool artifacts, draft). */
    fun attachmentIds(file: SessionFile): Set<String> = buildSet {
        for (m in file.messages) {
            when (val b = m.body) {
                is MessageBody.User -> addAll(b.attachmentIds)
                is MessageBody.Assistant -> b.blocks.filterIsInstance<ToolResultBlock>().forEach { r ->
                    addAll(r.artifactIds)
                    r.imageForModelId?.let(::add)
                }
            }
        }
        file.draft?.let { addAll(it.attachmentIds) }
    }
}
