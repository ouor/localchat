package com.ouor.localchat.ui.settings

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ouor.localchat.R
import com.ouor.localchat.browser.BrowserEngine
import com.ouor.localchat.core.model.AppLanguage
import com.ouor.localchat.core.model.AppSettings
import com.ouor.localchat.core.model.ModelInfo
import com.ouor.localchat.core.model.SearchEngine
import com.ouor.localchat.core.model.ThemeMode
import com.ouor.localchat.provider.ProviderRepository
import com.ouor.localchat.speech.ReadAloud
import com.ouor.localchat.speech.TtsVoice
import com.ouor.localchat.storage.SettingsStore
import com.ouor.localchat.ui.chat.ModelPickerSheet
import com.ouor.localchat.ui.components.Banner
import com.ouor.localchat.ui.components.BannerVariant
import com.ouor.localchat.ui.components.ButtonVariant
import com.ouor.localchat.ui.components.DialogKind
import com.ouor.localchat.ui.components.IconButtonSize
import com.ouor.localchat.ui.components.LcButton
import com.ouor.localchat.ui.components.LcCheckbox
import com.ouor.localchat.ui.components.LcDialog
import com.ouor.localchat.ui.components.LcIconButton
import com.ouor.localchat.ui.components.LcSelectMenu
import com.ouor.localchat.ui.components.LcSwitch
import com.ouor.localchat.ui.components.LcTextField
import com.ouor.localchat.ui.components.LcTopBar
import com.ouor.localchat.ui.components.MenuOption
import com.ouor.localchat.ui.components.SectionLabel
import com.ouor.localchat.ui.components.SegmentedControl
import com.ouor.localchat.ui.components.SettingsChevron
import com.ouor.localchat.ui.components.SettingsGroup
import com.ouor.localchat.ui.components.SettingsRow
import com.ouor.localchat.ui.components.Stepper
import com.ouor.localchat.ui.components.TextFieldKind
import com.ouor.localchat.ui.theme.LcTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/** SC-15 / SC-16 (architecture 10.3): settings are a thin view over SettingsStore. */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: SettingsStore,
    providers: ProviderRepository,
    private val readAloud: ReadAloud,
    private val browser: BrowserEngine,
) : ViewModel() {
    val settings: StateFlow<AppSettings?> = store.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val models: StateFlow<List<ModelInfo>> = providers.allVisibleModels.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val endpointCount = providers.endpoints.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    /** Model picker groups: same rule as the chat sheet ([com.ouor.localchat.ui.chat.pickerEndpoints]). */
    val pickerEndpointIds: StateFlow<List<String>> = providers.endpoints.map { eps -> com.ouor.localchat.ui.chat.pickerEndpoints(eps).map { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val voices: StateFlow<List<TtsVoice>> = readAloud.voices

    fun update(transform: AppSettings.() -> AppSettings) { viewModelScope.launch { store.update(transform) } }
    fun voice(name: String?, rate: Float) { update { copy(ttsVoice = name, ttsRate = rate) }; readAloud.setVoice(name, rate) }
    fun preview(name: String?, rate: Float) = readAloud.preview(name, rate)
    fun clearBrowserData(cookies: Boolean, cache: Boolean, storage: Boolean) = browser.clearData(cookies, cache, storage)
    val builtInSensitiveCount: Int get() = browser.builtInSensitiveCount
}

@Composable
private fun Back(onBack: () -> Unit) = LcIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.ui_back), onBack)

@Composable
private fun Page(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        LcTopBar(title, navigationIcon = { Back(onBack) })
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) { content() }
    }
}

/** A row that opens an [LcSelectMenu] anchored to it. */
@Composable
private fun SelectRow(title: String, value: String, options: List<MenuOption>, selected: Int, divider: Boolean = false, subtitle: String? = null, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        SettingsRow(title, subtitle = subtitle, value = value, divider = divider, onClick = { open = true })
        // FB2-1: the value sits at the row's right edge, so the menu opens there too.
        Box(Modifier.align(Alignment.BottomEnd)) {
            LcSelectMenu(open, { open = false }, options, selected, { open = false; onSelect(it) })
        }
    }
}

