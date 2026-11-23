package com.ouor.localchat.policy

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ouor.localchat.agent.R
import com.ouor.localchat.tools.TargetRule
import com.ouor.localchat.tools.ToolFacts
import com.ouor.localchat.tools.ToolOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The two policies that speak to the user, with this module's own strings (browser-layers 10-1). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SiteBlockPolicyTest {
    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val builtIn = SensitiveUrls.builtIn(ctx)

    private class Settings(s: SiteBlockState) : SiteBlockSettings {
        override val state = MutableStateFlow(s)
        override suspend fun setBuiltInOn(on: Boolean) { state.value = state.value.copy(builtInOn = on) }
        override suspend fun setUserDomains(domains: List<String>) { state.value = state.value.copy(userDomains = domains) }
    }

    private fun policy(scope: TestScope, settings: SiteBlockState = SiteBlockState(), facts: FakeFacts = FakeFacts()) =
        SiteBlockPolicy(builtIn, Settings(settings), scope.backgroundScope, facts, ctx).also { scope.runCurrent() }

    @Test fun aToolOnABlockedSitesTabIsRefusedOtherSitesAndAskUserAndTabsPass() = runTest(UnconfinedTestDispatcher()) {
        val p = policy(this)
        val bank = tab(3, "https://www.paypal.com/myaccount")
        assertTrue(p.before(call("read", tab = bank)) is Verdict.Refuse)
        assertTrue((p.before(call("read", tab = bank)) as Verdict.Refuse).message.contains("sensitive site"))
        assertEquals(Verdict.Allow, p.before(call("read", tab = tab(4, "https://en.wikipedia.org/wiki/PayPal"))))
        assertEquals(Verdict.Allow, p.before(call("ask_user", tab = bank)))
        assertEquals(Verdict.Allow, p.before(call("tabs", ToolFacts(TargetRule.NONE, browser = true, changesPage = false), tab = bank)))
    }

    @Test fun openingOrReadingABlockedAddressIsRefusedBeforeAnyTabExists() = runTest(UnconfinedTestDispatcher()) {
        val p = policy(this)
        val open = ToolFacts(TargetRule.OPEN, browser = true, changesPage = false)
        assertTrue(p.before(call("open", open, mapOf("url" to "paypal.com/signin"), tab = null)) is Verdict.Refuse)
        assertTrue(p.before(call("read", ToolFacts(TargetRule.READ, true, false), mapOf("url" to "https://accounts.google.com/"), tab = null)) is Verdict.Refuse)
        assertEquals(Verdict.Allow, p.before(call("open", open, mapOf("url" to "news.example.com"), tab = null)))
        assertEquals(Verdict.Allow, p.before(call("open", open, mapOf("url" to "paypal fees"), tab = null))) // search words
    }

    @Test fun withTheBuiltInListOffOnlyTheUsersListCountsAndABadWebHostFailsClosed() = runTest(UnconfinedTestDispatcher()) {
        val p = policy(this, SiteBlockState(builtInOn = false, userDomains = listOf("*.Corp.Example")))
        assertEquals(Verdict.Allow, p.before(call(tab = tab(1, "https://www.paypal.com/"))))
        assertTrue(p.before(call(tab = tab(1, "https://wiki.corp.example/page"))) is Verdict.Refuse)
        assertTrue(p.before(call(tab = tab(1, "https://bad host/x"))) is Verdict.Refuse)
    }

    @Test fun aCallThatEndsOnABlockedSiteGivesNoneOfItsContent() = runTest(UnconfinedTestDispatcher()) {
        val p = policy(this)
        val r = ToolOutcome(lead = "Clicked", page = "secret balance", note = "n")
        val out = p.after(call("click", REF_TOOL), facts(tabAfter = tab(1, "https://www.paypal.com/x")), r)
        assertEquals("", out.page)
        assertTrue(out.lead, out.lead.startsWith("Tab 1 is now on paypal.com, which is a sensitive site"))
        assertSame(r, p.after(call("click", REF_TOOL), facts(tabAfter = tab(1, "https://shop.example/")), r))
    }

    @Test fun itContributesAPromptLineTwoSettingsAndABannerForAShownBlockedTab() = runTest(UnconfinedTestDispatcher()) {
        val f = FakeFacts(listOf(tab(1, "https://www.paypal.com/"), tab(2)))
        val p = policy(this, facts = f)
        assertEquals(1, p.promptLines().size)
        val items = p.settings()
        assertTrue(items[0] is SettingItem.Switch && items[1] is SettingItem.TextList)
        assertEquals(ctx.resources.getQuantityString(R.plurals.fb2_s_builtin_on, builtIn.size, builtIn.size), items[0].description.value)
        assertTrue(builtIn.size > 100)
        assertTrue(p.notices.value.isEmpty())
        f.shown.value = 1
        runCurrent()
        assertEquals(listOf(1), p.notices.value.map { it.tabId })
        assertEquals(Notice.Style.BANNER, p.notices.value.single().style)
        f.shown.value = 2
        runCurrent()
        assertTrue(p.notices.value.isEmpty())
    }

    @Test fun theSettingsRowsChangeTheSettings() = runTest(UnconfinedTestDispatcher()) {
        val store = Settings(SiteBlockState())
        val p = SiteBlockPolicy(builtIn, store, backgroundScope, FakeFacts(), ctx)
        runCurrent()
        (p.settings()[0] as SettingItem.Switch).set(false)
        (p.settings()[1] as SettingItem.TextList).set(listOf("bank.example", "bank.example"))
        runCurrent()
        assertEquals(SiteBlockState(builtInOn = false, userDomains = listOf("bank.example")), store.state.value)
        assertEquals("bank.com", SiteBlockPolicy.domain(" https://Bank.com/login "))
        assertNull(SiteBlockPolicy.domain("not a domain"))
    }

    @Test fun domainRulesBS3() {
        val urls = SensitiveUrls(builtIn, user = listOf("*.Corp.Example", "https://intra.test/x"))
        listOf("https://accounts.google.com/signin", "https://obank.kbstar.com/x", "https://www.gov.kr/portal", "https://paypal.me/x")
            .forEach { assertTrue(it, urls.isSensitive(it)) }
        assertTrue(urls.isSensitive("https://a.b.paypal.com/"))
        assertFalse(urls.isSensitive("https://notpaypal.com/"))
        assertFalse(urls.isSensitive("https://paypal.com.evil.example/"))
        assertTrue(urls.isSensitive("http://intra.test/"))
        assertFalse(urls.isSensitive("about:blank"))
        assertEquals("paypal.com", SensitiveUrls.hostOf("https://www.PayPal.com/x"))
        assertEquals("my_host.example", SensitiveUrls.hostOf("https://my_host.example/"))
    }
}

