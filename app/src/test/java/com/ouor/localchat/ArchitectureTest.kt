package com.ouor.localchat

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Package import rules of architecture 2.2. When the table there changes, change [allowed] and
 * [banned] in the same PR. Files directly under the root package and in `di` form the app layer.
 */
class ArchitectureTest {

    private val root = File("src/main/java/com/ouor/localchat")

    /** Layer -> app layers it may import (besides itself). Nobody may import ui or app. */
    private val allowed = mapOf(
        "app" to setOf("ui", "chat", "backup", "browser", "provider", "storage", "attach", "speech", "tools", "llm", "core"),
        "ui" to setOf("chat", "provider", "browser", "backup", "attach", "speech", "storage", "core"),
        "backup" to setOf("chat", "provider", "storage", "core"),
        "chat" to setOf("tools", "browser", "provider", "llm", "attach", "storage", "core"),
        "tools" to setOf("browser", "storage", "core"),
        "browser" to setOf("storage", "core"),
        "attach" to setOf("storage", "core"),
        "provider" to setOf("llm", "storage", "core"),
        "llm" to setOf("core"),
        "storage" to setOf("core"),
        "speech" to setOf("core"),
        "core" to emptySet(),
    )

    /** Sub-packages a layer may not import even though the parent layer is allowed. */
    private val banned = mapOf("ui" to listOf("storage.db", "browser.script", "backup.format"))

    /** Layers that must stay free of Android so they can move to a JVM module. */
    private val noAndroid = setOf("core", "llm")

    private val appImport = Regex("""^import com\.ouor\.localchat\.([\w.]+)""")

    @Test
    fun `imports follow the dependency table`() {
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("no sources found under ${root.absolutePath}", files.size > 10)

        val violations = files.flatMap { file ->
            val layer = layerOf(file)
            val rules = requireNotNull(allowed[layer]) { "unknown layer '$layer' for ${file.path}: add it to the table" }
            file.readLines().filter { it.startsWith("import ") }.mapNotNull { line ->
                val target = appImport.find(line)?.groupValues?.get(1)
                val problem = when {
                    layer in noAndroid && line.startsWith("import android.") -> "Android import"
                    // Generated resources (R) belong to no layer.
                    target == null || target == "R" || target.startsWith("R.") -> null
                    else -> {
                        val targetLayer = layerOfImport(target)
                        when {
                            targetLayer != layer && targetLayer !in rules -> "may not import $targetLayer"
                            banned[layer].orEmpty().any { target.startsWith("$it.") } -> "may not import $target"
                            else -> null
                        }
                    }
                }
                problem?.let { "${file.relativeTo(root)}: $it ($line)" }
            }
        }
        assertTrue(violations.joinToString("\n", "Import rule violations:\n"), violations.isEmpty())
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
