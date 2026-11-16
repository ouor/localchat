package com.ouor.localchat.core

import com.ouor.localchat.core.model.MessageBody
import com.ouor.localchat.core.model.MessageNode
import com.ouor.localchat.core.model.ReplyStatus
import com.ouor.localchat.core.model.TextBlock
import com.ouor.localchat.core.model.ToolCallBlock

/**
 * Moves the selected child of [parentId] to [childId]. [parentId] == null means the session's
 * root selection (Session.rootSelectedId). This is the only pointer a branch change writes.
 */
data class Selection(val parentId: String?, val childId: String)

/**
 * Pure branch-tree operations (same rules as chat-demo branches.ts): siblings share a parentId,
 * ordered by createdAt; the visible path follows each node's selected child.
 */
object MessageTree {

    /** Root..parent of [node], following parentId only: independent of what the user has selected (AN 6). */
    fun ancestors(node: MessageNode, nodes: List<MessageNode>): List<MessageNode> {
        val byId = nodes.associateBy { it.id }
        val seen = HashSet<String>()
        return generateSequence(node.parentId?.let(byId::get)) { n -> n.parentId?.let(byId::get) }
            .takeWhile { seen.add(it.id) } // a corrupt cycle cannot loop forever
            .toList().asReversed()
    }

    /**
     * Visible path from the root. A missing or dangling selection falls back to the newest child,
     * so a half-written branch (node saved, pointer not) still shows up.
     */
    fun path(rootSelectedId: String?, nodes: List<MessageNode>): List<MessageNode> {
        val byId = nodes.associateBy { it.id }
        val children = nodes.groupBy { it.parentId }
        val path = mutableListOf<MessageNode>()
        val seen = HashSet<String>()
        var parentId: String? = null
        var selectedId = rootSelectedId
        while (true) {
            val node = byId[selectedId]?.takeIf { it.parentId == parentId }
                ?: children[parentId]?.maxByOrNull { it.createdAt }
                ?: break
            if (!seen.add(node.id)) break
            path += node
            parentId = node.id
            selectedId = node.selectedChildId
        }
        return path
    }

    /** Versions at one position (M-06 `n/m`), oldest first. */
    fun siblings(nodes: List<MessageNode>, parentId: String?): List<MessageNode> =
        nodes.filter { it.parentId == parentId }.sortedBy { it.createdAt }

    /**
     * New branch under [parentId]: links [chain] (e.g. user + assistant) parent to child and
     * selects its first node. Existing nodes are untouched except through the returned selection.
     */
    fun appendBranch(parentId: String?, chain: List<MessageNode>): Pair<List<MessageNode>, Selection> {
        require(chain.isNotEmpty()) { "empty chain" }
        val linked = chain.mapIndexed { i, node ->
            node.copy(
                parentId = if (i == 0) parentId else chain[i - 1].id,
                selectedChildId = chain.getOrNull(i + 1)?.id,
            )
        }
        return linked to Selection(parentId, linked.first().id)
    }

    /**
     * Switch to version [messageId]. Only a node whose position is on the visible path can be
     * selected; returns null otherwise. Deeper selections are kept (M-06).
     */
    fun selectVersion(rootSelectedId: String?, nodes: List<MessageNode>, messageId: String): Selection? {
        val node = nodes.firstOrNull { it.id == messageId } ?: return null
        val path = path(rootSelectedId, nodes)
        val onPath = node.parentId == null || path.any { it.id == node.parentId }
        return if (onPath) Selection(node.parentId, node.id) else null
    }

    /** Applies [selection] in memory; returns the new root selection and nodes. */
    fun apply(
        rootSelectedId: String?,
        nodes: List<MessageNode>,
        selection: Selection,
    ): Pair<String?, List<MessageNode>> =
        if (selection.parentId == null) {
            selection.childId to nodes
        } else {
            rootSelectedId to nodes.map {
                if (it.id == selection.parentId) it.copy(selectedChildId = selection.childId) else it
            }
        }

    /** M-03 "continue" is offered. Computed, never stored (architecture 3.1). */
    fun canContinue(body: MessageBody.Assistant): Boolean {
        val ended = body.status == ReplyStatus.STOPPED ||
            (body.status == ReplyStatus.DONE && body.finishReason == "length")
        return ended &&
            body.blocks.any { it is TextBlock && it.text.isNotBlank() } &&
            body.blocks.none { it is ToolCallBlock } &&
            !body.refusal && body.error == null && !body.imported
    }

    /** M-04 "regenerate" is offered: not for refusals or a reply still in progress. */
    fun canRegenerate(body: MessageBody.Assistant): Boolean =
        !body.refusal && body.status != ReplyStatus.PENDING && body.status != ReplyStatus.STREAMING
}
