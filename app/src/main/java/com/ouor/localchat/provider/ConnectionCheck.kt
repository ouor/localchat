package com.ouor.localchat.provider

import com.ouor.localchat.llm.ChatCompletionsClient
import com.ouor.localchat.llm.LlmException
import com.ouor.localchat.llm.LlmTarget
import com.ouor.localchat.llm.toChatError
import kotlinx.serialization.json.JsonObject

/** P-02 `GET /models`: the result for the UI plus the raw list (empty on failure) for P-03. */
internal suspend fun ChatCompletionsClient.checkModels(target: LlmTarget): Pair<ConnectionResult, List<JsonObject>> =
    try {
        val models = listModels(target)
        ConnectionResult.Ok(models.size) to models
    } catch (e: LlmException) {
        ConnectionResult.Failed(e.error.toChatError()) to emptyList()
    }

/** No retries for a connection check: the user is waiting on the form. */
internal fun EndpointDraft.toTarget(): LlmTarget = LlmTarget(baseUrl.trim(), apiKey?.value, maxRetries = 0, allowHttp = allowHttp)
