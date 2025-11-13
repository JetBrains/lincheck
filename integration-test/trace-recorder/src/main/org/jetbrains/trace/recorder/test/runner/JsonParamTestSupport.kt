/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.trace.recorder.test.runner

import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Basic JSON entry describing how to run a single test method.
 * Optional fields allow specific test families to pass extra args.
 */
@Serializable
data class JsonTestEntry(
    @SerialName("class") val className: String,
    val gradleCommand: String,
    val methods: List<String>,
    val jvmArgs: List<String> = emptyList(),
    val checkRepresentation: Boolean = false,
)

internal fun loadResourceText(resourcePath: String, loader: Class<*>): String {
    val stream = loader.getResourceAsStream(resourcePath)
        ?: error("Resource not found: $resourcePath. Ensure it’s on the test runtime classpath")
    return stream.use { inp ->
        InputStreamReader(inp, StandardCharsets.UTF_8).buffered().readText()
    }
}

private val jsonParser = Json { ignoreUnknownKeys = true }

internal fun parseJsonEntries(json: String): List<JsonTestEntry> {
    return jsonParser.decodeFromString(json)
}

internal fun List<JsonTestEntry>.transformEntriesToArray(): List<Array<Any>> =
    flatMap { (className, gradleCommand, methods, jvmArgs, checkRepresentation) ->
        methods.map { arrayOf(className, it, gradleCommand, jvmArgs, checkRepresentation) }
    }
