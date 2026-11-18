package com.ouor.localchat.tools.browser

import android.graphics.Bitmap
import android.os.SystemClock
import com.ouor.localchat.browser.BrowserTab
import com.ouor.localchat.browser.DialogKind
import com.ouor.localchat.browser.DialogNote
import com.ouor.localchat.browser.NavError
import com.ouor.localchat.browser.PageError
import com.ouor.localchat.browser.Readiness
import com.ouor.localchat.browser.TabRules
import com.ouor.localchat.browser.safety.ElementInfo
import com.ouor.localchat.browser.safety.IrreversibleAction
import com.ouor.localchat.browser.safety.SensitiveFields
import com.ouor.localchat.browser.safety.SensitiveUrls
import com.ouor.localchat.browser.script.Located
import com.ouor.localchat.browser.script.PageList
import com.ouor.localchat.browser.script.PageMark
import com.ouor.localchat.browser.script.PageScript
import com.ouor.localchat.tools.ApprovalRequest
import com.ouor.localchat.tools.ParamsBuilder
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolError
import com.ouor.localchat.tools.ToolOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream

/** Shared by the browser tools: parameters, Main-thread hop, URL checks, the action wait and result shapes (tools-redesign 2, 3). */

private const val HIGHLIGHT_LEAD_MS = 250L
private const val LIST_BUDGET = 8_000

internal fun ParamsBuilder.refParam() = string("ref", "Element ref, e.g. \"e12\".", required = true)

/** WebView work runs on Main; suspending there is fine (every wait is a callback or a delay). */
internal suspend fun <T> onMain(block: suspend CoroutineScope.() -> T): T = withContext(Dispatchers.Main.immediate, block)

internal fun now() = SystemClock.uptimeMillis()

/** [text] when the model can call [tool] now, else nothing: guidance never names a tool it cannot call (D8). */
internal fun ToolContext.hint(tool: String, text: String) = if (tool in exposed) text else ""

private val NON_WEB = Regex("^(javascript|data|file|about|blob|intent|mailto|tel|sms|content|chrome|market):", RegexOption.IGNORE_CASE)

/** A URL the AI may open: https:// added when the scheme is missing, only http(s), never a sensitive site (B-S3). */
internal fun ToolContext.checkedUrl(raw: String): String {
    val trimmed = raw.trim()
    val url = if ("://" in trimmed || NON_WEB.containsMatchIn(trimmed)) trimmed else "https://$trimmed"
    val scheme = url.substringBefore(':').lowercase()
    if (scheme != "http" && scheme != "https") throw ToolError("Only http and https URLs can be opened.")
    val host = SensitiveUrls.hostOf(url) ?: throw ToolError("Not a valid URL: $raw")
    if (browser.sensitiveUrls.isSensitive(url)) throw ToolError(sensitiveMessage(host))
    return url
}

internal fun sensitiveMessage(host: String) =
    "$host ${SensitiveUrls.REFUSAL} (banking, payment, email, sign-in, government ...). The AI may not open or read it; ask the user to do this part."

/** B-S1 (SE 15): never into password/OTP/card fields or opaque frames. */
internal fun guardInput(target: ElementInfo?, scriptSensitive: Boolean) {
    if (target != null && SensitiveFields.refusesInput(target, scriptSensitive)) {
        throw ToolError(
            "Refused: ${target.label()} is a password, one-time-code or card field, or a frame whose content cannot be " +
                "checked. Only the user may fill it; use request_handoff.",
        )
    }
}

/**
 * B-S2 (SE 15): asks SC-13 when [reason] is set; false when the user denied. With [at] the request carries a cropped
 * image of the element (SC-13 design), never of a password/OTP/card field. Main thread.
 */
