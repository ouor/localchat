package com.ouor.localchat.agent

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ouor.localchat.browser.ToolStatus
import com.ouor.localchat.policy.ApprovalRequest
import com.ouor.localchat.policy.CookieMaskPolicy
import com.ouor.localchat.policy.MoneyConfirmPolicy
import com.ouor.localchat.policy.Policies
import com.ouor.localchat.policy.RepeatFailurePolicy
import com.ouor.localchat.policy.SecretValuePolicy
import com.ouor.localchat.policy.SensitiveUrls
import com.ouor.localchat.policy.SiteBlockPolicy
import com.ouor.localchat.policy.SiteBlockSettings
import com.ouor.localchat.policy.SiteBlockState
import com.ouor.localchat.policy.TabMemoryPolicy
import com.ouor.localchat.testing.ScreenOnRule
import com.ouor.localchat.testing.TestHostActivity
import com.ouor.localchat.tools.ChatTabs
import com.ouor.localchat.tools.ToolCatalog
import com.ouor.localchat.tools.browser.AskUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * browser-modules 4: the three modules without any app code. The engine in a bare Activity ([TestHostActivity] =
 * [com.ouor.localchat.browser.BrowserTabHost]), the runner with the default policies and a host of a few lines, driven
 * the way a model would: open, click, scroll, read, then ask_user shown in the host until the user is done.
 */
@RunWith(AndroidJUnit4::class)
class RunnerWithoutAppTest {
    @get:Rule val screen = ScreenOnRule()

    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var pages: MockWebServer
    private lateinit var scenario: ActivityScenario<TestHostActivity>
    private val engine = TestHostActivity.engine
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** All an app has to say: yes to every confirmation, nothing to show, artifacts and sources just numbered. */
    private val host = object : RunnerHost {
        val confirmed = mutableListOf<ApprovalRequest>()
        private val sources = mutableMapOf<String, Int>()
        override suspend fun confirm(callId: String, request: ApprovalRequest) = confirmed.add(request)
        override fun status(callId: String, status: ToolStatus?, engine: String?, tabId: Int?) = Unit
        override suspend fun saveArtifact(name: String, mime: String, bytes: ByteArray) = "artifact-$name"
        override fun numberSource(url: String, title: String, snippet: String?) = sources.getOrPut(url) { sources.size + 1 }
    }

    private val siteBlock = object : SiteBlockSettings {
        override val state = MutableStateFlow(SiteBlockState())
        override suspend fun setBuiltInOn(on: Boolean) { state.value = state.value.copy(builtInOn = on) }
        override suspend fun setUserDomains(domains: List<String>) { state.value = state.value.copy(userDomains = domains) }
    }

    @Before fun setUp() {
        pages = MockWebServer()
        pages.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/" -> html("<!doctype html><title>Start</title><h1>Start</h1><a href=\"/next\">Next</a>")
                "/next" -> html(
                    "<!doctype html><title>Next</title><h1>Next</h1>" +
                        (1..80).joinToString("") { "<p>Paragraph $it of the next page.</p>" } + "<p>The end of the next page.</p>",
                )
                else -> MockResponse.Builder().code(404).build()
            }
        }
        pages.start()
        scenario = ActivityScenario.launch(TestHostActivity::class.java)
        runBlocking(Dispatchers.Main) { engine.tabs.value.forEach { engine.close(it.id) } }
    }

    @After fun tearDown() {
        runBlocking(Dispatchers.Main) { engine.tabs.value.forEach { engine.close(it.id) } }
        scenario.close()
        scope.cancel()
        pages.close()
    }

    private fun html(body: String) = MockResponse.Builder().addHeader("Content-Type", "text/html; charset=utf-8").body(body).build()

    private fun json(vararg kv: Pair<String, String>) = kv.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"$v\"" }

    @Test fun openClickScrollReadAndAskUserWithTheDefaultPolicies() = runBlocking {
        val policies = Policies(
            listOf(
                SiteBlockPolicy(SensitiveUrls.builtIn(ctx), siteBlock, scope, engine, ctx),
                MoneyConfirmPolicy(ctx),
                SecretValuePolicy(),
                CookieMaskPolicy(),
                RepeatFailurePolicy(),
            ),
            listOf(TabMemoryPolicy(TabMemoryPolicy.startK(8L shl 30), null) {}),
        )
        policies.attach(engine, scope)
        val runner = BrowserToolRunner("s", { engine }, EngineBrowserPort(engine), policies, host, ToolCatalog.ALL, ChatTabs(), 64_000, false)

        val open = runner.run("1", "open", json("url" to pages.url("/").toString()))
        assertFalse(open.lead, open.isError)
        val tabId = engine.tabs.value.single().id
        val ref = Regex("""link "Next" \[ref=(\d*e\d+)]""").find(open.page)?.groupValues?.get(1)
        assertNotNull(open.page, ref)

        val tabs = runner.run("t", "tabs", "{}")
        assertEquals("1 tab open; your current tab is $tabId.", tabs.lead)
        assertTrue(tabs.page, tabs.page.startsWith("[$tabId] Start — ") && tabs.page.endsWith("(your current tab) (you opened it)"))

        val click = runner.run("2", "click", json("ref" to ref!!))
        assertFalse(click.lead, click.isError)
        assertTrue(engine.tabs.value.single().url.endsWith("/next"))

        val scroll = runner.run("3", "scroll", "{}")
        assertFalse(scroll.lead, scroll.isError)

        val read = runner.run("4", "read", "{}")
        assertFalse(read.lead, read.isError)
        assertTrue(read.page, "Paragraph 1 of the next page." in read.page)

        val ask = runner.run("5", "ask_user", json("task" to "Please check the page"))
        val step = ask.userStep
        assertNotNull(ask.lead, step)
        step!!
        assertEquals(tabId, step.tabId)
        scenario.onActivity { it.host.bind(engine, step.tabId) }
        assertEquals(step.tabId, engine.shown.value)
        val done = runner.afterUserStep("5", step)
        assertTrue(done.lead, done.lead.startsWith(AskUser.DONE))
        assertTrue(done.page, "Next" in done.page)
        scenario.onActivity { it.host.bind(engine, null) }
        assertEquals(null, engine.shown.value)
        assertTrue("nothing here pays or deletes an account", host.confirmed.isEmpty())
    }
}
