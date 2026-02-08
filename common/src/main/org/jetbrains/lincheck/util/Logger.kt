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

/**
 * Logging utilities for the Lincheck framework.
 *
 * Supports logging to `stderr` or to a specified file.
 * If a log file is specified through the `lincheck.logFile` system property,
 * then the file-based logging is used. Otherwise, messages are logged to the standard error stream.
 *
 * Logging levels can be configured using the `lincheck.logLevel` system property, see [LoggingLevel].
 *
 * NOTE: when stderr is used, log messages from shutdown hooks are not guaranteed to be written.
 */
object Logger {

    val logFile: File? = System.getProperty("lincheck.logFile")?.let { fileName ->
        File(fileName).also { runCatching { initFile(it) }.getOrNull() }
    }

    val logWriter: Writer = BufferedWriter(
        if (logFile != null) FileWriter(logFile)
        else System.err.writer()
    )

    val logLevel: LoggingLevel = System.getProperty("lincheck.logLevel")?.uppercase()?.let {
        runCatching { LoggingLevel.valueOf(it) }.getOrElse { DEFAULT_LOG_LEVEL }
    } ?: DEFAULT_LOG_LEVEL

    inline fun error(lazyMessage: () -> String) {
        log(LoggingLevel.ERROR, lazyMessage)
    }

    inline fun warn(lazyMessage: () -> String) {
        log(LoggingLevel.WARN, lazyMessage)
    }

    inline fun info(lazyMessage: () -> String) {
        log(LoggingLevel.INFO, lazyMessage)
    }

    inline fun debug(lazyMessage: () -> String) {
        log(LoggingLevel.DEBUG, lazyMessage)
    }

    inline fun error(e: Throwable, lazyMessage: () -> String = { e.message ?: "" }) {
        log(LoggingLevel.ERROR, e, lazyMessage)
    }

    inline fun warn(e: Throwable, lazyMessage: () -> String = { e.message ?: "" }) {
        log(LoggingLevel.WARN, e, lazyMessage)
    }

    inline fun info(e: Throwable, lazyMessage: () -> String = { e.message ?: "" }) {
        log(LoggingLevel.INFO, e, lazyMessage)
    }

    inline fun debug(e: Throwable, lazyMessage: () -> String = { e.message ?: "" }) {
        log(LoggingLevel.DEBUG, e, lazyMessage)
    }

    inline fun log(logLevel: LoggingLevel, lazyMessage: () -> String) {
        if (logLevel >= this.logLevel) {
            try {
                logWriter.write("[${logLevel.name}] ${lazyMessage()}$LINE_SEPARATOR")
                logWriter.flush()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    inline fun log(logLevel: LoggingLevel, throwable: Throwable, lazyMessage: () -> String = { throwable.message ?: "" }) {
        log(logLevel) {
            val writer = StringWriter().apply {
                PrintWriter(this).use { printer ->
                    throwable.printStackTrace(printer)
                }
            }
            val message = lazyMessage()
            val stackTrace = writer.toString()
            if (message.isNotEmpty()) {
                val paddedStackTrace = stackTrace.lines().joinToString(separator = LINE_SEPARATOR) { TAB + it }
                "${message}${LINE_SEPARATOR}${paddedStackTrace}"
            } else {
                stackTrace
            }
        }
    }

    private fun initFile(file: File) {
        // create parent directories
        file.parentFile?.let { parent ->
            if (!parent.exists()) parent.mkdirs()
        }

        // create the file
        if (file.exists()) {
            file.delete()
        }
        file.createNewFile()
    }

    val TAB: String = "\t"
    val LINE_SEPARATOR: String = System.lineSeparator()
}

@JvmField val DEFAULT_LOG_LEVEL = LoggingLevel.WARN

enum class LoggingLevel {
    DEBUG, INFO, WARN, ERROR, OFF
}