internal suspend fun ToolContext.guardActivate(
    tool: String,
    tab: BrowserTab,
    reason: IrreversibleAction.Reason?,
    what: String,
    at: Located? = null,
): Boolean {
    if (reason == null) return true
    return requireApproval(ApprovalRequest(tool, what, tab.url, screenshotId = at?.let { approvalPreview(tab, it) }, reason = reason))
}

private suspend fun ToolContext.approvalPreview(tab: BrowserTab, at: Located): String? {
    if (at.sensitive || SensitiveFields.isSensitive(at.info)) return null
    val crop = tab.captureElement(at.rect, at.innerWidth) ?: return null
    // Stored like any artifact; nothing references it after the answer, so attachment GC removes it.
    return saveArtifact("approval-preview.jpg", "image/jpeg", jpeg(crop, PREVIEW_QUALITY))
}

private const val PREVIEW_QUALITY = 85

/** [bitmap] as JPEG bytes; the bitmap is recycled. */
internal fun jpeg(bitmap: Bitmap, quality: Int): ByteArray = ByteArrayOutputStream().use { out ->
    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
    bitmap.recycle()
    out.toByteArray()
}

/** One-line element description for results and approvals: `button "Log in"`. */
internal fun ElementInfo.label(): String = (role ?: tag) + (name?.let { " \"$it\"" } ?: "")

internal suspend fun locate(tab: BrowserTab, ref: String, scroll: Boolean = true): Located =
    PageScript.call(tab, "locate", buildJsonObject { put("target", ref); put("scroll", scroll) })

