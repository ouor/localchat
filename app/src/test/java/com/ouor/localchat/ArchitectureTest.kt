package com.ouor.localchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Package rules of architecture 2.2 and the ui surface of 10.2. When those tables change, change
 * [allowed], [uiSurface] and [dbLayers] in the same PR. Files directly under the root package and in
 * `di` form the app layer. Imports and fully qualified names in code are both checked.
 */
class ArchitectureTest {

    private val root = File("src/main/java/com/ouor/localchat")

    /** Layer -> app layers it may import (besides itself). Nobody may import ui or app. */
    private val allowed = mapOf(
        "app" to setOf("ui", "chat", "backup", "browser", "provider", "storage", "attach", "speech", "tools", "llm", "policy", "core"),
        "ui" to setOf("chat", "provider", "browser", "backup", "attach", "speech", "storage", "policy", "core"),
        "backup" to setOf("chat", "provider", "storage", "core"),
        "chat" to setOf("tools", "browser", "provider", "llm", "attach", "storage", "policy", "core"),
        // browser-layers 1-3: layer 3 sees layers 1 and 2; they never see it.
        "policy" to setOf("browser", "tools", "storage", "core"),
        "tools" to setOf("browser", "storage", "core"),
        "browser" to setOf("storage", "core"),
        "attach" to setOf("storage", "core"),
        "provider" to setOf("llm", "storage", "core"),
        "llm" to setOf("core"),
        "storage" to setOf("core"),
        "speech" to setOf("core"),
        "core" to emptySet(),
    )

    /**
     * 10.2: the only files of other layers whose top-level types ui may use (core is free). A type
     * declared in one of these files is part of the surface.
     */
    private val uiSurface = mapOf(
        "chat" to listOf("ChatEngine.kt", "SessionRepository.kt", "DraftRepository.kt", "run/RunState.kt"),
        "provider" to listOf("ProviderRepository.kt", "Presets.kt"),
        "attach" to listOf("AttachmentIngest.kt"),
        "browser" to listOf("BrowserEngine.kt", "BrowserEvent.kt", "BrowserFacts.kt", "Highlight.kt", "SearchEngines.kt"), // element types of its public flows; the address bar's search URL
        "policy" to POLICY_GENERIC,
        "backup" to listOf("BackupService.kt"),
        "storage" to listOf("SettingsStore.kt"),
        "speech" to listOf("VoiceInput.kt", "ReadAloud.kt"),
    )

    /** browser-layers S20: outside policy/ and di/ only these files' types are used, never one policy by name. */
    private val policyGeneric by lazy { surfaceTypes("policy", POLICY_GENERIC) }

    /** browser-layers 4-1: what policies read of layer 2. */
    private val policyReadsOfTools = setOf("tools.ToolOutcome", "tools.ToolFacts", "tools.TargetRule")

    /** 2.2: Room entities and DAOs stay inside these layers. */
    private val dbLayers = setOf("storage", "chat", "provider", "attach", "backup", "app")

    /** Violations found when the rules were tightened (review F-07); the owner removes the entry with the fix. */
    private val known = listOf<String>(
    )

    /** Layers that must stay free of Android so they can move to a JVM module (2.1). */
    private val noAndroid = setOf("core", "llm")

    private val appRef = Regex("""(?<![\w.])com\.ouor\.localchat\.([\w.]*\w)""")
    private val androidRef = Regex("""(?<![\w.])(androidx?|dagger\.hilt\.android)\.[a-z]""")
    private val stringLiteral = Regex("""\"(?:[^\"\\]|\\.)*\"""")
    private val topLevel = Regex("""^(?:(?:public|data|sealed|enum|value|abstract|open|annotation|inline|const|suspend|fun|operator|infix)\s+)*(?:class|interface|object|typealias|fun|val|var)\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?(\w+)""")