/** SC-15. */
@Composable
fun SettingsHomeRoute(vm: SettingsViewModel, onBack: () -> Unit, onProviders: () -> Unit, onAgent: () -> Unit, onData: () -> Unit, onAbout: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val endpoints by vm.endpointCount.collectAsStateWithLifecycle()
    val pickerIds by vm.pickerEndpointIds.collectAsStateWithLifecycle()
    val voices by vm.voices.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var picker by remember { mutableStateOf(false) }
    val set = s ?: return
    Page(stringResource(R.string.ui_settings), onBack) {
        SectionLabel(stringResource(R.string.ui_models))
        SettingsGroup {
            SettingsRow(stringResource(R.string.ui_providers), subtitle = endpoints.joinToString { it.name }.ifEmpty { null }, onClick = onProviders) { SettingsChevron() }
            val def = models.firstOrNull { it.ref == set.defaultModel }
            SettingsRow(stringResource(R.string.ui_default_model), value = def?.displayName ?: stringResource(R.string.ui_choose_model), divider = true, onClick = { picker = true }) {
                SettingsChevron()
            }
        }
        SectionLabel(stringResource(R.string.ui_general))
        SettingsGroup {
            val langs = listOf(AppLanguage.SYSTEM to stringResource(R.string.ui_system), AppLanguage.KO to "한국어", AppLanguage.EN to "English")
            SelectRow(stringResource(R.string.ui_language), langs.first { it.first == set.language }.second, langs.map { MenuOption(it.second) }, langs.indexOfFirst { it.first == set.language }) { i ->
                val lang = langs[i].first
                vm.update { copy(language = lang) }
                // G-01: per-app language (Android 13+); older versions follow the system.
                if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java)?.applicationLocales =
                    when (lang) { AppLanguage.SYSTEM -> LocaleList.getEmptyLocaleList(); AppLanguage.KO -> LocaleList.forLanguageTags("ko"); AppLanguage.EN -> LocaleList.forLanguageTags("en") }
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.ui_theme), style = LcTheme.text.body, color = LcTheme.colors.textPrimary)
                val themes = listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SYSTEM)
                SegmentedControl(listOf(stringResource(R.string.ui_light), stringResource(R.string.ui_dark), stringResource(R.string.ui_system)), themes.indexOf(set.theme), { i -> vm.update { copy(theme = themes[i]) } })
            }
            val countries = remember { listOf<String?>(null) + Locale.getISOCountries().sortedBy { Locale("", it).displayCountry } }
            val none = stringResource(R.string.ui_none)
            SelectRow(
                stringResource(R.string.ui_region), set.region?.let { Locale("", it).displayCountry } ?: none,
                countries.map { MenuOption(it?.let { c -> Locale("", c).displayCountry } ?: none) }, countries.indexOf(set.region).coerceAtLeast(0),
                divider = true, subtitle = stringResource(R.string.ui_region_sub),
            ) { i -> vm.update { copy(region = countries[i]) } }
            val voiceNames = listOf<String?>(null) + voices.map { it.name }
            val engineDefault = stringResource(R.string.ui_default)
            SelectRow(
                stringResource(R.string.ui_tts_voice), set.ttsVoice ?: engineDefault,
                voiceNames.map { MenuOption(it ?: engineDefault, voices.firstOrNull { v -> v.name == it }?.localeTag) }, voiceNames.indexOf(set.ttsVoice).coerceAtLeast(0), divider = true,
            ) { i -> vm.voice(voiceNames[i], set.ttsRate) }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                var rate by remember(set.ttsRate) { mutableStateOf(set.ttsRate) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ui_tts_rate, rate), style = LcTheme.text.body, color = LcTheme.colors.textPrimary, modifier = Modifier.weight(1f))
                    LcButton(stringResource(R.string.ui_preview), { vm.preview(set.ttsVoice, rate) }, variant = ButtonVariant.Text)
                }
                Slider(rate, { rate = (it * 10).toInt() / 10f }, valueRange = 0.5f..2f, onValueChangeFinished = { vm.voice(set.ttsVoice, rate) })
            }
        }
        SectionLabel(stringResource(R.string.ui_other))
        SettingsGroup {
            SettingsRow(stringResource(R.string.ui_agent_browser), onClick = onAgent) { SettingsChevron() }
            SettingsRow(stringResource(R.string.ui_data), divider = true, onClick = onData) { SettingsChevron() }
            SettingsRow(stringResource(R.string.ui_about), value = appVersion(), divider = true, onClick = onAbout) { SettingsChevron() }
        }
    }
    if (picker) ModelPickerSheet(
        models, set.defaultModel, { id -> endpoints.firstOrNull { it.id == id }?.name.orEmpty() }, { picker = false },
        showRecent = false, endpointIds = pickerIds,
    ) {
        vm.update { copy(defaultModel = it) }; picker = false
    }
}

/** FB2-2: built-in list switch. Turning it off asks once; turning it on does not. */
@Composable
internal fun BuiltInSensitiveRow(enabled: Boolean, count: Int, onChange: (Boolean) -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    SettingsRow(
        stringResource(R.string.ui_sensitive_builtin), leadingIcon = Icons.Outlined.Lock,
        subtitle = if (enabled) pluralStringResource(R.plurals.fb2_s_builtin_on, count, count) else stringResource(R.string.fb2_s_builtin_off),
    ) { LcSwitch(enabled, { on -> if (on) onChange(true) else confirming = true }) }
    if (confirming) LcDialog(
        stringResource(R.string.fb2_s_off_title), { confirming = false }, stringResource(R.string.fb2_s_off_confirm),
        { onChange(false); confirming = false }, text = stringResource(R.string.fb2_s_off_body), kind = DialogKind.Destructive,
    )
}

