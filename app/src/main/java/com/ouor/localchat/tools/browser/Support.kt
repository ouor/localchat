package com.ouor.localchat.tools.browser

import android.graphics.Bitmap
import android.os.SystemClock
import com.ouor.localchat.browser.BrowserTab
import com.ouor.localchat.browser.DialogKind
import com.ouor.localchat.browser.DialogNote
import com.ouor.localchat.browser.NavError
import com.ouor.localchat.browser.PageError
import com.ouor.localchat.browser.Readiness
import com.ouor.localchat.browser.StaleRef
import com.ouor.localchat.browser.TabRules
import com.ouor.localchat.browser.safety.ElementInfo
import com.ouor.localchat.browser.safety.IrreversibleAction
import com.ouor.localchat.browser.safety.SensitiveFields
import com.ouor.localchat.browser.safety.SensitiveUrls
import com.ouor.localchat.browser.script.Located
import com.ouor.localchat.browser.script.PageList
import com.ouor.localchat.browser.script.PageMark
import com.ouor.localchat.browser.script.PageScript
import com.ouor.localchat.core.model.ToolStatus
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
import java.net.URI

/** Shared by the browser tools: parameters, Main-thread hop, URL checks, the action wait and result shapes (tools-redesign 2, 3). */

private const val HIGHLIGHT_LEAD_MS = 250L
private const val LIST_BUDGET = 16_000
private const val ACT_UNKNOWN = "The page changed while acting, so it is not known whether the action happened. Check with browse() before trying again."

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

/** A path from an element list (G-m5) on the site of [base] (the work tab's page), checked like any URL. */
internal fun ToolContext.pathUrl(raw: String, base: String): String {
    val resolved = base.takeIf { it.startsWith("http", ignoreCase = true) }?.let { runCatching { URI(it).resolve(raw).toString() }.getOrNull() }
        ?: throw ToolError("$raw is a path and your tab has no web page to open it on; pass a full URL.")
    return checkedUrl(resolved)
}

internal fun sensitiveMessage(host: String) =
    "$host ${SensitiveUrls.REFUSAL} (banking, payment, email, sign-in, government ...). The AI may not open or read it; ask the user to do this part."

