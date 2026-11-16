package com.ouor.localchat.core

import com.ouor.localchat.core.model.ChatError
import com.ouor.localchat.core.model.ChatErrorKind
import com.ouor.localchat.core.model.GenOptions
import com.ouor.localchat.core.model.MessageBody
import com.ouor.localchat.core.model.MessageNode
import com.ouor.localchat.core.model.ReplyStatus
import com.ouor.localchat.core.model.Role
import com.ouor.localchat.core.model.TextBlock
import com.ouor.localchat.core.model.ToolCallBlock
import com.ouor.localchat.core.model.ToolCallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageTreeTest {

    private var time = 0L
    private fun user(id: String) =
        MessageNode(id, "s", null, null, Role.USER, time++, MessageBody.User(id))
    private fun reply(id: String) =
        MessageNode(id, "s", null, null, Role.ASSISTANT, time++, assistant(ReplyStatus.DONE))

    private fun assistant(status: ReplyStatus, text: String = "hi", finish: String? = "stop") =
        MessageBody.Assistant(
            blocks = listOf(TextBlock(text, 0)),
            status = status,
            finishReason = finish,
            model = null,
            options = GenOptions(),
        )

    private fun ids(path: List<MessageNode>) = path.map { it.id }

    /** u1 -> a1, then a second answer a2 under u1, then the first question edited as u2 -> a3. */
    private fun branchedTree(): Pair<String?, List<MessageNode>> {
        var root: String? = null
        var nodes = emptyList<MessageNode>()
        fun add(parent: String?, vararg chain: MessageNode) {
            val (new, sel) = MessageTree.appendBranch(parent, chain.toList())
            val (r, n) = MessageTree.apply(root, nodes + new, sel)
            root = r
            nodes = n
        }
        add(null, user("u1"), reply("a1"))
        add("u1", reply("a2"))
        add(null, user("u2"), reply("a3"))
        return root to nodes
    }

    @Test
    fun `append selects the new branch and keeps the old one`() {
        val (root, nodes) = branchedTree()
        assertEquals(listOf("u2", "a3"), ids(MessageTree.path(root, nodes)))
        assertEquals(listOf("u1", "u2"), ids(MessageTree.siblings(nodes, null)))
        assertEquals(listOf("a1", "a2"), ids(MessageTree.siblings(nodes, "u1")))
    }

    @Test
    fun `switching back restores the deeper selection`() {
        val (root, nodes) = branchedTree()
        val sel = MessageTree.selectVersion(root, nodes, "u1")!!
        val (r, n) = MessageTree.apply(root, nodes, sel)
        assertEquals(listOf("u1", "a2"), ids(MessageTree.path(r, n)))
    }

    @Test
    fun `cannot select a version off the visible path`() {
        val (root, nodes) = branchedTree()
        assertNull(MessageTree.selectVersion(root, nodes, "a1"))
        assertNull(MessageTree.selectVersion(root, nodes, "missing"))
    }

    @Test
    fun `dangling selection falls back to the newest child`() {
        val nodes = listOf(
            user("u1").copy(selectedChildId = "gone"),
            reply("a1").copy(parentId = "u1"),
            reply("a2").copy(parentId = "u1"),
        )
        assertEquals(listOf("u1", "a2"), ids(MessageTree.path(null, nodes)))
    }

    @Test
    fun `cycle does not loop forever`() {
        val nodes = listOf(
            user("u1").copy(parentId = null, selectedChildId = "a1"),
            reply("a1").copy(parentId = "u1", selectedChildId = "u1"),
        )
        assertEquals(listOf("u1", "a1"), ids(MessageTree.path("u1", nodes)))
    }

    @Test
    fun `continue only for stopped or length-cut plain text replies`() {
        assertTrue(MessageTree.canContinue(assistant(ReplyStatus.STOPPED, finish = null)))
        assertTrue(MessageTree.canContinue(assistant(ReplyStatus.DONE, finish = "length")))
        assertFalse(MessageTree.canContinue(assistant(ReplyStatus.DONE)))
        assertFalse(MessageTree.canContinue(assistant(ReplyStatus.STOPPED, text = " ")))
        assertFalse(MessageTree.canContinue(assistant(ReplyStatus.STOPPED).copy(refusal = true)))
        assertFalse(MessageTree.canContinue(assistant(ReplyStatus.STOPPED).copy(imported = true)))
        assertFalse(
            MessageTree.canContinue(
                assistant(ReplyStatus.STOPPED).copy(error = ChatError(ChatErrorKind.NETWORK)),
            ),
        )
        val withTool = assistant(ReplyStatus.STOPPED).let {
            it.copy(blocks = it.blocks + ToolCallBlock("c", "click", "{}", 0, ToolCallState.DONE, 0))
        }
        assertFalse(MessageTree.canContinue(withTool))
    }

    @Test
    fun `regenerate is blocked for refusals and running replies`() {
        assertTrue(MessageTree.canRegenerate(assistant(ReplyStatus.ERROR)))
        assertFalse(MessageTree.canRegenerate(assistant(ReplyStatus.DONE).copy(refusal = true)))
        assertFalse(MessageTree.canRegenerate(assistant(ReplyStatus.STREAMING)))
    }
}
