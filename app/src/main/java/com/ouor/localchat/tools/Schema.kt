package com.ouor.localchat.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * JSON-schema DSL for tool parameters and typed argument readers that fail with [ToolError].
 *
 * ```
 * override val parameters = params {
 *     string("url", "Absolute http(s) URL", required = true)
 *     int("tabId", TAB_ID)
 * }
 * ```
 */
fun params(block: ParamsBuilder.() -> Unit): JsonObject = ParamsBuilder().apply(block).build()

class ParamsBuilder {
    private val props = mutableMapOf<String, JsonObject>()
    private val required = mutableListOf<String>()

    fun string(name: String, description: String, required: Boolean = false, enum: List<String>? = null) =
        prop(name, "string", description, required) { if (enum != null) putJsonArray("enum") { enum.forEach { add(JsonPrimitive(it)) } } }

    fun int(name: String, description: String, required: Boolean = false) = prop(name, "integer", description, required)
    fun number(name: String, description: String, required: Boolean = false) = prop(name, "number", description, required)
    fun bool(name: String, description: String, required: Boolean = false) = prop(name, "boolean", description, required)

    fun stringArray(name: String, description: String, required: Boolean = false) =
        prop(name, "array", description, required) { put("items", buildJsonObject { put("type", "string") }) }

    private fun prop(name: String, type: String, description: String, req: Boolean, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) {
        props[name] = buildJsonObject { put("type", type); put("description", description); extra() }
        if (req) required += name
    }

    fun build(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(props))
        if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
    }
}

// --- argument readers. Models sometimes send numbers as strings and booleans as "true"; both are accepted.

private fun JsonObject.present(name: String): JsonElement? = this[name]?.takeUnless { it is JsonNull }

private fun JsonObject.prim(name: String): JsonPrimitive? = when (val v = present(name)) {
    null -> null
    is JsonPrimitive -> v
    else -> throw ToolError("Argument '$name' must be a single value.")
}

fun JsonObject.optString(name: String): String? = prim(name)?.content

fun JsonObject.string(name: String): String =
    optString(name)?.takeIf { it.isNotBlank() } ?: throw ToolError("Missing required argument '$name'.")

fun JsonObject.optInt(name: String): Int? {
    val p = prim(name) ?: return null
    val d = p.doubleOrNull ?: p.content.trim().toDoubleOrNull() ?: throw ToolError("Argument '$name' must be an integer.")
    if (d != Math.floor(d) || d > Int.MAX_VALUE || d < Int.MIN_VALUE) throw ToolError("Argument '$name' must be an integer.")
    return d.toInt()
}

fun JsonObject.optNumber(name: String): Double? {
    val p = prim(name) ?: return null
    return p.doubleOrNull ?: p.content.trim().toDoubleOrNull() ?: throw ToolError("Argument '$name' must be a number.")
}

fun JsonObject.optBool(name: String): Boolean? {
    val p = prim(name) ?: return null
    return p.booleanOrNull ?: when (p.content.trim().lowercase()) {
        "true" -> true
        "false" -> false
        else -> throw ToolError("Argument '$name' must be true or false.")
    }
}

fun JsonObject.optStringList(name: String): List<String>? = when (val v = present(name)) {
    null -> null
    is JsonArray -> v.map { (it as? JsonPrimitive)?.content ?: throw ToolError("Argument '$name' must be a list of strings.") }
    is JsonPrimitive -> listOf(v.content)
    else -> throw ToolError("Argument '$name' must be a list of strings.")
}

/** Integer argument limited to [range]; out-of-range values are clamped, not rejected. */
fun JsonObject.optIntIn(name: String, range: IntRange): Int? = optInt(name)?.coerceIn(range)
