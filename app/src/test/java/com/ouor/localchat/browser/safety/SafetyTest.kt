package com.ouor.localchat.browser.safety

import com.ouor.localchat.browser.BrowserEngine
import com.ouor.localchat.browser.safety.IrreversibleAction.Reason
import com.ouor.localchat.core.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SafetyTest {

    // --- B-S3 SensitiveUrls

    private val builtIn = SensitiveUrls.parseList(File("src/main/assets/browser/sensitive_domains.txt").readText())
    private val urls = SensitiveUrls(builtIn, user = listOf("*.Corp.Example", "https://intra.test/x"))

    @Test
    fun `built-in list loads and has every category`() {
        assertTrue(builtIn.size > 50)
        listOf(
            "https://accounts.google.com/signin", "https://mail.google.com/mail/u/0", "https://www.paypal.com/",
            "https://obank.kbstar.com/x", "https://upbit.com/exchange", "https://www.hometax.go.kr/",
            "https://vault.bitwarden.com/#/", "https://www.gov.kr/portal",
        ).forEach { assertTrue(it, urls.isSensitive(it)) }
    }

    @Test
    fun `suffix match is on label boundaries only`() {
        assertTrue(urls.isSensitive("https://a.b.paypal.com/"))
        assertTrue(urls.isSensitive("HTTPS://PAYPAL.COM."))
        assertFalse(urls.isSensitive("https://notpaypal.com/"))
        assertFalse(urls.isSensitive("https://paypal.com.evil.example/"))
        assertFalse(urls.isSensitive("https://www.google.com/search?q=accounts.google.com"))
        assertFalse(urls.isSensitive("https://en.wikipedia.org/wiki/PayPal"))
    }

    @Test
    fun `user additions accept wildcard and url forms`() {
        assertTrue(urls.isSensitive("https://wiki.corp.example/page"))
        assertTrue(urls.isSensitive("http://intra.test/"))
        assertFalse(urls.isSensitive("https://example.com/"))
    }

    @Test
    fun `unparseable or hostless urls are not sensitive and hostOf strips www`() {
        assertFalse(urls.isSensitive("about:blank"))
        assertFalse(urls.isSensitive("not a url"))
        assertEquals("paypal.com", SensitiveUrls.hostOf("https://www.PayPal.com/x"))
    }

    @Test
    fun `settings to engine - built-in list off keeps user domains and fail-closed (FB2-2)`() {
        val user = AppSettings(userSensitiveDomains = listOf("*.Corp.Example"))
        val off = BrowserEngine.sensitiveRule(builtIn, user.copy(builtInSensitiveEnabled = false))
        assertFalse(off.isSensitive("https://www.paypal.com/"))
        assertFalse(off.isSensitive("https://www.gov.kr/portal"))
        assertTrue(off.isSensitive("https://wiki.corp.example/page"))
        assertTrue(off.isSensitive("https://bad host/x"))
        assertFalse(off.isSensitive("about:blank"))
        val on = BrowserEngine.sensitiveRule(builtIn, user)
        assertTrue(on.isSensitive("https://www.paypal.com/"))
        assertTrue(on.isSensitive("https://wiki.corp.example/page"))
        assertTrue(on.isSensitive("https://bad host/x"))
    }

    // --- B-S1 SensitiveFields

    private fun field(
        type: String? = "text", nameAttr: String? = null, id: String? = null, name: String? = null,
        autocomplete: String? = null, placeholder: String? = null,
    ) = ElementInfo(tag = "input", role = "textbox", type = type, name = name, nameAttr = nameAttr, id = id,
        autocomplete = autocomplete, placeholder = placeholder)

    @Test
    fun `password otp and card fields are sensitive`() {
        listOf(
            field(type = "password"),
            field(autocomplete = "current-password"),
            field(autocomplete = "section-pay billing cc-number"),
            field(autocomplete = "one-time-code"),
            field(nameAttr = "otp"),
            field(id = "card_number"),
            field(nameAttr = "cardNumber"),
            field(nameAttr = "cvv2"),
            field(id = "user-pin"),
            field(name = "비밀번호 확인"),
            field(placeholder = "인증번호 6자리"),
            field(name = "Security code"),
        ).forEach { assertTrue(it.toString(), SensitiveFields.isSensitive(it)) }
    }

    @Test
    fun `ordinary fields are not sensitive`() {
        listOf(
            field(nameAttr = "q"),
            field(nameAttr = "shipping_address"),
            field(id = "spinner"),
            field(name = "Email"),
            field(placeholder = "Search"),
            field(autocomplete = "username"),
            field(nameAttr = "topic"),
        ).forEach { assertFalse(it.toString(), SensitiveFields.isSensitive(it)) }
    }

    // --- B-S2 IrreversibleAction

    private fun button(name: String, tag: String = "button", type: String? = null, method: String? = null, role: String? = "button") =
        ElementInfo(tag = tag, role = role, type = type, name = name, formMethod = method)

    @Test
    fun `post form submit needs approval, get form does not`() {
        assertEquals(Reason.POST_FORM, IrreversibleAction.reasonForClick(button("Go", method = "post")))
        assertEquals(Reason.POST_FORM, IrreversibleAction.reasonForClick(button("Go", tag = "input", type = "submit", method = "post")))
        assertNull(IrreversibleAction.reasonForClick(button("Search", method = "get")))
        assertNull(IrreversibleAction.reasonForClick(button("Go", type = "button", method = "post")))
    }

    @Test
    fun `risky keywords in ko and en`() {
        listOf("Place order", "Buy now", "Delete", "결제하기", "주문", "삭제", "보내기", "게시", "확인", "Send message", "CONFIRM")
            .forEach { assertEquals(it, Reason.RISKY_KEYWORD, IrreversibleAction.reasonForClick(button(it))) }
        assertEquals(Reason.RISKY_KEYWORD, IrreversibleAction.reasonForClick(button("Delete account", tag = "a", role = "link")))
    }

    @Test
    fun `harmless labels and long text pass`() {
        listOf("Next", "Search", "Sort by price", "Ordered list", "Reposition", "Sender settings", "다음", "검색")
            .forEach { assertNull(it, IrreversibleAction.reasonForClick(button(it))) }
        val article = "How to rename a git branch locally and remotely — a complete guide for beginners"
        assertNull(IrreversibleAction.reasonForClick(button(article, tag = "a", role = "link")))
        assertNull(IrreversibleAction.reasonForClick(ElementInfo(tag = "div", name = "Delete")))
    }

    @Test
    fun `enter submits post forms except in textarea`() {
        assertEquals(Reason.POST_FORM, IrreversibleAction.reasonForEnter(field().copy(formMethod = "post")))
        assertNull(IrreversibleAction.reasonForEnter(field().copy(formMethod = "get")))
        assertNull(IrreversibleAction.reasonForEnter(ElementInfo(tag = "textarea", formMethod = "post")))
        assertNull(IrreversibleAction.reasonForEnter(null))
    }

    // --- B-S4 CookieMasker

    @Test
    fun `cookie values of six chars or more are masked`() {
        val header = "sid=abcdef123456; lang=ko; tok=a%2Bb%3Dcdefg"
        val out = CookieMasker.mask("x abcdef123456 y ko a+b=cdefg z", header)
        assertEquals("x [cookie] y ko [cookie] z", out)
        assertEquals("same", CookieMasker.mask("same", null))
    }

    // --- review fixes (track-c.md 8)

    @Test
    fun `input is refused into sensitive fields and frames (SE 1)`() {
        assertTrue(SensitiveFields.refusesInput(field(type = "password"), scriptSensitive = false))
        assertTrue(SensitiveFields.refusesInput(field(), scriptSensitive = true))
        assertTrue(SensitiveFields.refusesInput(ElementInfo(tag = "iframe"), scriptSensitive = false))
        assertFalse(SensitiveFields.refusesInput(field(nameAttr = "q"), scriptSensitive = false))
        assertFalse(SensitiveFields.refusesInput(null, scriptSensitive = false))
    }

    @Test
    fun `type's Enter asks only in a POST form, whatever the field is called`() {
        assertEquals(Reason.POST_FORM, IrreversibleAction.reasonForEnter(field().copy(formMethod = "post")))
        assertNull(IrreversibleAction.reasonForEnter(field().copy(formMethod = "get", name = "주문 검색")))
        assertNull(IrreversibleAction.reasonForEnter(ElementInfo(tag = "textarea", formMethod = "post")))
        assertNull(IrreversibleAction.reasonForEnter(null))
    }

    @Test
    fun `frames need approval and long labels are checked from the start (SE 7, SE 18)`() {
        assertEquals(Reason.FRAME, IrreversibleAction.reasonForClick(ElementInfo(tag = "iframe")))
        val padded = "결제하기 " + "x".repeat(80)
        assertEquals(Reason.RISKY_KEYWORD, IrreversibleAction.reasonForClick(button(padded)))
    }

    @Test
    fun `unparseable web urls fail closed and lenient hosts parse (SE 20)`() {
        assertTrue(urls.isSensitive("https://bad host/x"))
        assertFalse(urls.isSensitive("about:blank"))
        assertEquals("my_host.example", SensitiveUrls.hostOf("https://my_host.example/"))
    }

    @Test
    fun `json escaped cookie values are masked (SE 19)`() {
        assertEquals("""{"v":"[cookie]"}""", CookieMasker.mask("""{"v":"abc\\def1"}""", "t=abc\\def1"))
    }

    @Test
    fun `added built-in domains (SE 28)`() {
        listOf("https://docs.google.com/document/d/1", "https://drive.google.com/", "https://signin.aws.amazon.com/",
            "https://login.coupang.com/", "https://paypal.me/x").forEach { assertTrue(it, urls.isSensitive(it)) }
    }
}
