package com.ouor.localchat.browser.script

import com.ouor.localchat.browser.BrowserTab
import com.ouor.localchat.browser.PageError
import com.ouor.localchat.browser.SearchEngines
import com.ouor.localchat.browser.StaleRef
import android.os.SystemClock
import com.ouor.localchat.core.AppJson
import com.ouor.localchat.core.model.SearchEngine
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.SecureRandom

/**
 * Calls assets/browser/agent.js in a tab (architecture 7.2, 7.3). Every call is one evaluateJavascript that
 * installs the library under a per-process random, non-enumerable window key if the document lacks it, then
 * runs one function. Results come back only through the evaluateJavascript callback; the page has no channel
 * to the app. Failures throw [PageError] with a model-facing message.
 */
object PageScript {

    // SE 17: the page can find this key (getOwnPropertyNames) and pre-define a fake API for its own document, so
    // page-reported values (labels, formMethod, [secret]) are only as trustworthy as that page.
    private val key: String = "__lc" + ByteArray(8).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
    private val keyJs = JsonPrimitive(key).toString()

    private val config = buildJsonObject { put("bot", SearchEngines.BOT_PATTERN) }.toString()

    @Volatile private var agentJs: String? = null
    @Volatile private var searchJs: String? = null

    private fun asset(tab: BrowserTab, name: String): String =
        tab.view.context.applicationContext.assets.open("browser/$name").bufferedReader().use { it.readText() }

    /** Installs agent.js in a new document with refs "{tab}e{n}" numbered from [BrowserTab.refNext] (G-M1, S4). */
    private fun install(tab: BrowserTab): String {
        val lib = agentJs ?: asset(tab, "agent.js").also { agentJs = it }
        return "var A=window[$keyJs];if(!A){($lib\n)($keyJs,Object.assign($config,{refBase:${tab.refNext},tab:${tab.id}}));A=window[$keyJs];}"
    }

    /**
     * Runs agent.js function [fn] and returns its raw result. A document being swapped answers nothing: with [retry]
     * the call waits for the page to be ready and runs once more (tools-redesign 2-5). Functions that change the page
     * (click, type) pass retry = false, so they never run twice (B-m1). A refused ref throws [StaleRef].
     */
    suspend fun callJson(
        tab: BrowserTab,
        fn: String,
        args: JsonObject = JsonObject(emptyMap()),
        timeoutMs: Long = DEFAULT_TIMEOUT,
        retry: Boolean = true,
    ): JsonElement {
        val js = "(function(){var A;try{${install(tab)}A.floor(${tab.refNext});return JSON.stringify({ok:A.call(${JsonPrimitive(fn)},$args),next:A.next()});}" +
            "catch(e){return JSON.stringify({error:String(e&&e.message||e),next:A&&A.next()});}})()"
        val raw = tab.evaluate(js, timeoutMs).takeUnless { it == "null" } ?: run {
            if (!retry) throw PageError(NOT_RUN)
            tab.awaitReady(SystemClock.uptimeMillis())
            tab.evaluate(js, timeoutMs).takeUnless { it == "null" } ?: throw PageError(NOT_RUN_AGAIN)
        }
        val o = decode(raw)
        (o["next"] as? JsonPrimitive)?.intOrNull?.let { if (it > tab.refNext) tab.refNext = it }
        (o["error"] as? JsonPrimitive)?.content?.takeIf { it.startsWith(REF_PREFIX) }?.let { throw StaleRef(it.removePrefix(REF_PREFIX)) }
        return okOf(o)
    }

    /** Runs agent.js function [fn] and decodes its result (see ScriptResults). */
    suspend inline fun <reified T> call(
        tab: BrowserTab,
        fn: String,
        args: JsonObject = JsonObject(emptyMap()),
        timeoutMs: Long = DEFAULT_TIMEOUT,
        retry: Boolean = true,
    ): T = AppJson.decodeFromJsonElement(callJson(tab, fn, args, timeoutMs, retry))

