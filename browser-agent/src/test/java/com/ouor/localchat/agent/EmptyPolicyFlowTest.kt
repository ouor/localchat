package com.ouor.localchat.agent

import com.ouor.localchat.browser.TabInfo
import com.ouor.localchat.browser.ToolStatus
import com.ouor.localchat.policy.ApprovalRequest
import com.ouor.localchat.policy.Policies
import com.ouor.localchat.tools.ChatTabs
import com.ouor.localchat.tools.TargetRule
import com.ouor.localchat.tools.ToolFacts
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.tools.browser.CloseTab
import com.ouor.localchat.tools.browser.Tabs
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S21: with every policy list empty the tools still do their whole job. The browser part (open, click, scroll, read) runs
 * in RunnerWithoutAppTest on a device; here the runner's own path with tab tools over a fake engine port.
 */
class EmptyPolicyFlowTest {
    private object Host : RunnerHost {
        override suspend fun confirm(callId: String, request: ApprovalRequest) = true
        override fun status(callId: String, status: ToolStatus?, engine: String?, tabId: Int?) = Unit
        override suspend fun saveArtifact(name: String, mime: String, bytes: ByteArray) = "artifact-$name"
        override fun numberSource(url: String, title: String, snippet: String?) = 1
    }

    private fun runner(tools: List<com.ouor.localchat.tools.Tool>, browser: FakeBrowserPort, chat: ChatTabs) = BrowserToolRunner(
        "s", { throw AssertionError("engine used") }, browser, Policies(emptyList(), emptyList()), Host, tools, chat,
    )

    @Test fun `an empty chain runs tools and names the callable ones`() = runTest {
        val browser = FakeBrowserPort()
        browser.tabs.value = listOf(TabInfo(1, "Home", "https://a.test/"), TabInfo(2, "News", "https://b.test/"))
        browser.shown = 1
        val chat = ChatTabs().also { it.opened += 2; it.used(2, 1) }
        val r = runner(listOf(Tabs, CloseTab), browser, chat)
        // tabs itself reads the real engine (ToolContext.browser): its output is checked in RunnerWithoutAppTest.
        val unknown = r.run("c1", "look", "{}")
        assertTrue(unknown.isError)
        assertEquals("Unknown tool: look. Tools you can call now: tabs, close_tab.", unknown.lead)
        // A fake tool with page facts goes through Targets: no tab named, the chat's current one.
        val page = FakeTool("page", facts = ToolFacts(TargetRule.NONE, browser = true, changesPage = false), body = { _, _ -> ToolOutcome(lead = "ok") })
        assertEquals("ok", runner(listOf(page), browser, chat).run("c1", "page", "{}").lead)
    }
}
