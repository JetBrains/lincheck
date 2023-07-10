/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.lang.IllegalArgumentException
import java.util.*

/**
 * Contains properties, applying which helps to reproduce exact execution.
 */
@Serializable
data class ReproduceSettings(
    val randomSeedGeneratorSeed: Long
)

/**
 * Encapsulates logic of [ReproduceSettings] creation, storing required parameters.
 */
class ReproduceSettingsFactory(
    private val seedGeneratorSeed: Long
) {

    fun createReproduceSettings() = ReproduceSettings(seedGeneratorSeed)

}

/**
 * Provides encoding and decoding of [ReproduceSettings].
 *
 * At the first stage converts settings into JSON string.
 * Then encodes it in Base64 format.
 */
internal object ConfigurationStringEncoder {

    private val base64Encoder = Base64.getEncoder()
    private val base64Decoder = Base64.getDecoder()
    private val json = Json

    fun encodeToConfigurationString(reproduceSettings: ReproduceSettings): String {
        return base64Encoder.encodeToString(json.encodeToString(reproduceSettings).toByteArray())
    }

    fun decodeReproduceSettings(configuration: String): ReproduceSettings = runCatching {
        val decodedJson = base64Decoder.decode(configuration)
        return json.decodeFromString(decodedJson.decodeToString())
    }.getOrElse { cause ->
        val message =
            "Supplied reproduce settings string is not valid or is not supported by this version of Lincheck. Please ensure it is correct"
        throw IllegalArgumentException(message, cause)
    }
}