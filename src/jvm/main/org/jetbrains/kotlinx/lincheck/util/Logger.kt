/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.util

import java.io.*
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*

internal object Logger {
    private val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss.SSS")
    private var logWriter: Writer = BufferedWriter(
    System.getProperty("lincheck.log.file")?.let { logFilename ->
            runCatching {
                val file = File(logFilename)
                initFile(file)
                FileWriter(file)
            }.getOrNull()
        } ?: System.err.writer()
    )
    private var logLevel: LoggingLevel = System.getProperty("lincheck.log.level")?.uppercase()?.let {
        runCatching { LoggingLevel.valueOf(it) }.getOrElse { DEFAULT_LOG_LEVEL }
    } ?: DEFAULT_LOG_LEVEL

    fun error(lazyMessage: () -> String) = log(LoggingLevel.ERROR, lazyMessage)
    fun error(e: Throwable) = error {
        StringWriter().use {
            e.printStackTrace(PrintWriter(it))
        }.toString()
    }
    fun warn(lazyMessage: () -> String) = log(LoggingLevel.WARN, lazyMessage)
    fun info(lazyMessage: () -> String) = log(LoggingLevel.INFO, lazyMessage)
    fun debug(lazyMessage: () -> String) = log(LoggingLevel.DEBUG, lazyMessage)

    private inline fun log(logLevel: LoggingLevel, lazyMessage: () -> String) {
        if (logLevel >= this.logLevel) {
            write(logLevel, lazyMessage(), logWriter)
        }
    }

    private fun write(logLevel: LoggingLevel, s: String, writer: Writer) {
        try {
            writer.write("[${getCurrentTimeStamp()}] [${logLevel.name}] $s$LINE_SEPARATOR")
            writer.flush()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun getCurrentTimeStamp(): String {
        return formatter.format(LocalDateTime.now())
    }

    private fun initFile(file: File) {
        // create parent directories
        file.parentFile?.let { if (!it.exists()) it.mkdirs() }

        // create file
        if (file.exists()) file.delete()
        file.createNewFile()
    }
}

private val LINE_SEPARATOR = System.lineSeparator()

@JvmField val DEFAULT_LOG_LEVEL = LoggingLevel.WARN
enum class LoggingLevel {
    DEBUG, INFO, WARN, ERROR, OFF
}