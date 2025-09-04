/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.util

import java.io.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object Logger {
    private val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss.SSS")

    private val logFile: File? = System.getProperty("lincheck.logFile")?.let { fileName ->
        File(fileName).also { runCatching { initFile(it) }.getOrNull() }
    }

    val logWriter: Writer = BufferedWriter(
        if (logFile != null) FileWriter(logFile)
        else System.err.writer()
    )

    val logLevel: LoggingLevel = System.getProperty("lincheck.logLevel")?.uppercase()?.let {
        runCatching { LoggingLevel.valueOf(it) }.getOrElse { DEFAULT_LOG_LEVEL }
    } ?: DEFAULT_LOG_LEVEL

    inline fun error(lazyMessage: () -> String) = log(LoggingLevel.ERROR, lazyMessage)

    inline fun warn(lazyMessage: () -> String) = log(LoggingLevel.WARN, lazyMessage)

    inline fun info(lazyMessage: () -> String) = log(LoggingLevel.INFO, lazyMessage)

    inline fun debug(lazyMessage: () -> String) = log(LoggingLevel.DEBUG, lazyMessage)

    fun error(e: Throwable) = log(LoggingLevel.ERROR, e)

    fun warn(e: Throwable) = log(LoggingLevel.WARN, e)

    fun info(e: Throwable) = log(LoggingLevel.INFO, e)

    fun debug(e: Throwable) = log(LoggingLevel.DEBUG, e)

    inline fun log(logLevel: LoggingLevel, lazyMessage: () -> String) {
        if (logLevel >= this.logLevel) {
            write(logLevel, lazyMessage(), logWriter)
        }
    }

    fun write(logLevel: LoggingLevel, s: String, writer: Writer) {
        try {
            writer.write("[${logLevel.name}] $s$LINE_SEPARATOR")
            writer.flush()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun log(logLevel: LoggingLevel, throwable: Throwable) {
        log(logLevel) {
            StringWriter().use { writer ->
                throwable.printStackTrace(PrintWriter(writer))
                writer.toString()
            }
        }
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

@JvmField val DEFAULT_LOG_LEVEL = LoggingLevel.INFO

enum class LoggingLevel {
    DEBUG, INFO, WARN, ERROR, OFF
}