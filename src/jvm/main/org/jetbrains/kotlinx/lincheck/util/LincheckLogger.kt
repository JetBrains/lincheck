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
import java.util.*

object LincheckLogger {
    private val LINE_SEPARATOR = System.lineSeparator()
    private val formatter: ThreadLocal<SimpleDateFormat> = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat {
            return SimpleDateFormat("dd.MM.yyyy HH:mm:ss")
        }
    }

    private val logFilename = System.getProperty("lincheck.log.file") ?: null
    private val logFile = if (logFilename != null) File(logFilename) else null
    private var logWriter: Writer? = null

    init {
        println("property: lincheck.log.file = " + System.getProperty("lincheck.log.file"))
        logFile?.let {
            initFile(it)
            logWriter = BufferedWriter(FileWriter(it))
            println("Lincheck log file could be found at: ${it.absolutePath}")
        }
    }

    /**
     * Logs will be printed to log file if it exists.
     */
    fun log(s: String) {
        logFile?.let {
            write(s, logWriter!!)
        }
    }

    /**
     * Errors will be printed to log file if it is set.
     * However, they will always be printed to the `System.err`.
     */
    fun error(m: String, e: Throwable) {
        logFile?.let {
            write(m, e, logWriter!!)
        }
        System.err.println(m)
        e.printStackTrace(System.err)
    }

    private fun write(m: String, e: Throwable, writer: Writer) {
        try {
            writer.write(getCurrentTimeStamp() + " " + m + LINE_SEPARATOR)
            writer.flush()
            e.printStackTrace(PrintWriter(writer))
        }
        catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun write(s: String, writer: Writer) {
        try {
            writer.write(getCurrentTimeStamp() + " " + s + LINE_SEPARATOR)
            writer.flush()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun getCurrentTimeStamp(): String {
        return "[" + formatter.get().format(Date()) + "]"
    }

    private fun initFile(file: File) {
        // create parent directories
        file.parentFile?.let { if (!it.exists()) it.mkdirs() }

        // create file
        if (file.exists()) file.delete()
        file.createNewFile()
    }
}
