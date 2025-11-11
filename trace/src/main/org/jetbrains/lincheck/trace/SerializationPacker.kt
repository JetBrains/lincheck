/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace

import java.io.InputStream
import java.io.PrintWriter
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.Path

fun packRecordedTrace(baseFileName: String, metaInfo: TraceMetaInfo, deleteSources: Boolean = true) {
    val dataName = baseFileName
    val indexName = "$baseFileName.$INDEX_FILENAME_EXT"
    val outputName = "$baseFileName.$PACK_FILENAME_EXT"

    try {
        ZipOutputStream(openNewFile(outputName).buffered(OUTPUT_BUFFER_SIZE)).use { zip ->
            var input: InputStream

            // Don't compress for now, but STORED requires pre-compute CRC32, and we don't want to do it.
            zip.setMethod(ZipOutputStream.DEFLATED)
            zip.setLevel(0)

            // Store metadata first
            zip.putNextEntry(ZipEntry(PACKED_META_ITEM_NAME))
            val printer = PrintWriter(zip)
            metaInfo.print(printer)
            printer.flush() // Don't close, it will close ZIP stream too!
            zip.closeEntry()

            // Store data
            zip.putNextEntry(ZipEntry(PACKED_DATA_ITEM_NAME))
            input = openExistingFile(dataName) ?: throw IllegalArgumentException("Cannot open trace file \"$dataName\"")
            input.use { data ->
                data.copyTo(zip)
            }
            zip.closeEntry()

            // Store Index
            zip.putNextEntry(ZipEntry(PACKED_INDEX_ITEM_NAME))
            input = openExistingFile(indexName) ?: throw IllegalArgumentException("Cannot open trace index file \"$indexName\"")
            input.use { data ->
                data.copyTo(zip)
            }
            zip.closeEntry()
        }
        if (deleteSources) {
            Files.deleteIfExists(Path(dataName))
            Files.deleteIfExists(Path(indexName))
        }
    } catch (e: Throwable) {
        Files.deleteIfExists(Path(outputName))
        throw e
    }
}
