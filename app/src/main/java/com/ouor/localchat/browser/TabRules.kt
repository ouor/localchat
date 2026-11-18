package com.ouor.localchat.browser

import com.ouor.localchat.browser.script.CssRect
import com.ouor.localchat.browser.script.PageMark

/** Pure rules behind BrowserEngine/BrowserTab, kept apart so JVM tests can run them. */
internal object TabRules {

    /** B-09 ③: a user touch on an AI tab offers "take control", unless the user already controls it (handoff, QA F2). */
    fun asksToTakeOver(owner: Owner, control: Control): Boolean = owner is Owner.Ai && control == Control.AI

    /** At most this many idle AI tabs of other chats stay open (BUG-13). */
    const val MAX_IDLE_AI_TABS = 3

    /** An AI tab unused this long counts as idle. */
    const val AI_IDLE_MS = 10 * 60_000L

    /** CSS px around the element in an approval preview. */
    private const val CROP_PAD = 24f

    /** Hidden tabs open at once (web_search, read(url), link prefetch; tool calls run one at a time). */
    const val MAX_HIDDEN_TABS = 3

    /**
     * Why a new tab may not open, null when it may. Visible tabs count toward [BrowserEngine.MAX_TABS]; hidden ones
     * only toward [MAX_HIDDEN_TABS], so a prefetch never takes a slot from the user (FB3 arch M1).
     */
    fun limitError(hidden: Boolean, visible: Int, hiddenOpen: Int): String? = when {
        hidden && hiddenOpen >= MAX_HIDDEN_TABS -> "Too many pages are being read at once ($MAX_HIDDEN_TABS)."
        !hidden && visible >= BrowserEngine.MAX_TABS -> "Too many tabs are open (${BrowserEngine.MAX_TABS}). Ask the user to close some."
        else -> null
    }

    /** Size for a parked WebView: the host's once laid out, else the window's, else the display's. */
    fun fallbackSize(hostW: Int, hostH: Int, windowW: Int?, windowH: Int?, displayW: Int, displayH: Int): Pair<Int, Int> =
        if (hostW > 0 && hostH > 0) hostW to hostH
        else if (windowW != null && windowH != null && windowW > 0 && windowH > 0) windowW to windowH
        else displayW to displayH

    /** Ids to close, oldest first, from [lastUse] (tab id -> last AI use) of the eligible tabs. */
    fun idleAiTabsToClose(lastUse: Map<Int, Long>, now: Long): List<Int> {
        val idle = lastUse.entries.filter { now - it.value >= AI_IDLE_MS }.sortedBy { it.value }.map { it.key }
        return idle.take((idle.size - MAX_IDLE_AI_TABS).coerceAtLeast(0))
    }

    /** [x, y, w, h] in view px of [rect] scaled by [scale] plus padding, clipped to the view; null if nothing is left. */
    fun cropBox(rect: CssRect, scale: Float, viewW: Int, viewH: Int): IntArray? {
        val l = ((rect.left - CROP_PAD) * scale).toInt().coerceIn(0, viewW)
        val t = ((rect.top - CROP_PAD) * scale).toInt().coerceIn(0, viewH)
        val r = ((rect.left + rect.width + CROP_PAD) * scale).toInt().coerceIn(0, viewW)
        val b = ((rect.top + rect.height + CROP_PAD) * scale).toInt().coerceIn(0, viewH)
        return if (r - l < 1 || b - t < 1) null else intArrayOf(l, t, r - l, b - t)
    }

    // ---------- page readiness (tools-redesign 2-1)

    /** Load phase cap: a mobile network's slow first response. */
    const val LOAD_CAP = 15_000L

    /** Quiet window: no DOM change or request for this long (Playwright/Puppeteer networkidle). */
    const val QUIET = 500L

    /** Settle phase cap after the document is in: pages that never stop changing (clocks, tickers). */
    const val SETTLE_CAP = 5_000L

    /** Below this much text a page may still be drawing itself (E7): the wait goes on until it changes or the cap. */
    const val SPARSE_TEXT = 200

    const val POLL_MIN = 100L
    const val LOAD_POLL = 250L

    /** One mark() evaluate; a page busier than this is read as it is. */
    const val MARK_EVAL = 2_000L

    /** What one look at the tab found; times are uptime ms. */
    data class ReadyInput(
        val now: Long,
        /** Start of the action this wait follows. */
        val since: Long,
        /** Start of the current load phase (since, or the time of a restart). */
        val docStart: Long,
        /** Start of the settle phase, null while loading. */
        val settleStart: Long?,
        val loading: Boolean,
        /** Null when the page did not answer (busy, document swapping). */
        val mark: PageMark?,
        /** mark.n when the settle phase started. */
        val settleN: Int?,
        val mainRequestAt: Long,
        val pageStartedAt: Long,
        val lastRequestAt: Long,
        /** Latest main-frame failure. */
        val errorAt: Long?,
        /** Latest refused download (hidden tab): the load ends there. */
        val downloadAt: Long?,
    )

    sealed interface ReadyStep {
        data class Done(val readiness: Readiness) : ReadyStep
        /** The document is in: the settle phase starts now. */
        data object Settle : ReadyStep
        /** A new document was requested during the settle phase (JS redirect, form): load phase again. */
        data object Restart : ReadyStep
        data class Wait(val ms: Long) : ReadyStep
    }

