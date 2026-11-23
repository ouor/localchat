package com.ouor.localchat.policy

import android.content.Context
import com.ouor.localchat.agent.R
import com.ouor.localchat.browser.BrowserFacts
import com.ouor.localchat.tools.TargetRule
import com.ouor.localchat.tools.ToolOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
    private val settings: SiteBlockSettings,
    private val scope: CoroutineScope,
    browser: BrowserFacts,
    private val context: Context,
) : ToolPolicy {

    private val builtIn = builtInDomains

    private val rule: StateFlow<SensitiveUrls> = settings.state.map { s -> rule(builtIn, s) }
        .stateIn(scope, SharingStarted.Eagerly, rule(builtIn, settings.state.value))

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
        if (tab != null && r.isSensitive(tab.url)) listOf(Notice(KEY, context.getString(R.string.agent_sensitive_site), tab.id, Notice.Style.BANNER))
        else emptyList()
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    // Built once: each stateIn keeps a collector in [scope]. Titles are read again at every settings() (app language).
    private val builtInOn = settings.state.map { it.builtInOn }.stateIn(scope, SharingStarted.Eagerly, settings.state.value.builtInOn)
    private val builtInText = builtInOn.map(::builtInDescription).stateIn(scope, SharingStarted.Eagerly, builtInDescription(true))
    private val userDomains = settings.state.map { it.userDomains }.stateIn(scope, SharingStarted.Eagerly, settings.state.value.userDomains)

    private fun builtInDescription(on: Boolean) =
        if (on) context.resources.getQuantityString(R.plurals.agent_builtin_on, builtIn.size, builtIn.size) else context.getString(R.string.agent_builtin_off)

    override fun settings(): List<SettingItem> = listOf(
        SettingItem.Switch(
            key = "$KEY.builtIn",
            title = context.getString(R.string.agent_sensitive_builtin),
            description = builtInText,
            value = builtInOn,
            set = { on -> scope.launch { settings.setBuiltInOn(on) } },
            offConfirm = SettingItem.Confirm(context.getString(R.string.agent_builtin_off_title), context.getString(R.string.agent_builtin_off_body), context.getString(R.string.agent_builtin_off_confirm)),
        ),
        SettingItem.TextList(
            key = "$KEY.user",
            title = context.getString(R.string.agent_sensitive_sites),
            description = NO_TEXT,
            values = userDomains,
            set = { list -> scope.launch { settings.setUserDomains(list.distinct()) } },
            addLabel = context.getString(R.string.agent_add_domain),
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
        fun rule(builtIn: List<String>, s: SiteBlockState) = SensitiveUrls(if (s.builtInOn) builtIn else emptyList(), s.userDomains)

        /** What the user typed as a domain: `https://bank.com/x` -> `bank.com`; null when it is no domain. */
        fun domain(typed: String): String? =
            typed.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').lowercase()
                .takeIf { '.' in it && ' ' !in it }
    }
}

/** The policy's two settings ([SiteBlockState]); the app stores them. */
interface SiteBlockSettings {
    /** Until the stored values load: the built-in list on, no user domains (fail safe). */
    val state: StateFlow<SiteBlockState>
    suspend fun setBuiltInOn(on: Boolean)
    suspend fun setUserDomains(domains: List<String>)
}

/** [SiteBlockSettings] kept in memory: back to [initial] at every process start. Store them for settings that last. */
class InMemorySiteBlockSettings(initial: SiteBlockState = SiteBlockState()) : SiteBlockSettings {
    override val state = MutableStateFlow(initial)
    override suspend fun setBuiltInOn(on: Boolean) = state.update { it.copy(builtInOn = on) }
    override suspend fun setUserDomains(domains: List<String>) = state.update { it.copy(userDomains = domains) }
}

/** [builtInOn]: the built-in list (sensitive_domains.txt) counts; [userDomains]: the user's own list. */
data class SiteBlockState(val builtInOn: Boolean = true, val userDomains: List<String> = emptyList())
