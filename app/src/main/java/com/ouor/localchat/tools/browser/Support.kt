package com.ouor.localchat.tools.browser

import android.graphics.Bitmap
import android.os.SystemClock
import com.ouor.localchat.browser.Answer
import com.ouor.localchat.browser.BrowserEvent
import com.ouor.localchat.browser.BrowserTab
import com.ouor.localchat.browser.DialogKind
import com.ouor.localchat.browser.DownloadNote
import com.ouor.localchat.browser.Handled
import com.ouor.localchat.browser.NavError
import com.ouor.localchat.browser.PageError
import com.ouor.localchat.browser.Readiness
import com.ouor.localchat.browser.StaleRef
import com.ouor.localchat.browser.TabRules
import com.ouor.localchat.browser.script.ElementInfo
import com.ouor.localchat.browser.script.Located
import com.ouor.localchat.browser.script.PageList
import com.ouor.localchat.browser.script.PageMark
import com.ouor.localchat.browser.script.PageScript
import com.ouor.localchat.core.model.ToolStatus
import com.ouor.localchat.tools.ParamsBuilder
import com.ouor.localchat.tools.Targets
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

/** Shared by the browser tools (browser-layers 3-6): parameters, the Main-thread hop, the action wait and result shapes. */

private const val HIGHLIGHT_LEAD_MS = 250L
private const val ACT_UNKNOWN = "The page changed while acting, so it is not known whether the action happened. Check with look() before trying again."

/** Element list budget in characters (3-4): about one phone screen and what is just below it. */
fun listBudget(contextWindow: Int?): Int = contextWindow?.let { (it / 8).coerceIn(4_000, 12_000) } ?: 8_000

internal const val TAB_PARAM = "Tab number from tabs() or a result's Page line. Omit for the tab you or the user used last."

internal fun ParamsBuilder.tabParam(description: String = TAB_PARAM) = string("tab", description)

internal fun ParamsBuilder.refParam(description: String = "Element ref such as 4e7 from the latest list; it names its tab.", required: Boolean = true) =
    string("ref", description, required = required)

/** WebView work runs on Main; suspending there is fine (every wait is a callback or a delay). */
internal suspend fun <T> onMain(block: suspend CoroutineScope.() -> T): T = withContext(Dispatchers.Main.immediate, block)

internal fun now() = SystemClock.uptimeMillis()

/** The call's tab, which the executor resolved, locked and woke. */
internal fun ToolContext.tab(): BrowserTab = target ?: throw ToolError(Targets.NO_TAB)

/** [bitmap] as JPEG bytes; the bitmap is recycled. */
internal fun jpeg(bitmap: Bitmap, quality: Int): ByteArray = ByteArrayOutputStream().use { out ->
    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
    bitmap.recycle()
    out.toByteArray()
}

/** One-line element description for results: `button "Log in"`. */
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

/** Show the outline, then give the user a moment to see it before the page changes. */
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

/** What an action did to the page. */
enum class Change(val line: String, val words: String) {
    NEW_PAGE("→ new page.", "new page"),
    UPDATED("→ page updated.", "page updated"),
    NONE("→ no visible change.", "no visible change"),
    FAILED("", ""),
}

internal class Acted(val change: Change, val readiness: Readiness, val since: Long, val events: List<Handled>, val downloads: List<DownloadNote>)

/**
 * How [act] answers the tab's events while the AI acts (3-6): a dialog is part of the flow the AI chose (money and
 * account deletion were asked before the action), an injected tap is not the user's, so other apps stay closed;
 * permissions and files are the user's. Policies still fold over this answer (layer 1's engine-wide listeners).
 */
internal fun actAnswer(e: BrowserEvent): Answer? = when (e) {
    is BrowserEvent.Dialog -> Answer.Accept(e.defaultText.takeIf { e.kind == DialogKind.PROMPT })
    is BrowserEvent.ExternalLink -> Answer.Dismiss
    is BrowserEvent.Popup, is BrowserEvent.Download -> Answer.Accept()
    is BrowserEvent.Permission, is BrowserEvent.FileChooser -> Answer.ToUser
    else -> null
}

/**
 * Runs [action] on [tab] and waits until the page is ready after it: a document requested after the action is waited
 * for, otherwise the page only has to go quiet. Events in between get [actAnswer]. A script cut short by a dialog is
 * not a failure; one whose answer was lost (the document changed) may have run, so it is never run again (B-m1).
 */
