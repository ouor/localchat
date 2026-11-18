package com.ouor.localchat.policy

import com.ouor.localchat.R
import com.ouor.localchat.browser.Answer
import com.ouor.localchat.browser.Asleep
import com.ouor.localchat.browser.BrowserEvent
import com.ouor.localchat.browser.Front
import com.ouor.localchat.browser.Handled
import com.ouor.localchat.browser.ToolActivity
import com.ouor.localchat.chat.MemoryDataStore
import com.ouor.localchat.core.model.AppSettings
import com.ouor.localchat.storage.SettingsStore
import com.ouor.localchat.tools.TargetRule
import com.ouor.localchat.tools.ToolFacts
import com.ouor.localchat.tools.ToolOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
import java.io.File

/** Each policy on its own: applied when its case is there, the result untouched when not (browser-layers 10-1). */
@OptIn(ExperimentalCoroutinesApi::class)
class SiteBlockPolicyTest {
    private val builtIn = SensitiveUrls.parseList(File("src/main/assets/browser/sensitive_domains.txt").readText())

    private fun policy(scope: TestScope, settings: AppSettings = AppSettings(), facts: FakeFacts = FakeFacts()) =
        SiteBlockPolicy(builtIn, SettingsStore(MemoryDataStore(settings)), scope.backgroundScope, facts, FakeTexts).also { scope.runCurrent() }

    @Test fun `a tool on a blocked site's tab is refused, other sites and ask_user and tabs pass`() = runTest(UnconfinedTestDispatcher()) {
        val p = policy(this)
        val bank = tab(3, "https://www.paypal.com/myaccount")
        assertTrue(p.before(call("read", tab = bank)) is Verdict.Refuse)
        assertTrue((p.before(call("read", tab = bank)) as Verdict.Refuse).message.contains("sensitive site"))
        assertEquals(Verdict.Allow, p.before(call("read", tab = tab(4, "https://en.wikipedia.org/wiki/PayPal"))))
        assertEquals(Verdict.Allow, p.before(call("ask_user", tab = bank)))
        assertEquals(Verdict.Allow, p.before(call("tabs", ToolFacts(TargetRule.NONE, browser = true, changesPage = false), tab = bank)))
    }

    @Test fun `opening or reading a blocked address is refused before any tab exists`() = runTest(UnconfinedTestDispatcher()) {
        val p = policy(this)
        val open = ToolFacts(TargetRule.OPEN, browser = true, changesPage = false)
        assertTrue(p.before(call("open", open, mapOf("url" to "paypal.com/signin"), tab = null)) is Verdict.Refuse)
        assertTrue(p.before(call("read", ToolFacts(TargetRule.READ, true, false), mapOf("url" to "https://accounts.google.com/"), tab = null)) is Verdict.Refuse)
        assertEquals(Verdict.Allow, p.before(call("open", open, mapOf("url" to "news.example.com"), tab = null)))
        assertEquals(Verdict.Allow, p.before(call("open", open, mapOf("url" to "paypal fees"), tab = null))) // search words
    }

    @Test fun `with the built-in list off only the user's list counts, and a bad web host fails closed`() = runTest(UnconfinedTestDispatcher()) {
        val p = policy(this, AppSettings(builtInSensitiveEnabled = false, userSensitiveDomains = listOf("*.Corp.Example")))
        assertEquals(Verdict.Allow, p.before(call(tab = tab(1, "https://www.paypal.com/"))))
        assertTrue(p.before(call(tab = tab(1, "https://wiki.corp.example/page"))) is Verdict.Refuse)
        assertTrue(p.before(call(tab = tab(1, "https://bad host/x"))) is Verdict.Refuse)
    }

    @Test fun `a call that ends on a blocked site gives none of its content`() = runTest(UnconfinedTestDispatcher()) {
        val p = policy(this)
        val r = ToolOutcome(lead = "Clicked", page = "secret balance", note = "n")
        val out = p.after(call("click", REF_TOOL), facts(tabAfter = tab(1, "https://www.paypal.com/x")), r)
        assertEquals("", out.page)
        assertTrue(out.lead, out.lead.startsWith("Tab 1 is now on paypal.com, which is a sensitive site"))
        assertSame(r, p.after(call("click", REF_TOOL), facts(tabAfter = tab(1, "https://shop.example/")), r))
    }

