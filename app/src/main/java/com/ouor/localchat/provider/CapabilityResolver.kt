package com.ouor.localchat.provider

import com.ouor.localchat.core.model.CapSource
import com.ouor.localchat.core.model.ModelCapabilities
import com.ouor.localchat.core.model.ModelInfo
import com.ouor.localchat.core.model.ModelOverrides
import com.ouor.localchat.core.model.ReasoningSpec
import com.ouor.localchat.core.model.ReasoningWire
import com.ouor.localchat.core.model.Sourced
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * P-04: ① user ② `/models` extension fields ③ [KnownModels] ④ off/unknown (architecture 3.4 table).
 * Pure; the only place that decides a model's capabilities.
 */
object CapabilityResolver {

    fun resolve(user: ModelOverrides, server: JsonObject?, known: KnownModels.Known?): ModelCapabilities {
        val serverImage = server?.strings("input_modalities")?.let { "image" in it }
        val serverReasoning = (server?.get("effort") as? JsonObject)?.let {
            // The server never says how to switch reasoning; that comes from known models only.
            ReasoningSpec(it.strings("supported_levels").orEmpty(), it.string("default_level"), known?.reasoning?.wire ?: ReasoningWire.EFFORT)
        }
        return ModelCapabilities(
            toolCalling = pick(user.toolCalling, null, known?.toolCalling, false),
            imageInput = pick(user.imageInput, serverImage, known?.imageInput, false),
            reasoning = reasoning(user, pick(null, serverReasoning, known?.reasoning, null)),
            contextWindow = pick(user.contextWindow, server?.int("context_window") ?: server?.int("context_length"), known?.contextWindow, null),
            maxOutputTokens = pick(user.maxOutputTokens, server?.int("max_output_tokens"), known?.maxOutputTokens, null),
        )
    }

    /** user > server `name` > model id. */
    fun displayName(modelId: String, user: ModelOverrides, server: JsonObject?): String =
        user.displayName?.takeIf { it.isNotBlank() } ?: server?.string("name")?.takeIf { it.isNotBlank() } ?: modelId

    private fun reasoning(user: ModelOverrides, auto: Sourced<ReasoningSpec?>): Sourced<ReasoningSpec?> = when {
        user.reasoning == false -> Sourced(null, CapSource.USER)
        user.reasoning == true || user.reasoningEfforts != null -> {
            val base = auto.value ?: ReasoningSpec(emptyList(), null, ReasoningWire.EFFORT)
            val efforts = user.reasoningEfforts ?: base.efforts
            Sourced(base.copy(efforts = efforts, defaultEffort = base.defaultEffort?.takeIf { it in efforts }), CapSource.USER)
        }
        else -> auto
    }

    private fun <T> pick(user: T?, server: T?, known: T?, none: T): Sourced<T> = when {
        user != null -> Sourced(user, CapSource.USER)
        server != null -> Sourced(server, CapSource.SERVER)
        known != null -> Sourced(known, CapSource.DEFAULT)
        else -> Sourced(none, CapSource.UNKNOWN)
    }

    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String) = (get(key) as? JsonPrimitive)?.intOrNull
    private fun JsonObject.strings(key: String) = (get(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
}

/**
 * How to switch reasoning in a request for this model (pass to `ChatRequest.withReasoning`).
 * Falls back to [KnownModels] so a DeepSeek model the user turned reasoning off for still gets
 * `thinking: disabled` (its default is ON).
 */
val ModelInfo.reasoningWire: ReasoningWire?
    get() = caps.reasoning.value?.wire ?: KnownModels.find(modelId)?.reasoning?.wire