internal fun refArgs(ref: String, extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject { put("target", ref); extra() }

/** B-09 ①: show the outline, then give the user a moment to see it before the page changes. */
internal suspend fun ToolContext.highlight(tab: BrowserTab, at: Located, labelRes: Int) {
    browser.showHighlight(tab.id, at, labelRes)
    delay(HIGHLIGHT_LEAD_MS)
}

/** The page answered mark(), or null (busy, swapping documents, a dialog open). */
internal suspend fun mark(tab: BrowserTab): PageMark? =
    try { PageScript.call<PageMark>(tab, "mark", timeoutMs = TabRules.MARK_EVAL, retry = false) } catch (_: PageError) { null }

/** [BrowserTab.awaitReady] with the step line showing what it waits for. */
internal suspend fun ToolContext.ready(tab: BrowserTab, since: Long): Readiness =
    tab.awaitReady(since) { status(it) }.also { status(null) }

/** What an action did to the page (tools-redesign 2-3). */
enum class Change(val line: String, val words: String) {
    NEW_PAGE("→ new page.", "new page"),
    UPDATED("→ page updated.", "page updated"),
    NONE("→ no visible change.", "no visible change"),
    FAILED("", ""),
}

internal class Acted(val change: Change, val readiness: Readiness, val since: Long, val dialogs: List<DialogNote>)

/**
 * Runs [action] on [tab] and waits until the page is ready after it: a document requested after the action is waited
 * for, otherwise the page only has to go quiet. A script cut short by a dialog it opened is not a failure.
 */
internal suspend fun ToolContext.act(tab: BrowserTab, action: suspend () -> Unit): Acted {
    val before = mark(tab)
    val t0 = now()
    try {
        action()
    } catch (e: PageError) {
        if (tab.dialog.value == null && tab.dialogsSince(t0).isEmpty()) throw e
    }
    val r = ready(tab, t0)
    val after = if (r == Readiness.FAILED) null else mark(tab)
    val change = when {
        r == Readiness.FAILED -> Change.FAILED
        before == null || after == null -> Change.UPDATED
        after.doc != before.doc -> Change.NEW_PAGE
        after.n != before.n -> Change.UPDATED
        else -> Change.NONE
    }
    return Acted(change, r, t0, tab.dialogsSince(t0))
}

/** tools-redesign 2-4: a dialog the page showed, for the result. */
internal fun DialogNote.describe(): String {
    val m = message.take(300)
    return when {
        extra -> "Another dialog from the page was dismissed: \"$m\"."
        kind == DialogKind.ALERT -> "The page showed an alert: \"$m\"."
        !accepted -> "The page asked (${kind.name.lowercase()}): \"$m\" — the user chose Cancel."
        kind == DialogKind.PROMPT -> "The page asked (prompt): \"$m\" — the user answered \"${text.orEmpty()}\"."
        else -> "The page asked (confirm): \"$m\" — the user chose OK."
    }
}

/** `Page: {title} — {url}` plus what is off about it. Main thread. */
internal fun pageHeader(tab: BrowserTab, readiness: Readiness, error: NavError?): String {
    val marks = listOfNotNull(
        "(still loading after ${TabRules.LOAD_CAP / 1000} s)".takeIf { readiness == Readiness.LOADING },
        "(page still changing; content may be incomplete)".takeIf { readiness == Readiness.CHANGING },
        error?.takeIf { it.http }?.let { "(HTTP ${it.code}${it.desc.takeIf(String::isNotBlank)?.let { d -> " $d" }.orEmpty()})" },
    )
    return "Page: ${tab.title.ifBlank { "(untitled)" }} — ${tab.url}" + marks.joinToString("") { " $it" }
}

/** The page's elements with refs, within [LIST_BUDGET] characters (tools-redesign 3-3). */
internal suspend fun ToolContext.elementList(tab: BrowserTab): String {
    val r = PageScript.call<PageList>(tab, "pageList", buildJsonObject { put("budget", LIST_BUDGET) })
    val left = r.above + r.below
    val more = if (left == 0) "" else
        "\n($left more elements not listed: ${r.above} above and ${r.below} below the visible part.${hint("read", " Use read() for the page text.")})"
    return r.text.ifBlank { "(no elements)" } + more
}

/** Header, a blank line, the element list: browse, handoff return and the end of every action result. */
internal suspend fun ToolContext.pageResult(tab: BrowserTab, readiness: Readiness, since: Long): String =
    pageHeader(tab, readiness, tab.navError(since)) + "\n\n" + elementList(tab)

/**
 * click and type: [first] (what was done) + the change, dialog lines, then the page. A failed new page is an error
 * with the load failure (tools-redesign 3-4).
 */
internal suspend fun ToolContext.actionResult(tab: BrowserTab, first: String, acted: Acted, summary: String): ToolOutcome {
    val dialogs = acted.dialogs.joinToString("") { "\n" + it.describe() }
    if (acted.change == Change.FAILED) {
        val err = tab.navError(acted.since)
        val why = err?.let { TabRules.navErrorText(it, retried = false) } ?: "the page did not load."
        return ToolOutcome("$first then the new page failed to open: $why$dialogs", isError = true, summary = summary)
    }
    val err = tab.navError(acted.since)
    return ToolOutcome(
        "$first ${acted.change.line}$dialogs\n" + pageResult(tab, acted.readiness, acted.since),
        isError = err?.http == true,
        summary = summary,
    )
}

/**
 * Loads [url] in [tab] (app-opened: one automatic retry of a network error or 429/5xx; tools-redesign 2-2) and waits
 * until ready. Returns the readiness, the load's start and whether the retry was used.
 */
internal suspend fun ToolContext.open(tab: BrowserTab, url: String, alreadyLoading: Long? = null): Opened {
    var since = alreadyLoading ?: now().also { tab.load(url) }
    var r = ready(tab, since)
    val err = tab.navError(since)
    val delayMs = err?.let(TabRules::netRetry)
    if (delayMs != null && (r == Readiness.FAILED || err.http)) {
        status(com.ouor.localchat.core.model.ToolStatus.RETRYING)
        delay(delayMs)
        since = now()
        tab.load(url)
        r = ready(tab, since)
        return Opened(r, since, retried = true)
    }
    return Opened(r, since, retried = false)
}

internal class Opened(val readiness: Readiness, val since: Long, val retried: Boolean)
