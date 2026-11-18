package com.ouor.localchat.browser.script

import com.ouor.localchat.browser.BrowserTab
import com.ouor.localchat.browser.PageError
import com.ouor.localchat.browser.SearchEngines
import android.os.SystemClock
import com.ouor.localchat.browser.safety.SensitiveFields
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.security.SecureRandom

/**
 * Calls assets/browser/agent.js in a tab (architecture 7.2, 7.3). Every call is one evaluateJavascript that
 * installs the library under a per-process random, non-enumerable window key if the document lacks it, then
 * runs one function. Results come back only through the evaluateJavascript callback; the page has no channel
 * to the app. Failures throw [PageError] with a model-facing message.
 */
object PageScript {

    // SE 17: the page can find this key (getOwnPropertyNames) and pre-define a fake API for its own document, so
    // page-reported values (sensitive, formMethod, labels) are only as trustworthy as that page. The page cannot
    // reach the other layers: agent.js type() re-checks the live element, and approvals are asked app-side.
    private val key: String = "__lc" + ByteArray(8).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
    private val keyJs = JsonPrimitive(key).toString()

    private val config = buildJsonObject {
        putJsonArray("autocomplete") { SensitiveFields.AUTOCOMPLETE.forEach { add(JsonPrimitive(it)) } }
        put("prefix", SensitiveFields.AUTOCOMPLETE_PREFIX)
        put("pattern", SensitiveFields.NAME_PATTERN)
        put("bot", SearchEngines.BOT_PATTERN)
    }.toString()
    private val searchCfg = buildJsonObject { put("bot", SearchEngines.BOT_PATTERN) }.toString()

    @Volatile private var agentJs: String? = null
    @Volatile private var searchJs: String? = null

    private fun asset(tab: BrowserTab, name: String): String =
        tab.webView.context.applicationContext.assets.open("browser/$name").bufferedReader().use { it.readText() }

    private fun install(tab: BrowserTab): String {
        val lib = agentJs ?: asset(tab, "agent.js").also { agentJs = it }
        return "var A=window[$keyJs];if(!A){($lib\n)($keyJs,$config);A=window[$keyJs];}"
    }

    /**
     * Runs agent.js function [fn] and returns its raw result. A document being swapped answers nothing: with [retry]
     * the call waits for the page to be ready and runs once more (tools-redesign 2-5).
     */
    suspend fun callJson(
        tab: BrowserTab,
        fn: String,
        args: JsonObject = JsonObject(emptyMap()),
        timeoutMs: Long = DEFAULT_TIMEOUT,
        retry: Boolean = true,
    ): JsonElement {
        val js = "(function(){try{${install(tab)}return JSON.stringify({ok:A.call(${JsonPrimitive(fn)},$args)});}" +
            "catch(e){return JSON.stringify({error:String(e&&e.message||e)});}})()"
        val raw = tab.evaluate(js, timeoutMs).takeUnless { it == "null" } ?: run {
            if (!retry) throw PageError(NOT_RUN)
            tab.awaitReady(SystemClock.uptimeMillis())
            tab.evaluate(js, timeoutMs).takeUnless { it == "null" } ?: throw PageError(NOT_RUN_AGAIN)
        }
        return okOf(decode(raw))
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
        val js = "(function(){try{return JSON.stringify({ok:($lib\n)(${JsonPrimitive(engine.name)},$MAX_RESULTS,$searchCfg)});}" +
            "catch(e){return JSON.stringify({error:String(e&&e.message||e)});}})()"
        val raw = tab.evaluate(js, DEFAULT_TIMEOUT).takeUnless { it == "null" } ?: throw PageError(NOT_RUN)
        return AppJson.decodeFromJsonElement(okOf(decode(raw)))
    }

    /**
     * `execute` (architecture 7.4): [code] runs as an expression, or as a function body when it is not one; it runs
     * at most once (tools-redesign 3-6). Sensitive input value setters are refused while it runs; a returned Promise
     * is awaited by polling. Returns the JSON-ish result text (not yet capped or cookie-masked).
     */
    suspend fun execute(tab: BrowserTab, code: String, timeoutMs: Long): String {
        val expr = code.trim().trimEnd(';').trim()
        var raw = tab.evaluate(execWrapper("return ($expr\n);", tab), timeoutMs)
        if (raw == "null") {
            // "null" = the wrapper did not parse, or the document was swapped. A live document answers the probe, so the
            // expression wrapper failed to parse and the code never ran: run it as statements.
            if (tab.evaluate("1", DEFAULT_TIMEOUT) != "1") throw PageError(EXEC_UNKNOWN)
            raw = tab.evaluate(execWrapper("$code\n", tab), timeoutMs)
            if (raw == "null") throw PageError("SyntaxError: the code could not be parsed as an expression or as statements.")
        }
        var result = decode(raw)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (result["pending"] != null) {
            if (System.currentTimeMillis() > deadline) {
                runCatching { callJson(tab, "execEnd", buildJsonObject { put("force", true) }) }
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
        "(function(){${install(tab)}A.call('execBegin');var r;try{r=(function(){$body})();}" +
            "catch(e){A.call('execEnd');return JSON.stringify({error:String(e&&e.stack||e)});}" +
            "return JSON.stringify(A.call('execResult',{value:r}));})()"

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

    /** The model-facing text after the one retry; the browse hint is added by the tool layer when browse is exposed. */
    const val NOT_RUN_AGAIN = "The page was changing and did not run the script."
    const val EXEC_UNKNOWN = "The page changed while the code ran; it may or may not have run. Check with browse() before running it again."
    const val MAX_RESULTS = 8
    const val DEFAULT_TIMEOUT = 10_000L
    const val POLL_MS = 100L
}
