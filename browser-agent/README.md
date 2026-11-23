# browser-agent

A shared in-app browser that both the user and an AI model operate, as three Android library modules:

| Module | What it is |
|---|---|
| `:browser` | A plain browser: `BrowserEngine` (tabs, the user's front tab, sleep and wake, events, downloads), `BrowserTab`, page scripts, `BrowserTabHost`. |
| `:browser-tools` | The 12 model-callable tools (`ToolCatalog.ALL`: web_search, open, navigate, read, look, click, type, scroll, execute, tabs, close_tab, ask_user), their result format and the turn's tab note (`TabNote`). |
| `:browser-agent` | Runs them: `BrowserToolRunner` (target tab, lock and wake, policies, new tab, run, `after`), `BrowserPort`, the policy contracts and six default policies. |

No Hilt, no app settings, no app strings: everything is a plain constructor.

## Build requirements

- compileSdk 37, minSdk 31, Java/Kotlin JVM target 21. Built with AGP 9.2 (built-in Kotlin 2.4) and the
  `org.jetbrains.kotlin.plugin.serialization` plugin.
- Dependencies the modules bring: kotlinx-coroutines-android 1.11 and kotlinx-serialization-json 1.13 (as `api`),
  androidx.core-ktx, androidx.webkit 1.18, OkHttp 5.5.
- `:browser`'s manifest adds `INTERNET`. Resources of `:browser-agent` all start with `agent_`.

Not published to Maven. Include the three folders in your build (`include(":browser", ":browser-tools", ":browser-agent")`), then:

```kotlin
dependencies {
    implementation(project(":browser-agent")) // brings :browser-tools and :browser
}
```

## Set it up

```kotlin
// One browser for the whole process, in an Application or a DI singleton.
val engine = BrowserEngine(appContext, appScope)
engine.searchEngine = SearchEngine.DUCKDUCKGO

// The policies: pick from the defaults; leave a line out to drop that policy.
val policies = Policies(
    tools = listOf(
        SiteBlockPolicy(SensitiveUrls.builtIn(appContext), InMemorySiteBlockSettings(), appScope, engine, appContext),
        MoneyConfirmPolicy(appContext),
        SecretValuePolicy(),
        CookieMaskPolicy(),
        RepeatFailurePolicy(),
    ),
    browser = listOf(TabMemoryPolicy(TabMemoryPolicy.startK(totalMemBytes), savedK) { k -> storeK(k) }),
)
policies.attach(engine, appScope) // appScope must run on the main thread (Dispatchers.Main)

// Application.onTrimMemory(level): lets TabMemoryPolicy put background tabs to sleep.
engine.reportMemory(level)
```

`TabMemoryPolicy` keeps at most K tabs awake. `startK` picks K from the device's RAM. When the system kills a
renderer for memory, K goes down by one and `save(k)` is called: store it, and pass it back as `saved` at the next start
(null the first time) so K stays lowered.

`InMemorySiteBlockSettings` forgets the user's choices at every start; implement `SiteBlockSettings` over your own
storage (DataStore, SharedPreferences) to keep them. `policies.settings()` gives the rows a settings screen shows.

## Page prompts

A JavaScript dialog in the tab the user is looking at, and every permission request (camera, microphone) and file
chooser, is the user's to answer: it waits in `engine.prompts` until you call `engine.answer`. Without this it waits
forever:

```kotlin
appScope.launch {
    engine.prompts.collect { waiting ->
        waiting.firstOrNull()?.let { e -> showPrompt(e) { answer -> engine.answer(e.id, answer) } }
    }
}
// answer: Answer.Accept(text) / Answer.Dismiss / Answer.Files(uris) for a FileChooser
```

A dialog in a tab the user does not see is dismissed by the engine itself, the way Chrome does.

## Screen layout

`BrowserTabHost` is the engine's container: every tab's WebView lives in it at full size, even when hidden, so pages
keep running and taps land where they should. Put it under your chat UI in one `FrameLayout`, and show a tab by
binding it while you hide the chat view:

```kotlin
val tabHost = BrowserTabHost(activity)
val root = FrameLayout(activity).apply {
    addView(tabHost, MATCH_PARENT, MATCH_PARENT) // under
    addView(chatView, MATCH_PARENT, MATCH_PARENT) // over, opaque
}
activity.setContentView(root)
tabHost.bind(engine, null) // hosts the tabs, shows none

fun showTab(id: Int) { chatView.visibility = View.GONE; tabHost.bind(engine, id) }
fun showChat() { tabHost.bind(engine, null); chatView.visibility = View.VISIBLE }
```

## The turn loop

```kotlin
val chatTabs = ChatTabs() // one per chat, kept across turns: the chat's current tab, the tabs it opened

suspend fun turn(userMessage: String) {
    chatTabs.turn++ // a new user message starts a turn (not a resumed one): read positions belong to a turn
    policies.turnStart(chatId)
    val tools = ToolCatalog.ALL.filter { it.name !in policies.hiddenTools() } // name, description, parameters
    val system = mySystemPrompt + policies.promptLines().joinToString("\n")
    val note = TabNote.build(engine.tabs.value, engine.front.value, engine.shown.value, chatTabs) // after the user's message
    // One runner per turn (citation numbers are per message):
    val runner = BrowserToolRunner(chatId, { engine }, EngineBrowserPort(engine), policies, host, tools, chatTabs,
        contextWindow = 128_000, vision = false)
    // For each tool call of the model, one at a time:
    var outcome = runner.run(call.id, call.name, call.argumentsJson)
    outcome.userStep?.let { step ->
        showTab(step.tabId); waitForUserDone(step.task); showChat()
        outcome = runner.afterUserStep(call.id, step) // the page as the user left it
    }
    sendToModel(call.id, ToolResultFormat.render(outcome)) // lead, page text inside the boundary tag, note
    // ...
    policies.turnEnd(chatId)
}
```

`policies.promptLines()` starts with the page-boundary line (`Untrusted.PROMPT_LINE`): keep it in the prompt of every
model that may see page text. For `ask_user`, the model gets the result of `afterUserStep`, not the first outcome
("Asked the user: ..."), under the same call id.

## RunnerHost

What differs per app. The runner calls these from the coroutine that called `run` (any dispatcher) unless noted.

```kotlin
val host = object : RunnerHost {
    override suspend fun confirm(callId: String, request: ApprovalRequest) = askUserInMyUi(request)
    override fun started(callId: String) = markRunning(callId)
    override fun status(callId: String, status: ToolStatus?, engine: String?, tabId: Int?) = showProgress(callId, status, tabId)
    override suspend fun saveArtifact(name: String, mime: String, bytes: ByteArray) = myStore.save(name, mime, bytes)
    override fun numberSource(url: String, title: String, snippet: String?) = sources.getOrPut(url) { sources.size + 1 }
}
```

| Callback | Return value and when |
|---|---|
| `confirm` | `MoneyConfirmPolicy` wants a yes before paying, ordering or deleting an account. `ApprovalRequest` has `title`, `target` (the element's label), `preview: Bitmap?` (a picture of the element) and `pageUrl`. True goes on, false makes the call DENIED. The tab is unlocked while it waits. An exception fails the call. |
| `started` | Optional. The call passed the unknown-tool and argument checks and starts. |
| `status` | What the call waits for (opening, settling, a page dialog), the search engine in use and the call's tab; all null = nothing to wait for. Any thread, the main thread included: return at once. |
| `saveArtifact` | Stores a tool artifact (a page picture for a vision model) and returns its id. The id ends up in `ToolOutcome.artifactIds` (to show in the chat) and `imageForModelId`: with `vision = true`, attach that newest image to the model's next request. An exception fails the call. |
| `numberSource` | The citation number for a URL, stable within one answer; the model cites it as [n]. |

## Default policies

| Policy | Does |
|---|---|
| `SiteBlockPolicy` | Refuses to read or operate banks, payments, government and similar sites (built-in list, switchable) and the user's own list; `SiteBlockSettings` stores both. |
| `MoneyConfirmPolicy` | Asks (`RunnerHost.confirm`) before a click or Enter that pays, orders or deletes an account (English and Korean labels). |
| `SecretValuePolicy` | Hides values of password and card fields in results. |
| `CookieMaskPolicy` | Hides the page's cookie values in results. |
| `RepeatFailurePolicy` | Tells the model when the same call fails twice in a turn. |
| `TabMemoryPolicy` | Keeps at most K tabs awake (least recently used ones sleep); lowers K after a memory kill. |

With no policies at all every tool still does its whole job. Strings (en, ko) come with the module.

A policy of your own is a `ToolPolicy` (or a `BrowserPolicy` for engine events) in the list:

```kotlin
class NoExecute : ToolPolicy {
    override fun hiddenTools() = setOf("execute") // the model never sees it
    override suspend fun before(call: PolicyCall) =
        if (call.tab?.url?.contains("intranet") == true) Verdict.Refuse("This site is off limits.") else Verdict.Allow
}
```

## WebView limits

- Google sign-in is refused inside a WebView (`disallowed_useragent`); app-to-app sign-in (Kakao, Naver) returns to the
  system browser, not here. A site's own ID and password sign-in, and popup sign-in, work.
- There is no PDF viewer: a PDF link is saved as a download.
- Script-made key events are untrusted: handlers get them, but they do not type or submit.