/** SC-16. */
@Composable
fun AgentSettingsRoute(vm: SettingsViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val set = s ?: return
    var adding by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    Page(stringResource(R.string.ui_agent_browser), onBack) {
        SectionLabel(stringResource(R.string.ui_agent))
        SettingsGroup {
            SettingsRow(stringResource(R.string.ui_max_steps), subtitle = stringResource(R.string.ui_max_steps_sub)) {
                Stepper(set.maxSteps.toString(), if (set.maxSteps > 5) ({ vm.update { copy(maxSteps = (maxSteps - 5).coerceAtLeast(5)) } }) else null,
                    if (set.maxSteps < 100) ({ vm.update { copy(maxSteps = (maxSteps + 5).coerceAtMost(100)) } }) else null)
            }
        }
        var instructions by remember { mutableStateOf(set.userInstructions) }
        LcTextField(
            instructions, { instructions = it.take(MAX_INSTRUCTIONS); vm.update { copy(userInstructions = instructions) } },
            Modifier.padding(16.dp), label = stringResource(R.string.ui_instructions), kind = TextFieldKind.Multiline,
            placeholder = stringResource(R.string.ui_instructions_hint), helper = "${instructions.length} / $MAX_INSTRUCTIONS",
        )
        SectionLabel(stringResource(R.string.ui_browser))
        SettingsGroup {
            val engines = SearchEngine.entries
            val names = listOf("DuckDuckGo", "Google", "Bing", "Naver")
            SelectRow(stringResource(R.string.ui_search_engine), names[engines.indexOf(set.searchEngine)], names.map { MenuOption(it) }, engines.indexOf(set.searchEngine)) { i ->
                vm.update { copy(searchEngine = engines[i]) }
            }
            SettingsRow(stringResource(R.string.ui_clear_browser_data), divider = true, danger = true, onClick = { clearing = true })
        }
        SectionLabel(stringResource(R.string.ui_sensitive_sites))
        SettingsGroup {
            BuiltInSensitiveRow(set.builtInSensitiveEnabled, vm.builtInSensitiveCount) { on -> vm.update { copy(builtInSensitiveEnabled = on) } }
            set.userSensitiveDomains.forEach { d ->
                SettingsRow(d, divider = true) {
                    LcIconButton(Icons.Rounded.Close, stringResource(R.string.ui_delete), { vm.update { copy(userSensitiveDomains = userSensitiveDomains - d) } }, size = IconButtonSize.Sm)
                }
            }
            SettingsRow(stringResource(R.string.ui_add_domain), divider = true, leadingIcon = Icons.Rounded.Add, onClick = { adding = true })
        }
        Spacer(Modifier.height(16.dp))
        Banner(stringResource(R.string.ui_safety_summary), BannerVariant.Agent, Modifier.padding(horizontal = 16.dp))
    }
    if (adding) {
        var d by remember { mutableStateOf("") }
        val domain = d.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').lowercase()
        LcDialog(stringResource(R.string.ui_add_domain), { adding = false }, stringResource(R.string.ui_save), {
            vm.update { copy(userSensitiveDomains = (userSensitiveDomains + domain).distinct()) }; adding = false
        }, confirmEnabled = '.' in domain && ' ' !in domain) { LcTextField(d, { d = it }, placeholder = "bank.example.com", kind = TextFieldKind.Url) }
    }
    if (clearing) {
        var cookies by remember { mutableStateOf(true) }
        var cache by remember { mutableStateOf(true) }
        var storage by remember { mutableStateOf(true) }
        LcDialog(stringResource(R.string.ui_clear_browser_data), { clearing = false }, stringResource(R.string.ui_delete), {
            vm.clearBrowserData(cookies, cache, storage); clearing = false
        }, kind = DialogKind.Destructive, confirmEnabled = cookies || cache || storage) {
            listOf(
                Triple(R.string.ui_cookies, cookies) { v: Boolean -> cookies = v },
                Triple(R.string.ui_cache, cache) { v: Boolean -> cache = v },
                Triple(R.string.ui_site_storage, storage) { v: Boolean -> storage = v },
            ).forEach { (label, value, set) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LcCheckbox(value, set)
                    Text(stringResource(label), style = LcTheme.text.body, color = LcTheme.colors.textPrimary)
                }
            }
        }
    }
}

private const val MAX_INSTRUCTIONS = 2000

@Composable
private fun appVersion(): String {
    val context = LocalContext.current
    return remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
}

/** About (G-06): version, privacy note, open-source licenses. */
@Composable
fun AboutRoute(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        LcTopBar(stringResource(R.string.ui_about), navigationIcon = { Back(onBack) })
        Banner(stringResource(R.string.ui_privacy_note), BannerVariant.Info, Modifier.padding(horizontal = 16.dp))
        Text("localchat ${appVersion()}", Modifier.padding(16.dp), style = LcTheme.text.caption, color = LcTheme.colors.textTertiary)
        val libraries by com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries()
        com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer(libraries, Modifier.fillMaxWidth().weight(1f))
    }
}