    @Test fun `it contributes a prompt line, two settings and a banner for a shown blocked tab`() = runTest(UnconfinedTestDispatcher()) {
        val f = FakeFacts(listOf(tab(1, "https://www.paypal.com/"), tab(2)))
        val p = policy(this, facts = f)
        assertEquals(1, p.promptLines().size)
        val items = p.settings()
        assertTrue(items[0] is SettingItem.Switch && items[1] is SettingItem.TextList)
        assertEquals("${R.plurals.fb2_s_builtin_on}#${builtIn.size}", items[0].description.value)
        assertTrue(p.notices.value.isEmpty())
        f.shown.value = 1
        runCurrent()
        assertEquals(listOf(1), p.notices.value.map { it.tabId })
        assertEquals(Notice.Style.BANNER, p.notices.value.single().style)
        f.shown.value = 2
        runCurrent()
        assertTrue(p.notices.value.isEmpty())
    }

    @Test fun `the settings rows change the settings`() = runTest(UnconfinedTestDispatcher()) {
        val store = SettingsStore(MemoryDataStore(AppSettings()))
        val p = SiteBlockPolicy(builtIn, store, backgroundScope, FakeFacts(), FakeTexts)
        runCurrent()
        (p.settings()[0] as SettingItem.Switch).set(false)
        (p.settings()[1] as SettingItem.TextList).set(listOf("bank.example", "bank.example"))
        runCurrent()
        val s = store.settings.first()
        assertFalse(s.builtInSensitiveEnabled)
        assertEquals(listOf("bank.example"), s.userSensitiveDomains)
        assertEquals("bank.com", SiteBlockPolicy.domain(" https://Bank.com/login "))
        assertNull(SiteBlockPolicy.domain("not a domain"))
    }

