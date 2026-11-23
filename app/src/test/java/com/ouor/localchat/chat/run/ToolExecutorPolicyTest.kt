package com.ouor.localchat.chat.run

import com.ouor.localchat.chat.FakeAttachments
import com.ouor.localchat.agent.BrowserToolRunner
import com.ouor.localchat.agent.FakeBrowserPort
import com.ouor.localchat.agent.FakeTool
import com.ouor.localchat.core.model.GenOptions
import com.ouor.localchat.core.model.ToolCallBlock
import com.ouor.localchat.core.model.ToolCallState
import com.ouor.localchat.policy.ApprovalRequest
import com.ouor.localchat.policy.CallFacts
import com.ouor.localchat.policy.Policies
import com.ouor.localchat.policy.PolicyCall
import com.ouor.localchat.policy.RepeatFailurePolicy
import com.ouor.localchat.policy.SecretValuePolicy
import com.ouor.localchat.policy.ToolPolicy
import com.ouor.localchat.policy.Verdict
import com.ouor.localchat.tools.ChatTabs
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolError
import com.ouor.localchat.tools.ToolOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.inject.Provider

/** A RunHandle for the executor alone: no run state, pauses answered by [answer]. */
internal class FakeHandle(var answer: (Pause) -> Resolution = { Resolution.ApprovalAnswer("", true) }) : RunRegistry.RunHandle {
    val pauses = mutableListOf<Pause>()
    override val state: RunState get() = throw AssertionError("state read")
    override fun update(transform: (RunState) -> RunState) = Unit
    override val switches: GenOptions? = null
    override suspend fun pause(pause: Pause): Resolution = answer(pause).also { pauses += pause }
}

internal fun executor(
    policies: Policies,
    tools: List<Tool>,
    handle: FakeHandle = FakeHandle(),
    browser: FakeBrowserPort = FakeBrowserPort(),
    chat: ChatTabs = ChatTabs(),
    states: MutableList<ToolCallState> = mutableListOf(),
) = ToolExecutor(
    "s", Provider { throw AssertionError("engine used") }, FakeAttachments(), browser, policies, handle, tools, emptyList(),
    chat = chat,
) { _, st -> states += st }

internal fun call(name: String, args: String = "{}", id: String = "c1") = ToolCallBlock(id, name, args, 0, ToolCallState.RUNNING, 0)

/**
 * browser-layers 10-1: the same call through an empty chain and through a chain of one policy; the difference is that
 * policy's part only.
 */
class ToolExecutorPolicyTest {
    private val none = Policies(emptyList(), emptyList())
    private fun one(p: ToolPolicy) = Policies(listOf(p), emptyList())
    private val secretPage = "- textbox \"Password\" [secret] [ref=1e3] value=\"hunter2\"\n- button \"Log in\" [ref=1e4]"
    private val tool = FakeTool("look_like", body = { _, _ -> ToolOutcome(lead = "Listed", page = secretPage) })

    @Test fun `secret values are hidden only with the policy`() = runTest {
        val plain = executor(none, listOf(tool)).execute(call("look_like")).block
        val masked = executor(one(SecretValuePolicy()), listOf(tool)).execute(call("look_like")).block
        assertEquals(secretPage, plain.page)
        assertEquals(SecretValuePolicy.hide(secretPage), masked.page)
        assertEquals(plain.copy(page = masked.page), masked)
    }

    @Test fun `a repeated failure gets the note only with the policy, exceptions counted`() = runTest {
        val failing = FakeTool("f", body = { _, _ -> throw ToolError("No such thing.") })
        val p = RepeatFailurePolicy()
        val withP = executor(one(p), listOf(failing))
        p.onTurnStart("s")
        withP.execute(call("f"))
        val second = withP.execute(call("f", id = "c2")).block
        val without = executor(none, listOf(failing)).also { it.execute(call("f")) }.execute(call("f", id = "c2")).block
        assertEquals("No such thing.", without.lead)
        assertEquals("", without.note)
        assertEquals(RepeatFailurePolicy.NOTE, second.note)
        assertEquals(without.copy(note = second.note), second)
    }

    @Test fun `a refusal stops the call before it runs and is marked refused`() = runTest {
        var ran = 0
        val t = FakeTool("t", body = { _, _ -> ran++; ToolOutcome(page = "p") })
        val refuse = object : ToolPolicy { override suspend fun before(call: PolicyCall) = Verdict.Refuse("Not here.") }
        val r = executor(one(refuse), listOf(t)).execute(call("t"))
        assertEquals(0, ran)
        assertTrue(r.block.refused && r.block.isError)
        assertEquals("Not here.", r.block.lead)
        assertEquals(ToolCallState.FAILED, r.state)
        assertFalse(executor(none, listOf(t)).execute(call("t")).block.refused)
    }

    @Test fun `a confirmation pauses with the generic request, no is DENIED, yes runs`() = runTest {
        var ran = 0
        val t = FakeTool("t", body = { _, _ -> ran++; ToolOutcome(page = "done") })
        val request = ApprovalRequest("Pay?", "button \"Pay\"", null, "https://shop.test/")
        val confirm = object : ToolPolicy { override suspend fun before(call: PolicyCall) = Verdict.Confirm(request) }
        val no = FakeHandle { Resolution.ApprovalAnswer((it as Pause.Approval).id, false) }
        val states = mutableListOf<ToolCallState>()
        val denied = executor(one(confirm), listOf(t), no, states = states).execute(call("t"))
        assertEquals(ToolCallState.DENIED, denied.state)
        assertEquals(0, ran)
        assertEquals(request, (no.pauses.single() as Pause.Approval).request)
        assertEquals(listOf(ToolCallState.AWAITING_APPROVAL, ToolCallState.RUNNING), states)
        val yes = executor(one(confirm), listOf(t)).execute(call("t"))
        assertEquals(ToolCallState.DONE, yes.state)
        assertEquals("done", yes.block.page)
    }

    @Test fun `after sees failures too, and a hidden tool is unknown`() = runTest {
        val seen = mutableListOf<ToolOutcome>()
        val watch = object : ToolPolicy {
            override fun after(call: PolicyCall, facts: CallFacts, result: ToolOutcome) = result.also { seen += it }
            override fun hiddenTools() = setOf("secret_tool")
        }
        val boom = FakeTool("boom", body = { _, _ -> throw IllegalStateException("internal") })
        val r = executor(one(watch), listOf(boom, FakeTool("secret_tool"))).execute(call("boom"))
        assertEquals(BrowserToolRunner.TOOL_FAILED, r.block.lead)
        assertEquals(1, seen.size)
        val hidden = executor(one(watch), listOf(boom, FakeTool("secret_tool"))).execute(call("secret_tool"))
        assertEquals("Unknown tool: secret_tool. Tools you can call now: boom.", hidden.block.lead)
    }
}
