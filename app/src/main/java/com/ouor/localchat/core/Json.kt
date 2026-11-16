package com.ouor.localchat.core

import kotlinx.serialization.json.Json

/**
 * The one Json used for DB body columns, DataStore files and backups, so all three share one format.
 * Unknown keys are ignored so files with added fields still decode (architecture 8.4, 9.2).
 */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