    @Test fun `domain rules (B-S3)`() {
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

class MoneyConfirmPolicyTest {
    private val p = MoneyConfirmPolicy(FakeTexts)

    private suspend fun click(at: com.ouor.localchat.browser.script.Located) = p.before(call("click", REF_TOOL, mapOf("ref" to "1e5"), describe = at))

    @Test fun `payment and order buttons ask first`() = kotlinx.coroutines.runBlocking {
        listOf("결제하기", "구매하기", "주문하기", "Pay now", "Place order", "Checkout", "Buy now", "충전", "송금 보내기", "결제하기 " + "x".repeat(80)).forEach {
            val v = click(located("button", "button", it))
            assertTrue(it, v is Verdict.Confirm)
            assertEquals("${R.string.policy_money_title}[]", (v as Verdict.Confirm).request.title)
        }
        assertTrue(click(located("input", "button", "Purchase", type = "submit")) is Verdict.Confirm)
    }

    @Test fun `account deletion asks first`() = kotlinx.coroutines.runBlocking {
        listOf("회원 탈퇴", "탈퇴하기", "계정 삭제", "Delete account", "Delete my account", "Close account", "Deactivate account").forEach {
            val v = click(located("button", "button", it))
            assertTrue(it, v is Verdict.Confirm)
            assertEquals("${R.string.policy_account_title}[]", (v as Verdict.Confirm).request.title)
        }
        assertTrue(click(located("a", "link", "회원 탈퇴")) is Verdict.Confirm)
    }

    @Test fun `ordinary buttons, lookups and links pass`() = kotlinx.coroutines.runBlocking {
        listOf("Search", "다음", "Save", "주문 내역", "결제 내역 조회", "Order history", "Order status", "Subscribe", "구독하기", "Payment details")
            .forEach { assertEquals(it, Verdict.Allow, click(located("button", "button", it))) }
        listOf("결제 안내", "Payment methods", "주문하기 전에 읽어 주세요", "Buy now and save").forEach {
            assertEquals(it, Verdict.Allow, click(located("a", "link", it)))
        }
        assertTrue(click(located("a", "link", "Buy now")) is Verdict.Confirm)
        assertEquals(Verdict.Allow, p.before(call("read", PAGE_TOOL)))
    }

    @Test fun `a click in a payment provider's frame asks first`() = kotlinx.coroutines.runBlocking {
        assertTrue(click(located("iframe", "iframe", "", frame = "https://js.stripe.com/v3/elements")) is Verdict.Confirm)
        assertEquals(Verdict.Allow, click(located("iframe", "iframe", "", frame = "https://www.youtube.com/embed/x")))
    }

    @Test fun `Enter into a form asks when its submit button pays`() = kotlinx.coroutines.runBlocking {
        val field = located("input", "textbox", "Card holder", submit = "결제하기")
        assertTrue(p.before(call("type", REF_TOOL, mapOf("ref" to "1e2", "text" to "Kim\n"), describe = field)) is Verdict.Confirm)
        assertEquals(Verdict.Allow, p.before(call("type", REF_TOOL, mapOf("ref" to "1e2", "text" to "Kim"), describe = field)))
        assertEquals(Verdict.Allow, p.before(call("type", REF_TOOL, mapOf("ref" to "1e2", "text" to "shoes\n"),
            describe = located("input", "searchbox", "Search", submit = "Search", search = true))))
    }
}

class SecretValuePolicyTest {
    @Test fun `secret values are hidden in plain words, other lines stay`() {
        val page = "- textbox \"Password\" [secret] [ref=1e3] value=\"hunter22\"\n- textbox \"Name\" [ref=1e4] value=\"Kim\""
        val out = SecretValuePolicy().after(call(), facts(), ToolOutcome(page = page))
        assertEquals("- textbox \"Password\" [secret] [ref=1e3] value=[hidden by the app]\n- textbox \"Name\" [ref=1e4] value=\"Kim\"", out.page)
        val plain = ToolOutcome(page = "- textbox \"Name\" value=\"Kim\"")
        assertSame(plain, SecretValuePolicy().after(call(), facts(), plain))
    }
}

class CookieMaskPolicyTest {
    @Test fun `cookie values of the call's pages are hidden`() {
        val cookies = mapOf("https://a.example/" to "sid=abcdef123456; lang=ko", "https://b.example/" to "tok=a%2Bb%3Dcdefg")
        val r = ToolOutcome(page = "x abcdef123456 y ko a+b=cdefg z")
        val out = CookieMaskPolicy().after(call(), facts(tab(1, "https://b.example/"), listOf("https://a.example/"), cookies::get), r)
        assertEquals("x [cookie hidden by the app] y ko [cookie hidden by the app] z", out.page)
        assertSame(r, CookieMaskPolicy().after(call(), facts(tab(1, "https://c.example/")), r))
        assertEquals("""{"v":"[cookie hidden by the app]"}""", CookieMaskPolicy.mask("""{"v":"abc\\def1"}""", "t=abc\\def1"))
    }
}

class RepeatFailurePolicyTest {
    @Test fun `the second same failure in a turn gets the note, exceptions count, a new turn counts again`() {
        val p = RepeatFailurePolicy()
        val fail = ToolOutcome(lead = "boom", isError = true)
        val c = call("click", REF_TOOL, mapOf("ref" to "1e5"))
        p.onTurnStart("s")
        assertEquals("", p.after(c, facts(), fail).note)
        assertEquals(RepeatFailurePolicy.NOTE, p.after(c, facts(), fail).note)
        assertEquals("other args count apart", "", p.after(call("click", REF_TOOL, mapOf("ref" to "1e6")), facts(), fail).note)
        val ok = ToolOutcome(page = "fine")
        assertSame(ok, p.after(c, facts(), ok))
        p.onTurnEnd("s")
        p.onTurnStart("s")
        assertEquals("", p.after(c, facts(), fail).note)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TabMemoryPolicyTest {
    @Test fun `past K the least recently used tabs sleep, never the front, shown, busy or pending ones`() {
        val tabs = (1..8).map { tab(it, lastUsedAt = it.toLong(), pending = it == 3) }
        val out = TabMemoryPolicy.toDiscard(tabs, Front(1, 0), shown = 2, activity = mapOf(4 to ToolActivity("s", "click")), k = 4)
        assertEquals(listOf(5, 6, 7, 8), out)
        assertTrue(TabMemoryPolicy.toDiscard(tabs.take(4), null, null, emptyMap(), 4).isEmpty())
        val asleep = tabs.map { if (it.id > 5) it.copy(asleep = Asleep.DISCARDED) else it }
        assertEquals("the least recently used awake one", listOf(4), TabMemoryPolicy.toDiscard(asleep, Front(1, 0), 2, emptyMap(), 4))
        assertEquals(listOf(3, 6), listOf(TabMemoryPolicy.startK(6L shl 30), TabMemoryPolicy.startK(16L shl 30)))
    }

    @Test fun `attached it discards, lowers K after a memory kill and clears on a memory signal`() = runTest(UnconfinedTestDispatcher()) {
        val f = FakeFacts((1..3).map { tab(it) })
        var saved = 0
        val p = TabMemoryPolicy(startK = 3, saved = null) { saved = it }
        p.attach(f, backgroundScope)
        assertTrue(f.discarded.isEmpty())
        f.tabs.value = f.tabs.value + tab(4, lastUsedAt = 10)
        assertEquals(listOf(1), f.discarded)
        f.events.emit(Handled(BrowserEvent.RendererGone(1, 2, 0, crashed = false, repeated = false), Answer.Dismiss))
        assertEquals(2, p.k)
        assertEquals(2, saved)
        f.events.emit(Handled(BrowserEvent.RendererGone(2, 2, 0, crashed = true, repeated = false), Answer.Dismiss))
        assertEquals("a crash is no memory kill", 2, p.k)
        f.front.value = Front(4, 0)
        f.events.emit(Handled(BrowserEvent.MemoryPressure(3, 0, 0, level = 20), Answer.Dismiss))
        assertEquals(listOf(4), f.tabs.value.filter { it.asleep == null }.map { it.id })
        assertEquals(TabMemoryPolicy.MIN_K, TabMemoryPolicy(2, 2) {}.k)
    }
}

class PolicyChainTest {
    private class Recorder(val name: String, val log: MutableList<String>, val verdict: Verdict = Verdict.Allow) : ToolPolicy, BrowserPolicy {
        override fun onTurnStart(sessionId: String) { log += "$name start $sessionId" }
        override fun onTurnEnd(sessionId: String) { log += "$name end $sessionId" }
        override suspend fun before(call: PolicyCall): Verdict { log += "$name before"; return verdict }
        override fun after(call: PolicyCall, facts: CallFacts, result: ToolOutcome) = result.copy(lead = result.lead + name)
        override fun onEvent(e: BrowserEvent, proposed: Answer): Answer? = if (name == "b") Answer.ToUser else null
    }

    @Test fun `before runs in order and stops at the first Refuse or Confirm, after folds every link, turns reach all`() = kotlinx.coroutines.runBlocking {
        val log = mutableListOf<String>()
        val chain = Policies(listOf(Recorder("a", log), Recorder("b", log, Verdict.Refuse("no")), Recorder("c", log)), emptyList())
        assertEquals(Verdict.Refuse("no"), chain.before(call()))
        assertEquals(listOf("a before", "b before"), log)
        assertEquals("xabc", chain.after(call(), facts(), ToolOutcome(lead = "x")).lead)
        assertEquals("a failure goes through after too", "eabc", chain.after(call(), facts(), ToolOutcome(lead = "e", isError = true)).lead)
        log.clear()
        chain.turnStart("s"); chain.turnEnd("s")
        assertEquals(listOf("a start s", "b start s", "c start s", "a end s", "b end s", "c end s"), log)
    }

    @Test fun `onEvent folds the browser policies and an empty chain changes nothing`() = kotlinx.coroutines.runBlocking {
        val e = BrowserEvent.Dialog(1, 1, 0, com.ouor.localchat.browser.DialogKind.CONFIRM, "Delete?", null, "https://x/")
        val log = mutableListOf<String>()
        assertEquals(Answer.ToUser, Policies(emptyList(), listOf(Recorder("a", log), Recorder("b", log))).onEvent(e, Answer.Accept()))
        val empty = Policies(emptyList(), emptyList())
        assertNull(empty.onEvent(e, Answer.Accept()))
        assertEquals(Verdict.Allow, empty.before(call()))
        val r = ToolOutcome(page = "p")
        assertSame(r, empty.after(call(), facts(), r))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PoliciesTest {
    @Test fun `no policies, no contributions, one policy, only its own`() = runTest(UnconfinedTestDispatcher()) {
        val empty = Policies(emptyList(), emptyList())
        assertTrue(empty.promptLines().isEmpty() && empty.hiddenTools().isEmpty() && empty.settings().isEmpty())
        assertEquals(emptyList<Notice>(), empty.notices.first())
        val hides = object : ToolPolicy {
            override fun hiddenTools() = setOf("execute")
            override fun promptLines() = listOf("- line")
        }
        val one = Policies(listOf(hides), emptyList())
        assertEquals(setOf("execute"), one.hiddenTools())
        assertEquals(listOf("- line"), one.promptLines())
        assertTrue(one.settings().isEmpty())
        assertEquals(emptyList<Notice>(), one.notices.first())
    }
}