@RunWith(AndroidJUnit4::class)
class MoneyConfirmPolicyTest {
    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val p = MoneyConfirmPolicy(ctx)

    private suspend fun click(at: com.ouor.localchat.browser.script.Located) = p.before(call("click", REF_TOOL, mapOf("ref" to "1e5"), describe = at))

    @Test fun paymentAndOrderButtonsAskFirst() = kotlinx.coroutines.runBlocking {
        listOf("결제하기", "구매하기", "주문하기", "Pay now", "Place order", "Checkout", "Buy now", "충전", "송금 보내기", "결제하기 " + "x".repeat(80)).forEach {
            val v = click(located("button", "button", it))
            assertTrue(it, v is Verdict.Confirm)
            assertEquals(ctx.getString(R.string.policy_money_title), (v as Verdict.Confirm).request.title)
        }
        assertTrue(click(located("input", "button", "Purchase", type = "submit")) is Verdict.Confirm)
    }

    @Test fun accountDeletionAsksFirst() = kotlinx.coroutines.runBlocking {
        listOf("회원 탈퇴", "탈퇴하기", "계정 삭제", "Delete account", "Delete my account", "Close account", "Deactivate account").forEach {
            val v = click(located("button", "button", it))
            assertTrue(it, v is Verdict.Confirm)
            assertEquals(ctx.getString(R.string.policy_account_title), (v as Verdict.Confirm).request.title)
        }
        assertTrue(click(located("a", "link", "회원 탈퇴")) is Verdict.Confirm)
    }

    @Test fun ordinaryButtonsLookupsAndLinksPass() = kotlinx.coroutines.runBlocking {
        listOf("Search", "다음", "Save", "주문 내역", "결제 내역 조회", "Order history", "Order status", "Subscribe", "구독하기", "Payment details")
            .forEach { assertEquals(it, Verdict.Allow, click(located("button", "button", it))) }
        listOf("결제 안내", "Payment methods", "주문하기 전에 읽어 주세요", "Buy now and save").forEach {
            assertEquals(it, Verdict.Allow, click(located("a", "link", it)))
        }
        assertTrue(click(located("a", "link", "Buy now")) is Verdict.Confirm)
        assertEquals(Verdict.Allow, p.before(call("read", PAGE_TOOL)))
    }

    @Test fun aClickInAPaymentProvidersFrameAsksFirst() = kotlinx.coroutines.runBlocking {
        assertTrue(click(located("iframe", "iframe", "", frame = "https://js.stripe.com/v3/elements")) is Verdict.Confirm)
        assertEquals(Verdict.Allow, click(located("iframe", "iframe", "", frame = "https://www.youtube.com/embed/x")))
    }

    @Test fun enterIntoAFormAsksWhenItsSubmitButtonPays() = kotlinx.coroutines.runBlocking {
        val field = located("input", "textbox", "Card holder", submit = "결제하기")
        assertTrue(p.before(call("type", REF_TOOL, mapOf("ref" to "1e2", "text" to "Kim\n"), describe = field)) is Verdict.Confirm)
        assertEquals(Verdict.Allow, p.before(call("type", REF_TOOL, mapOf("ref" to "1e2", "text" to "Kim"), describe = field)))
        assertEquals(Verdict.Allow, p.before(call("type", REF_TOOL, mapOf("ref" to "1e2", "text" to "shoes\n"),
            describe = located("input", "searchbox", "Search", submit = "Search", search = true))))
    }
}
