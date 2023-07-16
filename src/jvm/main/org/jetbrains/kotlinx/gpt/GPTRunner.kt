/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.gpt

import com.apurebase.arkenv.Arkenv
import com.apurebase.arkenv.util.argument
import com.apurebase.arkenv.util.parse
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.commons.io.FileUtils
import org.jetbrains.kotlinx.gpt.structure.GptTest
import org.jetbrains.kotlinx.gpt.structure.modelCheckingFailure
import org.jetbrains.kotlinx.lincheck.toJson
import java.io.File

fun main(args: Array<String>) {
    val arguments = Arguments().parse(args)
    val dirToSaveFiles = arguments.path
    FileUtils.cleanDirectory(dirToSaveFiles)
    val failure = modelCheckingFailure()
    if (failure == null) {
        saveStatus(dirToSaveFiles, RunStatus.OK)
        return
    }

    saveStatus(dirToSaveFiles, RunStatus.FAILURE)

    val jsonFailure = failure.toJson()
    dirToSaveFiles.createSubFile("output.json").writeText(jsonFailure)

    val textFailure = failure.toString()
    dirToSaveFiles.createSubFile("output.txt").writeText(textFailure)
}

private fun saveStatus(directoryToSaveFiles: File, status: RunStatus) {
    val statusFile = directoryToSaveFiles.createSubFile("status.json")
    statusFile.writeText(Json.encodeToString(RunStatusJsonReport(status)))
}

private fun File.createSubFile(name: String): File {
    return File(this.absolutePath + File.separator + name)
}

@Serializable
data class RunStatusJsonReport(val runStatus: RunStatus)

enum class RunStatus {
    OK,
    FAILURE
}

class Arguments : Arkenv() {
    val path: File by argument("--path") {
        description = "Path to config"
        mapping = { File(it) }
        validate("Path must lead to existing dir") { it.isDirectory }
    }
}
