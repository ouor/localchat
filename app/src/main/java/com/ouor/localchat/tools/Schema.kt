package com.ouor.localchat.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * JSON-schema DSL for tool parameters and argument readers that fail with [ToolError]. Every tool argument is a string.
 *
 * ```
 * override val parameters = params {
 *     string("url", "Absolute http(s) URL", required = true)
 * }
 * ```
 */
fun params(block: ParamsBuilder.() -> Unit): JsonObject = ParamsBuilder().apply(block).build()

class ParamsBuilder {
    private val props = mutableMapOf<String, JsonObject>()
    private val required = mutableListOf<String>()

    fun string(name: String, description: String, required: Boolean = false) {
        props[name] = buildJsonObject { put("type", "string"); put("description", description) }
        if (required) this.required += name
    }

    fun build(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(props))
        if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
    }
}

private fun JsonObject.present(name: String): JsonElement? = this[name]?.takeUnless { it is JsonNull }

/** A string argument; models sometimes send numbers or booleans unquoted, which are read as their text. */
fun JsonObject.optString(name: String): String? = when (val v = present(name)) {
    null -> null
    is JsonPrimitive -> v.content
    else -> throw ToolError("Argument '$name' must be a single value.")
}

fun JsonObject.string(name: String): String =
    optString(name)?.takeIf { it.isNotBlank() } ?: throw ToolError("Missing required argument '$name'.")
