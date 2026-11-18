package com.ouor.localchat.policy

import com.ouor.localchat.R
import com.ouor.localchat.browser.BrowserFacts
import com.ouor.localchat.core.model.AppSettings
import com.ouor.localchat.storage.SettingsStore
import com.ouor.localchat.tools.TargetRule
import com.ouor.localchat.tools.ToolOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * B-S3 as a policy (browser-layers 4-2): the AI does not read or operate sites on the built-in list (switchable as a
 * whole) or the user's list. A refusal, never a confirmation; the user still browses them, and ask_user lets them do
 * that part. Tab titles and URLs in tabs() stay visible: they are metadata.
 */
class SiteBlockPolicy(
    builtInDomains: List<String>,
    private val store: SettingsStore,
    private val scope: CoroutineScope,
    browser: BrowserFacts,
    private val texts: Texts,
) : ToolPolicy {

    private val builtIn = builtInDomains

    private val settings: StateFlow<AppSettings?> = store.settings.stateIn(scope, SharingStarted.Eagerly, null)

    // Before settings load: the full built-in list (fail safe).
    private val rule: StateFlow<SensitiveUrls> = settings.map { s -> rule(builtIn, s) }
        .stateIn(scope, SharingStarted.Eagerly, SensitiveUrls(builtIn, emptyList()))

    private fun blocked(url: String?): Boolean = url != null && rule.value.isSensitive(url)

    override suspend fun before(call: PolicyCall): Verdict {
        if (!call.toolFacts.browser || call.tool == ASK_USER) return Verdict.Allow
        val tab = call.tab
        if (tab != null && call.toolFacts.target != TargetRule.NONE && blocked(tab.url)) {
            return Verdict.Refuse(onSite(tab.id, tab.url))
        }
        val dest = (call.args["url"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (dest != null && (call.toolFacts.target == TargetRule.OPEN || call.toolFacts.target == TargetRule.READ)) {
            val asUrl = if ("://" in dest || ' ' in dest || '.' !in dest) dest else "https://$dest"
            if (blocked(asUrl)) return Verdict.Refuse("${SensitiveUrls.hostOf(asUrl)} $REFUSAL. The AI may not open or read it; ask_user can let the user do this part.")
        }
        return Verdict.Allow
    }

    /** SE 2: a call that ended on a blocked site (a redirect, a link) gives none of its content. */
    override fun after(call: PolicyCall, facts: CallFacts, result: ToolOutcome): ToolOutcome {
        val tab = facts.tabAfter ?: return result
        if (!call.toolFacts.browser || call.toolFacts.target == TargetRule.NONE || !blocked(tab.url)) return result
        return ToolOutcome(
            lead = "Tab ${tab.id} is now on ${SensitiveUrls.hostOf(tab.url)}, which $REFUSAL; its content was withheld. ask_user can let the user do this part.",
            page = "",
            isError = true,
            refused = true,
        )
    }

    override fun promptLines() = listOf(
        "- If a tool says a site is a sensitive site, do not try to reach it another way; ask_user lets the user do that part.",
    )

    override val notices: StateFlow<List<Notice>> = combine(browser.shown, browser.tabs, rule) { shown, tabs, r ->
        val tab = tabs.firstOrNull { it.id == shown }
        if (tab != null && r.isSensitive(tab.url)) listOf(Notice(KEY, texts.get(R.string.ui_sensitive_site), tab.id, Notice.Style.BANNER))
        else emptyList()
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    // Built once: each stateIn keeps a collector in [scope]. Titles are read again at every settings() (app language).
    private val builtInOn = settings.map { it?.builtInSensitiveEnabled != false }.stateIn(scope, SharingStarted.Eagerly, true)
    private val builtInText = builtInOn.map(::builtInDescription).stateIn(scope, SharingStarted.Eagerly, builtInDescription(true))
    private val userDomains = settings.map { it?.userSensitiveDomains.orEmpty() }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private fun builtInDescription(on: Boolean) =
        if (on) texts.plural(R.plurals.fb2_s_builtin_on, builtIn.size, builtIn.size) else texts.get(R.string.fb2_s_builtin_off)

    override fun settings(): List<SettingItem> = listOf(
        SettingItem.Switch(
            key = "$KEY.builtIn",
            title = texts.get(R.string.ui_sensitive_builtin),
            description = builtInText,
            value = builtInOn,
            set = { on -> scope.launch { store.update { copy(builtInSensitiveEnabled = on) } } },
            offConfirm = SettingItem.Confirm(texts.get(R.string.fb2_s_off_title), texts.get(R.string.fb2_s_off_body), texts.get(R.string.fb2_s_off_confirm)),
        ),
        SettingItem.TextList(
            key = "$KEY.user",
            title = texts.get(R.string.ui_sensitive_sites),
            description = NO_TEXT,
            values = userDomains,
            set = { list -> scope.launch { store.update { copy(userSensitiveDomains = list.distinct()) } } },
            addLabel = texts.get(R.string.ui_add_domain),
            placeholder = "bank.example.com",
            normalize = ::domain,
        ),
    )

    private fun onSite(tabId: Int, url: String) =
        "Tab $tabId is on ${SensitiveUrls.hostOf(url)}, which $REFUSAL. The AI may not read or operate it; ask_user can let the user do this part."

    companion object {
        const val KEY = "siteBlock"
        const val ASK_USER = "ask_user"
        const val REFUSAL = SensitiveUrls.REFUSAL
        private val NO_TEXT: StateFlow<String> = kotlinx.coroutines.flow.MutableStateFlow("")

        /** FB2-2: the built-in list only while its switch is on; the user's domains always. */
        fun rule(builtIn: List<String>, s: AppSettings?) =
            SensitiveUrls(if (s?.builtInSensitiveEnabled != false) builtIn else emptyList(), s?.userSensitiveDomains.orEmpty())

        /** What the user typed as a domain: `https://bank.com/x` -> `bank.com`; null when it is no domain. */
        fun domain(typed: String): String? =
            typed.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').lowercase()
                .takeIf { '.' in it && ' ' !in it }
    }
}