/** B-S1 (SE 15): never into password/OTP/card fields or opaque frames. */
internal fun guardInput(ref: String, target: ElementInfo, scriptSensitive: Boolean) {
    if (SensitiveFields.refusesInput(target, scriptSensitive)) {
        throw ToolError(
            "Refused: $ref is a password, one-time-code or card field, or a frame whose content cannot be " +
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
    return requireApproval(ApprovalRequest(tool, what, tab.url, previewId = at?.let { approvalPreview(tab, it) }, reason = reason))
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

/**
 * The element of [ref]. A ref that is not one, is from an earlier page of the tab or is gone is refused with the
 * current element list, so the next call can use a fresh ref (G-M1).
 */
internal suspend fun ToolContext.locate(tab: BrowserTab, ref: String, scroll: Boolean = true): Located = try {
    PageScript.call(tab, "locate", buildJsonObject { put("target", ref); put("scroll", scroll) })
} catch (e: StaleRef) {
    throw staleRef(tab, e)
}

/** A refused ref, answered with the current element list (G-M1) wherever it comes from: locate, type, a script click. */
private suspend fun ToolContext.staleRef(tab: BrowserTab, e: StaleRef): ToolError {
    val page = pageResult(tab, Readiness.READY, now())
    return ToolError(e.message.orEmpty(), page.copy(isError = true, lead = "${e.message} Use a ref from the element list below."))
}

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
 * for, otherwise the page only has to go quiet. A script cut short by a dialog it opened is not a failure; one whose
 * answer was lost (the document changed) may have run, so it is never run again (B-m1).
 */
internal suspend fun ToolContext.act(tab: BrowserTab, action: suspend () -> Unit): Acted {
    val before = mark(tab)
    val t0 = now()
    try {
        action()
    } catch (e: StaleRef) {
        throw staleRef(tab, e)
    } catch (e: PageError) {
        if (tab.dialog.value == null && tab.dialogsSince(t0).isEmpty()) {
            throw if (e.message == PageScript.NOT_RUN) PageError(ACT_UNKNOWN) else e
        }
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
internal fun pageHeader(tab: BrowserTab, readiness: Readiness, error: NavError?, again: Boolean = false): String {
    val marks = listOfNotNull(
        "(still loading after ${TabRules.LOAD_CAP / 1000} s)".takeIf { readiness == Readiness.LOADING },
        // G-m13: the second time on the same document, waiting longer will not help.
        (if (again) "(this page keeps changing; this is as complete as it gets)" else "(page still changing; content may be incomplete)")
            .takeIf { readiness == Readiness.CHANGING },
        error?.takeIf { it.http }?.let { "(HTTP ${it.code}${it.desc.takeIf(String::isNotBlank)?.let { d -> " $d" }.orEmpty()})" },
    )
    return "Page: ${tab.title.ifBlank { "(untitled)" }} — ${tab.url}" + marks.joinToString("") { " $it" }
}

/**
 * The page as a result (browse, handoff return and the end of every action): inside the boundary [top] (page-derived
 * lines such as dialogs), the header, a blank line and the element list with refs within [LIST_BUDGET] characters;
 * after it the note on what was left out (tools-redesign 3-3). [more] continues the last list of the same page (G-M3).
 */
internal suspend fun ToolContext.pageResult(tab: BrowserTab, readiness: Readiness, since: Long, more: Boolean = false, top: String = ""): ToolOutcome {
    val again = if (readiness == Readiness.CHANGING) {
        val doc = mark(tab)?.doc
        (doc != null && doc == tab.changingDoc).also { tab.changingDoc = doc }
    } else false
    val err = tab.navError(since)
    val r = PageScript.call<PageList>(tab, "pageList", buildJsonObject { put("budget", LIST_BUDGET); put("more", more) })
    val head = pageHeader(tab, readiness, err, again) + if (r.continued) TabRules.CONTINUED else ""
    val left = r.above + r.below
    val more = if (left == 0) "" else
        "($left more elements: ${r.above} above and ${r.below} below the visible part." +
            hint("browse", " browse() with no url lists the remaining elements") + hint("read", if ("browse" in exposed) ", or read() for the page text" else " Use read() for the page text") +
            ".)"
    // F6: what is past the bound is not promised to browse().
    val beyond = if (r.beyond == 0) "" else "(${r.beyond} more elements on this very long page cannot be listed." + hint("read", " Use read() for its text.") + ")"
    val note = listOf(more, beyond).filter { it.isNotEmpty() }.joinToString("\n")
    return ToolOutcome(
        text = listOf(top, head).filter { it.isNotEmpty() }.joinToString("\n") + "\n\n" + r.text.ifBlank { "(no elements)" },
        isError = err?.http == true,
        summary = tab.title,
        note = note,
    )
}

/**
 * click, type and execute: [first] (what was done, app-written) + the change, then the page. [top] is page-derived
 * text for the inside of the boundary (a field's value, a script's result); [note] goes after it. A failed new page
 * is an error with the load failure (tools-redesign 3-4). A vision model gets a picture of the page.
 */
internal suspend fun ToolContext.actionResult(
    tab: BrowserTab,
    first: String,
    acted: Acted,
    summary: String,
    top: String = "",
    note: String = "",
): ToolOutcome {
    val inside = (listOf(top) + acted.dialogs.map { it.describe() }).filter { it.isNotEmpty() }.joinToString("\n")
    if (acted.change == Change.FAILED) {
        val why = tab.navError(acted.since)?.let { TabRules.navErrorText(it, retried = false) } ?: "The page did not load."
        return ToolOutcome(listOf(inside, why).filter { it.isNotEmpty() }.joinToString("\n"), isError = true, summary = summary,
            lead = "$first, then the new page failed to open.", note = note)
    }
    val page = pageResult(tab, acted.readiness, acted.since, top = inside)
    return withPicture(tab, page.copy(lead = "$first ${acted.change.line}", summary = summary,
        note = listOf(note, page.note).filter { it.isNotEmpty() }.joinToString("\n")), keep = false)
}

/**
 * A vision model gets a picture of the visible part with every result that lists the page (G-m10); ContextAssembler
 * sends only the newest. [keep] also shows it in the chat (browse).
 */
internal suspend fun ToolContext.withPicture(tab: BrowserTab, o: ToolOutcome, keep: Boolean): ToolOutcome {
    if (!vision || o.isError) return o
    val bitmap = onMain { tab.capture() } ?: return o // not laid out: no picture
    val bytes = withContext(Dispatchers.Default) { jpeg(bitmap, PICTURE_QUALITY) }
    val id = saveArtifact("page-${System.currentTimeMillis()}.jpg", "image/jpeg", bytes)
    return o.copy(artifactIds = if (keep) o.artifactIds + id else o.artifactIds, imageForModelId = id)
}

private const val PICTURE_QUALITY = 80

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
        status(ToolStatus.RETRYING)
        delay(delayMs)
        since = now()
        tab.load(url)
        r = ready(tab, since)
        return Opened(r, since, retried = true)
    }
    return Opened(r, since, retried = false)
}

internal class Opened(val readiness: Readiness, val since: Long, val retried: Boolean)