    /** search.js extractor for [engine] on the loaded result page: up to [MAX_RESULTS] results, or blocked. */
    suspend fun search(tab: BrowserTab, engine: SearchEngine): SearchPage {
        val lib = searchJs ?: asset(tab, "search.js").also { searchJs = it }
        val js = "(function(){try{return JSON.stringify({ok:($lib\n)(${JsonPrimitive(engine.name)},$MAX_RESULTS,$config)});}" +
            "catch(e){return JSON.stringify({error:String(e&&e.message||e)});}})()"
        val raw = tab.evaluate(js, DEFAULT_TIMEOUT).takeUnless { it == "null" } ?: throw PageError(NOT_RUN)
        return AppJson.decodeFromJsonElement(okOf(decode(raw)))
    }

    /**
     * `execute` (architecture 7.4): [code] runs as an expression, or as a function body when it is not one; it runs
     * at most once (tools-redesign 3-6). Which form parses is checked first without running anything ([JsSyntax]), so an empty
     * answer afterwards can only mean the document changed while the code ran (B-m2). A returned Promise is awaited
     * by polling. Returns the JSON-ish result text (not yet capped).
     */
    suspend fun execute(tab: BrowserTab, code: String, timeoutMs: Long): String {
        val expr = code.trim().trimEnd(';').trim()
        val ctx = tab.view.context
        val body = listOf("return ($expr\n);", "$code\n").firstOrNull { JsSyntax.parses(ctx, it) ?: throw PageError(NOT_CHECKED) }
            ?: throw PageError("SyntaxError: the code could not be parsed as an expression or as statements; it was not run.")
        val raw = tab.evaluate(execWrapper(body, tab), timeoutMs)
        if (raw == "null") throw PageError(EXEC_UNKNOWN)
        var result = decode(raw)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (result["pending"] != null) {
            if (System.currentTimeMillis() > deadline) {
                throw PageError("The returned Promise did not settle within ${timeoutMs / 1000}s.")
            }
            delay(POLL_MS)
            result = decode(tab.evaluate(
                "(function(){${install(tab)}return JSON.stringify(A.call('execPoll',{id:${result["pending"]!!.jsonPrimitive.int}}));})()",
                DEFAULT_TIMEOUT,
            ))
        }
        return okOf(result).jsonPrimitive.content
    }

    private fun execWrapper(body: String, tab: BrowserTab) =
        "(function(){${install(tab)}var r;try{r=(function(){$body})();}" +
            "catch(e){return JSON.stringify({error:String(e&&e.stack||e)});}" +
            "return JSON.stringify(A.call('execResult',{value:r}));})()"

    /** The bytes of a `blob:` URL the page made, as a data: URL (browser-layers 2-5); only the page can read it. */
    suspend fun blob(tab: BrowserTab, url: String): String {
        val u = JsonPrimitive(url).toString()
        val code = "return fetch($u).then(function(r){return r.blob()}).then(function(b){return new Promise(function(ok,no){" +
            "var f=new FileReader();f.onload=function(){ok(f.result)};f.onerror=function(){no(f.error)};f.readAsDataURL(b)})})"
        val raw = execute(tab, code, BLOB_TIMEOUT)
        return AppJson.parseToJsonElement(raw).jsonPrimitive.content
    }

    private fun decode(raw: String?): JsonObject {
        if (raw == null || raw == "null") throw PageError(NOT_RUN)
        val inner = AppJson.parseToJsonElement(raw).jsonPrimitive.content
        return AppJson.parseToJsonElement(inner).jsonObject
    }

    private fun okOf(o: JsonObject): JsonElement {
        o["error"]?.let { throw PageError(it.jsonPrimitive.content) }
        return o["ok"] ?: JsonNull
    }

    const val NOT_RUN = "The page did not run the script (it may still be loading)."
    const val NOT_CHECKED = "The code could not be checked before running, so it was not run. Try again."

    /** The model-facing text after the one retry. */
    const val NOT_RUN_AGAIN = "The page was changing and did not run the script."

    /** agent.js marks a refused ref this way. */
    private const val REF_PREFIX = "REF:"
    const val EXEC_UNKNOWN = "The page changed while the code ran; it may or may not have run. Check with look() before running it again."
    const val MAX_RESULTS = 8
    const val DEFAULT_TIMEOUT = 10_000L
    const val POLL_MS = 100L
    private const val BLOB_TIMEOUT = 15_000L
}
