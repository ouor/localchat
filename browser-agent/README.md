# browser-agent

A shared in-app browser that both the user and an AI model operate, as three Android library modules:

| Module | What it is |
|---|---|
| `:browser` | Layer 1, a plain browser: `BrowserEngine` (tabs without owners, front, sleep and wake, events, downloads), `BrowserTab`, page scripts, `BrowserTabHost`. |
| `:browser-tools` | Layer 2, the 12 model-callable tools (`ToolCatalog.ALL`: web_search, open, navigate, read, look, click, type, scroll, execute, tabs, close_tab, ask_user) and their result format. |
| `:browser-agent` | Runs them: `BrowserToolRunner` (target tab, lock and wake, policies, new tab, run, `after`), `BrowserPort`, the policy contracts and six default policies. |

No Hilt, no app settings, no app strings: everything is a plain constructor. minSdk 31.

## Add it

Not published to Maven. Include the modules from a checkout (`includeBuild`, or copy the three folders), then:

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
        SiteBlockPolicy(SensitiveUrls.builtIn(appContext), mySiteBlockSettings, appScope, engine, appContext),
        MoneyConfirmPolicy(appContext),
        SecretValuePolicy(),
        CookieMaskPolicy(),
        RepeatFailurePolicy(),
    ),
    browser = listOf(TabMemoryPolicy(TabMemoryPolicy.startK(totalMemBytes), null) {}),
)
policies.attach(engine, appScope)

// Show tabs: a full-size BrowserTabHost in your Activity is the engine's host container.
val tabHost = BrowserTabHost(activity).also { activity.setContentView(it); it.bind(engine, null) }

// One runner per model turn (sources are numbered per message); ChatTabs per chat.
val runner = BrowserToolRunner("chat-1", { engine }, EngineBrowserPort(engine), policies, host, ToolCatalog.ALL, ChatTabs(),
    contextWindow = 128_000, vision = false)
val result: ToolOutcome = runner.run(callId, toolName, argumentsJson)
val forModel = ToolResultFormat.render(result) // lead, page text inside the boundary tag, note
```

Give the model `ToolCatalog.ALL` minus `policies.hiddenTools()` (name, `description`, `parameters`) and add
`policies.promptLines()` to its system prompt. Calls run one at a time; call `policies.turnStart(id)` /
`turnEnd(id)` around each turn.

## RunnerHost

What differs per app:

```kotlin
val host = object : RunnerHost {
    // MoneyConfirmPolicy wants a yes before paying or deleting an account; request has a title, label and preview.
    override suspend fun confirm(callId: String, request: ApprovalRequest) = askUserInMyUi(request)
    // What the call waits for (opening, settling, a page dialog) and its tab; all null when a call starts.
    override fun status(callId: String, status: ToolStatus?, engine: String?, tabId: Int?) = showProgress(status)
    // Page pictures (vision models) and other artifacts.
    override suspend fun saveArtifact(name: String, mime: String, bytes: ByteArray) = myStore.save(name, mime, bytes)
    // Citation numbers, stable within one answer.
    override fun numberSource(url: String, title: String, snippet: String?) = sources.getOrPut(url) { sources.size + 1 }
}
```

`ask_user`: the result has `userStep` (tab and task). Show that tab (`tabHost.bind(engine, step.tabId)`), let the
user act, and when they are done call `runner.afterUserStep(callId, step)` for the page as they left it.

## Default policies

| Policy | Does |
|---|---|
| `SiteBlockPolicy` | Refuses to read or operate banks, payments, government and similar sites (built-in list, switchable) and the user's own list; `SiteBlockSettings` stores both. |
| `MoneyConfirmPolicy` | Asks (`RunnerHost.confirm`) before a click or Enter that pays, orders or deletes an account. |
| `SecretValuePolicy` | Hides values of password and card fields in results. |
| `CookieMaskPolicy` | Hides the page's cookie values in results. |
| `RepeatFailurePolicy` | Tells the model when the same call fails twice in a turn. |
| `TabMemoryPolicy` | Keeps at most K tabs awake (least recently used ones sleep); lowers K after a memory kill. |

With no policies at all every tool still does its whole job. Strings (en, ko) come with the module.

## WebView limits

- Google sign-in is refused inside a WebView (`disallowed_useragent`); app-to-app sign-in (Kakao, Naver) returns to the
  system browser, not here. A site's own ID and password sign-in, and popup sign-in, work.
- There is no PDF viewer: a PDF link is saved as a download.
- Script-made key events are untrusted: handlers get them, but they do not type or submit.