internal suspend fun ToolContext.act(tab: BrowserTab, action: suspend () -> Unit): Acted {
    val before = mark(tab)
    val t0 = now()
    val listening = browser.listen(tab.id) { e, _ -> actAnswer(e) }
    try {
        try {
            action()
        } catch (e: StaleRef) {
            throw staleRef(tab, e)
        } catch (e: PageError) {
            if (tab.dialog.value == null && tab.eventsSince(t0).none { it.event is BrowserEvent.Dialog }) {
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
        return Acted(change, r, t0, tab.eventsSince(t0), tab.downloadsSince(t0))
    } finally {
        listening.close()
    }
}

/** An answered event of the page, for the inside of the boundary; null for one that says nothing about the page. */
internal fun Handled.describe(): String? {
    val e = event
    return when (e) {
        is BrowserEvent.Dialog -> {
            val m = e.message.take(300)
            when {
                answer == Answer.ToUser -> null
                e.kind == DialogKind.ALERT -> "The page showed an alert: \"$m\"."
                answer !is Answer.Accept -> "The page asked (${e.kind.name.lowercase()}): \"$m\" — it was answered Cancel."
                e.kind == DialogKind.PROMPT -> "The page asked (prompt): \"$m\" — it was answered \"${(answer as Answer.Accept).text.orEmpty()}\"."
                else -> "The page asked (${e.kind.name.lowercase()}): \"$m\" — it was answered OK."
            }
        }
        is BrowserEvent.ExternalLink -> if (answer is Answer.Accept) "The link opened another app (${e.uri.take(80)})." else "The link opens another app (${e.uri.take(80)}); it was not opened."
        is BrowserEvent.Popup -> if (answer is Answer.Accept) "The page opened tab ${e.newTabId}." else null
        else -> null
    }
}

/** An event handed to the user, for the note: the page waits for them. */
internal fun Handled.waitingForUser(): String? {
    if (answer != Answer.ToUser) return null
    return when (event) {
        is BrowserEvent.Permission -> "The page is waiting for the user to allow the camera or microphone."
        is BrowserEvent.FileChooser -> "The page is waiting for the user to pick a file."
        is BrowserEvent.Dialog -> "The page is waiting for the user to answer a dialog."
        else -> null
    }
}

/** A download for a result: what it is and where it went (2-5). */
internal fun DownloadNote.describe(): String {
    val what = listOfNotNull(mime, size.takeIf { it >= 0 }?.let(::bytes)).joinToString(", ").ifEmpty { "unknown type" }
    return when (state) {
        DownloadNote.State.SAVED -> "This is a file ($what), saved to Downloads as $fileName."
        DownloadNote.State.STARTED, DownloadNote.State.ASKED -> "This is a file ($what), being saved to Downloads as $fileName."
        DownloadNote.State.NOT_SAVED -> "This is a file ($what); it was not saved" + (reason?.let { " ($it)" } ?: "") + "."
        DownloadNote.State.FAILED -> "This is a file ($what); saving it failed" + (reason?.let { " ($it)" } ?: "") + "."
    } + if (again) " (the same file was asked for moments ago, so it was not fetched again)" else ""
}

private fun bytes(n: Long): String = when {
    n >= 1_000_000 -> "%.1f MB".format(n / 1_000_000.0)
    n >= 1_000 -> "${n / 1_000} KB"
    else -> "$n bytes"
}

/** `Page: {title} — {url} (tab {id}, on the user's screen, still loading after 15 s, HTTP 404)`. Main thread. */
internal fun ToolContext.pageHeader(tab: BrowserTab, readiness: Readiness, error: NavError?, again: Boolean = false): String {
    val marks = listOfNotNull(
        "tab ${tab.id}",
        "on the user's screen".takeIf { browser.shown.value == tab.id },
        "still loading after ${TabRules.LOAD_CAP / 1000} s".takeIf { readiness == Readiness.LOADING },
        // G-m13: the second time on the same document, waiting longer will not help.
        (if (again) "this page keeps changing; this is as complete as it gets" else "page still changing; content may be incomplete")
            .takeIf { readiness == Readiness.CHANGING },
        error?.takeIf { it.http }?.let { "HTTP ${it.code}${it.desc.takeIf(String::isNotBlank)?.let { d -> " $d" }.orEmpty()}" },
    )
    return "Page: ${tab.title.ifBlank { "(untitled)" }} — ${tab.url} (${marks.joinToString(", ")})"
}

/**
 * The page as a result (look, open, navigate and the end of every action): inside the boundary [top] (page-derived
 * lines such as dialogs), the header, a blank line and the element list with refs within [listBudget]; after it the
 * note on what was left out. [screen]: every line on screen first, text included (scroll).
 */
internal suspend fun ToolContext.pageResult(tab: BrowserTab, readiness: Readiness, since: Long, top: String = "", screen: Boolean = false): ToolOutcome {
    val again = if (readiness == Readiness.CHANGING) {
        val doc = mark(tab)?.doc
        (doc != null && doc == tab.changingDoc).also { tab.changingDoc = doc }
    } else false
    val err = tab.navError(since)
    val r = PageScript.call<PageList>(tab, "pageList", buildJsonObject { put("budget", listBudget(contextWindow)); put("screen", screen) })
    val head = pageHeader(tab, readiness, err, again)
    val left = r.above + r.below
    val more = if (left == 0) "" else
        "($left more elements: ${r.above} above and ${r.below} below the listed part. scroll() brings them on screen; read() gives the page text.)"
    val beyond = if (r.beyond == 0) "" else "(${r.beyond} more elements on this very long page cannot be listed. Use read() for its text.)"
    return ToolOutcome(
        page = listOf(top, head).filter { it.isNotEmpty() }.joinToString("\n") + "\n\n" + r.text.ifBlank { "(no elements)" },
        isError = err?.http == true,
        summary = tab.title,
        note = listOf(more, beyond).filter { it.isNotEmpty() }.joinToString("\n"),
    )
}

/**
 * click, type, scroll, navigate and execute: [first] (what was done, app-written) + the change, then the page. [top] is
 * page-derived text for the inside of the boundary (a field's value, a script's result); [note] goes after it. A
 * failed new page is an error with the load failure. A vision model gets a picture of the page.
 */
internal suspend fun ToolContext.actionResult(
    tab: BrowserTab,
    first: String,
    acted: Acted,
    summary: String,
    top: String = "",
    note: String = "",
    screen: Boolean = false,
    change: Boolean = true,
): ToolOutcome {
    val inside = (listOf(top) + acted.events.mapNotNull { it.describe() } + acted.downloads.map { it.describe() })
        .filter { it.isNotEmpty() }.joinToString("\n")
    val waiting = acted.events.mapNotNull { it.waitingForUser() }.distinct()
    val notes = { more: String -> (listOf(note) + waiting + more).filter { it.isNotEmpty() }.joinToString("\n") }
    if (acted.change == Change.FAILED) {
        val why = tab.navError(acted.since)?.let { TabRules.navErrorText(it, retried = false) } ?: "The page did not load."
        return ToolOutcome(lead = "$first, then the new page failed to open.", page = listOf(inside, why).filter { it.isNotEmpty() }.joinToString("\n"),
            isError = true, summary = summary, note = notes(""))
    }
    val page = pageResult(tab, acted.readiness, acted.since, top = inside, screen = screen)
    val lead = if (change) "$first ${acted.change.line}" else first
    return withPicture(tab, page.copy(lead = lead, summary = summary, note = notes(page.note)), keep = false)
}

/**
 * A vision model gets a picture of the visible part with every result that lists the page (G-m10); ContextAssembler
 * sends only the newest. [keep] also shows it in the chat (open, look).
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
 * Loads [url] in [tab] (one automatic retry of a network error or 429/5xx) and waits until ready. Returns the
 * readiness, the load's start and whether the retry was used.
 */
internal suspend fun ToolContext.open(tab: BrowserTab, url: String): Opened {
    var since = now().also { tab.load(url) }
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

/** A URL argument as a web URL on [tab]'s site, or a model-facing error. */
internal fun webUrlOrThrow(raw: String, base: String?): String =
    Targets.webUrl(raw, base) ?: throw ToolError(
        if (Targets.isPath(raw)) "$raw is a path and the tab has no web page to open it on; pass a full URL."
        else "Not an http(s) URL: $raw",
    )
