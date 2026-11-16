package com.ouor.localchat.backup.format

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Backup format migrations (K-06, architecture 9.2). JSON to JSON, so no old Kotlin types are kept.
 * Adding format version N+1: one object here + one line in [MIGRATIONS] + bump BackupFormat.CURRENT
 * + a new golden zip under src/test/resources/backup/vN+1/.
 */
interface FormatMigration {
    /** Migrates from this version to from + 1. */
    val from: Int

    fun manifest(o: JsonObject): JsonObject = o
    fun settings(o: JsonObject): JsonObject = o
    fun providers(o: JsonObject): JsonObject = o
    fun attachments(a: JsonArray): JsonArray = a
    fun session(o: JsonObject): JsonObject = o
}

val MIGRATIONS: List<FormatMigration> = listOf(
    // V1ToV2,
)

/** Applies every migration from [version] up to BackupFormat.CURRENT in order. */
internal fun <T> migrate(
    version: Int,
    value: T,
    migrations: List<FormatMigration> = MIGRATIONS,
    step: FormatMigration.(T) -> T,
): T = migrations.filter { it.from >= version }.sortedBy { it.from }.fold(value) { acc, m -> m.step(acc) }
