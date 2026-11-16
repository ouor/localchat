package com.ouor.localchat.tools.browser

import android.graphics.Bitmap
import com.ouor.localchat.browser.BrowserTab
import com.ouor.localchat.browser.safety.ElementInfo
import com.ouor.localchat.browser.safety.IrreversibleAction
import com.ouor.localchat.browser.safety.SensitiveFields
import com.ouor.localchat.browser.safety.SensitiveUrls
import com.ouor.localchat.browser.script.Located
import com.ouor.localchat.browser.script.PageScript
import com.ouor.localchat.tools.ApprovalRequest
import com.ouor.localchat.tools.ParamsBuilder
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolError
import com.ouor.localchat.tools.optInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream

/** Shared by the browser tools: common parameters, Main-thread hop, URL checks, post-action settling. */

internal const val TAB_ID = "Tab id from open_tab or get_tabs. Omit to use your current tab."
internal const val TARGET = "Element ref from the latest snapshot (e.g. \"e12\") or a CSS selector."

internal const val LOAD_TIMEOUT = 15_000L
private const val SETTLE_MS = 400L
private const val HIGHLIGHT_LEAD_MS = 250L

internal fun ParamsBuilder.tabIdParam() = int("tabId", TAB_ID)
internal fun ParamsBuilder.targetParam() = string("target", TARGET, required = true)

/** WebView work runs on Main; suspending there is fine (every wait is a callback or a delay). */
internal suspend fun <T> onMain(block: suspend CoroutineScope.() -> T): T = withContext(Dispatchers.Main.immediate, block)

internal suspend fun ToolContext.tabFor(args: JsonObject): BrowserTab = aiTab(args.optInt("tabId"))

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

/** B-S1 for every input tool (SE 15): never into password/OTP/card fields or opaque frames. */
internal fun guardInput(target: ElementInfo?, scriptSensitive: Boolean) {
    if (target != null && SensitiveFields.refusesInput(target, scriptSensitive)) {
        throw ToolError(
            "Refused: ${target.label()} is a password, one-time-code or card field, or a frame whose content cannot be " +
                "checked. Only the user may fill it; use request_handoff.",
        )
    }
}

/**
 * B-S2 for every activating tool (SE 15): asks SC-13 when [reason] is set; false when the user denied. With [at]
 * the request carries a cropped image of the element (SC-13 design), never of a password/OTP/card field.
 * Main thread.
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
    val jpeg = ByteArrayOutputStream().use { out ->
        crop.compress(Bitmap.CompressFormat.JPEG, PREVIEW_QUALITY, out)
        crop.recycle()
        out.toByteArray()
    }
    // Stored like any artifact; nothing references it after the answer, so attachment GC removes it.
    return saveArtifact("approval-preview.jpg", "image/jpeg", jpeg)
}

private const val PREVIEW_QUALITY = 85

internal fun pageLine(tab: BrowserTab) = "Page: ${tab.title.ifBlank { "(untitled)" }} — ${tab.url}"

internal fun dialogSuffix(tab: BrowserTab) = tab.dialog.value?.let { "\n" + BrowserTab.dialogNote(it) }.orEmpty()

/** One-line element description for results and approvals: `button "Log in"`. */
internal fun ElementInfo.label(): String = (role ?: tag) + (name?.let { " \"$it\"" } ?: "")

internal suspend fun locate(tab: BrowserTab, target: String, scroll: Boolean = true): Located =
    PageScript.call(tab, "locate", buildJsonObject { put("target", target); put("scroll", scroll) })

internal fun targetArgs(target: String, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) =
    buildJsonObject { put("target", target); extra() }

/** B-09 ①: show the outline, then give the user a moment to see it before the page changes. */
internal suspend fun ToolContext.highlight(tab: BrowserTab, at: Located, labelRes: Int) {
    browser.showHighlight(tab.id, at, labelRes)
    delay(HIGHLIGHT_LEAD_MS)
}

/** After an action: wait for a navigation it started (or a dialog it opened), then describe the page. */
internal suspend fun settle(tab: BrowserTab): String {
    delay(SETTLE_MS)
    withTimeoutOrNull(LOAD_TIMEOUT) { combine(tab.loading, tab.dialog) { l, d -> !l || d != null }.first { it } }
    return pageLine(tab) + (if (tab.loading.value) " (still loading)" else "") + dialogSuffix(tab)
}

/** Runs a JS action whose script may be cut short by a dialog it opened; a dialog is not a failure there. */
internal suspend fun tolerateDialog(tab: BrowserTab, block: suspend () -> Unit) {
    try {
        block()
    } catch (e: com.ouor.localchat.browser.PageError) {
        if (tab.dialog.value == null) throw e
    }
}