    fun readyStep(i: ReadyInput): ReadyStep {
        if (i.errorAt != null && i.errorAt >= i.since) return ReadyStep.Done(Readiness.FAILED)
        if (i.downloadAt != null && i.downloadAt >= i.since) return ReadyStep.Done(Readiness.READY)
        val settle = i.settleStart
        if (settle == null) {
            if (i.now - i.docStart >= LOAD_CAP || i.now - i.since >= LOAD_CAP + SETTLE_CAP) return ReadyStep.Done(Readiness.LOADING)
            // Requested but no response yet (slow first byte, E2): the old document still answers mark().
            val awaitingResponse = i.mainRequestAt >= i.docStart && i.pageStartedAt < i.mainRequestAt
            val docIn = !i.loading || (i.mark != null && i.mark.readyState != "loading")
            return if (!awaitingResponse && docIn) ReadyStep.Settle else ReadyStep.Wait(LOAD_POLL)
        }
        if (i.mainRequestAt > settle) return ReadyStep.Restart
        val quiet = minOf(i.mark?.quietMs ?: 0L, i.now - i.lastRequestAt, i.now - i.since)
        val textLen = i.mark?.textLen ?: 0
        val changed = i.mark != null && i.settleN != null && i.mark.n != i.settleN
        if (quiet >= QUIET && (textLen >= SPARSE_TEXT || changed)) return ReadyStep.Done(Readiness.READY)
        if (i.now - settle >= SETTLE_CAP) {
            // Short and unchanged while we waited: a short page, not an unfinished one.
            return ReadyStep.Done(if (quiet >= QUIET && !changed) Readiness.READY else Readiness.CHANGING)
        }
        return ReadyStep.Wait(maxOf(POLL_MIN, QUIET - quiet))
    }

    // ---------- navigation errors (tools-redesign 2-2)

    private val NET_RETRY = setOf(
        "ERR_INTERNET_DISCONNECTED", "ERR_NETWORK_CHANGED", "ERR_NAME_NOT_RESOLVED", "ERR_CONNECTION_TIMED_OUT",
        "ERR_CONNECTION_RESET", "ERR_CONNECTION_CLOSED", "ERR_CONNECTION_REFUSED", "ERR_CONNECTION_FAILED",
        "ERR_TIMED_OUT", "ERR_ADDRESS_UNREACHABLE",
    )

    private fun netCode(desc: String) = Regex("ERR_[A-Z_]+").find(desc)?.value.orEmpty()

    /** Delay before the one automatic retry of an app-opened load; null when it is not retried. */
    fun netRetry(e: NavError): Long? = when {
        e.http -> if (e.code in RETRY_HTTP) 2_000L else null
        netCode(e.desc) in NET_RETRY -> 1_500L
        else -> null
    }

    private val RETRY_HTTP = setOf(429, 502, 503, 504)

    /** A failed load for the model; [retried] = the automatic retry was used. */
    fun navErrorText(e: NavError, retried: Boolean): String {
        val url = e.url
        if (e.http) {
            return when (e.code) {
                404, 410 -> "$url answered HTTP ${e.code} (not found). Do not retry this URL."
                401, 403 -> "$url answered HTTP ${e.code} (the site may need sign-in or blocks automated visits)."
                else -> "$url answered HTTP ${e.code} (${e.desc.ifBlank { "error" }})" + if (retried) "; already retried once." else "."
            }
        }
        val code = netCode(e.desc)
        if (code.startsWith("ERR_CERT_") || code.startsWith("ERR_SSL_")) {
            return "Could not open $url: the site's security certificate is not valid. Do not retry."
        }
        return "Could not open $url: ${netCause(e.desc)} (${e.desc})." +
            if (retried) " Already retried once; try again later or use another source." else ""
    }

    fun netCause(desc: String): String {
        val c = netCode(desc)
        return when {
            c == "ERR_NAME_NOT_RESOLVED" -> "the site's address was not found"
            c == "ERR_INTERNET_DISCONNECTED" -> "no network connection"
            "TIMED_OUT" in c -> "the connection timed out"
            else -> "the connection failed"
        }
    }

    // ---------- read sizes (tools-redesign 3-2, 5-3)

    /** Characters per read part for a model of [contextWindow] tokens (null = unknown). */
    fun readChunk(contextWindow: Int?): Int = contextWindow?.let { (it / 4).coerceIn(4_000, 20_000) } ?: 12_000

    /** Characters of read results the trimmer sends whole, newest first. */
    fun readKeep(contextWindow: Int?): Int =
        contextWindow?.let { (it * 0.6).toInt().coerceIn(readChunk(it), 40_000) } ?: 40_000

    /** [text] in parts of at most [size], each cut after its last blank line when it has one. */
    fun parts(text: String, size: Int): List<String> {
        val out = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + size, text.length)
            if (end < text.length) {
                val blank = text.lastIndexOf("\n\n", end - 2)
                if (blank > start) end = blank + 2
            }
            out += text.substring(start, end).trim('\n')
            start = end
        }
        return out.ifEmpty { listOf("") }
    }
}

enum class Readiness { READY, LOADING, CHANGING, FAILED }

/** A main-frame load failure: a network error ([http] false, [desc] like net::ERR_NAME_NOT_RESOLVED) or HTTP >= 400. */
data class NavError(val at: Long, val url: String, val http: Boolean, val code: Int, val desc: String)