    private val files by lazy { root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    private val uiAllowed by lazy { uiSurface.flatMap { (layer, names) -> surfaceTypes(layer, names) }.toSet() }

    private fun surfaceTypes(layer: String, names: List<String>): Set<String> = names.flatMap { name ->
        val file = File(root, "$layer/$name")
        require(file.isFile) { "surface file missing: ${file.path}" }
        val pkg = "$layer/$name".substringBeforeLast('/').replace('/', '.')
        file.readLines().mapNotNull { topLevel.find(it)?.groupValues?.get(1) }.map { "$pkg.$it" }
    }.toSet()

    @Test
    fun `references follow the dependency table`() {
        assertTrue("no sources found under ${root.absolutePath}", files.size > 10)
        val violations = files.flatMap(::violationsOf)
        val fresh = violations.filter { v -> known.none { v.startsWith(it) } }
        assertTrue(fresh.joinToString("\n", "Architecture rule violations:\n"), fresh.isEmpty())
        val fixed = known.filter { k -> violations.none { it.startsWith(k) } }
        assertTrue("fixed, remove from known: $fixed", fixed.isEmpty())
    }

    @Test
    fun `the checker catches what it should`() {
        assertEquals(listOf("may not use llm"), check("tools", "x = com.ouor.localchat.llm.ChatCompletionsClient(a)"))
        assertEquals(listOf("Android reference"), check("core", "import androidx.annotation.Keep"))
        assertEquals(listOf("Android reference"), check("llm", "import dagger.hilt.android.qualifiers.ApplicationContext"))
        assertEquals(listOf("Android reference"), check("core", "val t = android.os.SystemClock.uptimeMillis()"))
        assertEquals(listOf("not in the 10.2 surface: chat.run.TurnRunner"), check("ui", "import com.ouor.localchat.chat.run.TurnRunner"))
        assertEquals(listOf("Room types stay in storage users: storage.db.dao.ChatDao"), check("tools", "import com.ouor.localchat.storage.db.dao.ChatDao"))
        assertEquals(emptyList<String>(), check("ui", "import com.ouor.localchat.chat.run.Pause.Approval"))
        assertEquals(emptyList<String>(), check("ui", "import com.ouor.localchat.R"))
        assertEquals(emptyList<String>(), check("ui", "import com.ouor.localchat.browser.DialogKind"))
        assertEquals(emptyList<String>(), check("ui", "import com.ouor.localchat.browser.Highlight"))
        assertEquals(emptyList<String>(), check("core", "// see com.ouor.localchat.llm.X and android.os"))
        assertEquals(emptyList<String>(), check("chat", "const val A = \"com.ouor.localchat.action.STOP\""))
        // browser-layers 1-3, S20
        assertEquals(listOf("may not use policy"), check("browser", "import com.ouor.localchat.policy.Policies"))
        assertEquals(listOf("may not use policy"), check("tools", "import com.ouor.localchat.policy.ApprovalRequest"))
        assertEquals(listOf("policy internals stay in policy/ and di/: policy.SiteBlockPolicy"), check("chat", "import com.ouor.localchat.policy.SiteBlockPolicy"))
        assertEquals(emptyList<String>(), check("chat", "import com.ouor.localchat.policy.Policies"))
        assertEquals(emptyList<String>(), check("app", "import com.ouor.localchat.policy.SiteBlockPolicy", di = true))
        assertEquals(listOf("policy reads only tool facts and outcomes: tools.browser.Click"), check("policy", "import com.ouor.localchat.tools.browser.Click"))
        assertEquals(emptyList<String>(), check("policy", "import com.ouor.localchat.tools.ToolFacts"))
    }

    private fun violationsOf(file: File): List<String> {
        val layer = layerOf(file)
        requireNotNull(allowed[layer]) { "unknown layer '$layer' for ${file.path}: add it to the table" }
        val di = file.relativeTo(root).invariantSeparatorsPath.startsWith("di/")
        return file.readLines().flatMap { line -> check(layer, line, di).map { "${file.relativeTo(root).invariantSeparatorsPath}: $it (${line.trim()})" } }
    }

    private fun check(layer: String, raw: String, di: Boolean = false): List<String> {
        val line = raw.trim()
        if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*") || line.startsWith("package ")) return emptyList()
        val code = line.replace(stringLiteral, "\"\"")
        val android = if (layer in noAndroid && androidRef.containsMatchIn(code)) listOf("Android reference") else emptyList()
        return android + appRef.findAll(code).mapNotNull { problem(layer, it.groupValues[1], di) }
    }

    private fun problem(layer: String, target: String, di: Boolean): String? {
        // Generated resources (R) belong to no layer.
        if (target == "R" || target.startsWith("R.")) return null
        val targetLayer = layerOfImport(target)
        if (targetLayer == layer) return null
        if (targetLayer !in allowed.getValue(layer)) return "may not use $targetLayer"
        if (target.startsWith("storage.db") && layer !in dbLayers) return "Room types stay in storage users: $target"
        if (targetLayer == "policy" && !di && typeOf(target) !in policyGeneric) return "policy internals stay in policy/ and di/: ${typeOf(target)}"
        if (layer == "policy" && targetLayer == "tools" && typeOf(target) !in policyReadsOfTools) return "policy reads only tool facts and outcomes: ${typeOf(target)}"
        if (layer == "ui" && targetLayer != "core" && typeOf(target) !in uiAllowed) return "not in the 10.2 surface: ${typeOf(target)}"
        return null
    }

    private companion object {
        val POLICY_GENERIC = listOf("Policy.kt", "ToolPolicy.kt", "BrowserPolicy.kt", "Policies.kt")
    }

    /** `chat.run.Pause.Approval` -> `chat.run.Pause`; a top-level function stays as it is. */
    private fun typeOf(target: String): String {
        val parts = target.split('.')
        val cls = parts.indexOfFirst { it.first().isUpperCase() }
        return if (cls < 0) target else parts.take(cls + 1).joinToString(".")
    }

    private fun layerOf(file: File): String {
        val first = file.relativeTo(root).invariantSeparatorsPath.substringBefore('/', missingDelimiterValue = "")
        return if (first.isEmpty() || first == "di") "app" else first
    }

    /** `chat.run.RunState` -> chat; `App`, `MainActivity`, `di.X` -> app. */
    private fun layerOfImport(target: String): String {
        val first = target.substringBefore('.')
        return if (first == "di" || first.first().isUpperCase()) "app" else first
    }
}
