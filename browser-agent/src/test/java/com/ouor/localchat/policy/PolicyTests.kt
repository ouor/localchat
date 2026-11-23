package com.ouor.localchat.policy

import com.ouor.localchat.browser.Answer
import com.ouor.localchat.browser.Asleep
import com.ouor.localchat.browser.BrowserEvent
import com.ouor.localchat.browser.Front
import com.ouor.localchat.browser.Handled
import com.ouor.localchat.browser.ToolActivity
import com.ouor.localchat.tools.TargetRule
import com.ouor.localchat.tools.ToolFacts
import com.ouor.localchat.tools.ToolOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each policy on its own: applied when its case is there, the result untouched when not (browser-layers 10-1). */
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

    /** QA B4: a cookie holding the page's own address does not hide that address. */
    @Test fun `a value that is part of the call's addresses is not hidden`() {
        val u = "https://www.bmj.com/content/389/bmj-2024-082007"
        val cookies = mapOf(u to "last=www.bmj.com%2Fcontent%2F389%2Fbmj-2024-082007; sid=abcdef123456")
        val r = ToolOutcome(page = "Page: The BMJ - $u (tab 5) abcdef123456")
        val out = CookieMaskPolicy().after(call(), facts(tab(5, u), listOf(u), cookies::get), r)
        assertEquals("Page: The BMJ - $u (tab 5) [cookie hidden by the app]", out.page)
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
