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

import org.jetbrains.lincheck.util.Logger
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
            // Don't compress for now, but STORED requires pre-compute CRC32, and we don't want to do it.
            zip.setMethod(ZipOutputStream.DEFLATED)
            zip.setLevel(0)

            // Save main meta
            saveMeta(zip, PACKED_META_ITEM_NAME, metaInfo)
            // Store data
            copyFileIntoZip(zip, PACKED_DATA_ITEM_NAME, dataName, "data")
            // Store Index
            copyFileIntoZip(zip, PACKED_INDEX_ITEM_NAME, indexName, "index")
        }
        if (deleteSources) {
            Files.deleteIfExists(Path(dataName))
            Files.deleteIfExists(Path(indexName))
        }
        Logger.info { "Packed trace size: ${Files.size(Path(outputName))} bytes" }
    } catch (e: Throwable) {
        Files.deleteIfExists(Path(outputName))
        throw e
    }
}

internal fun packDiff(baseFileName: String, idMapName: String, threadMapName: String, metaInfo: TraceMetaInfo) {
    val dataName = baseFileName
    val indexName = "$baseFileName.$INDEX_FILENAME_EXT"
    val outputName = "$baseFileName.$PACK_FILENAME_EXT"

    try {
        ZipOutputStream(openNewFile(outputName).buffered(OUTPUT_BUFFER_SIZE)).use { zip ->
            // Don't compress for now, but STORED requires pre-compute CRC32, and we don't want to do it.
            zip.setMethod(ZipOutputStream.DEFLATED)
            zip.setLevel(0)

            if (metaInfo.isDiff) {
                // Store diff marker, if needed
                zip.putNextEntry(ZipEntry(PACKED_DIFF_MARKER_ITEM_NAME))
                zip.closeEntry()
                // Store left & right metadata, if present
                saveMeta(zip, PACKED_LEFT_META_ITEM_NAME, metaInfo.leftTraceMetaInfo)
                saveMeta(zip, PACKED_RIGHT_META_ITEM_NAME, metaInfo.rightTraceMetaInfo)
            }

            // Save main meta
            saveMeta(zip, PACKED_META_ITEM_NAME, metaInfo)
            // Store data
            copyFileIntoZip(zip, PACKED_DATA_ITEM_NAME, dataName, "data")
            // Store Index
            copyFileIntoZip(zip, PACKED_INDEX_ITEM_NAME, indexName, "index")
            // Store idMap
            copyFileIntoZip(zip, PACKED_ID_MAP_ITEM_NAME, idMapName, "id map")
            // Store threadMap
            copyFileIntoZip(zip, PACKED_THREAD_MAP_ITEM_NAME, threadMapName, "thread map")
        }
        Files.deleteIfExists(Path(dataName))
        Files.deleteIfExists(Path(indexName))
        Files.deleteIfExists(Path(idMapName))
        Files.deleteIfExists(Path(threadMapName))
        Logger.info { "Packed diff size: ${Files.size(Path(outputName))} bytes" }
    } catch (e: Throwable) {
        Files.deleteIfExists(Path(outputName))
        throw e
    }
}

private fun copyFileIntoZip(
    zip: ZipOutputStream,
    zipEntryName: String,
    sourceFileName: String,
    fileType: String
) {
    zip.putNextEntry(ZipEntry(zipEntryName))
    val input = openExistingFile(sourceFileName) ?: throw IllegalArgumentException("Cannot open $fileType file \"$sourceFileName\"")
    input.use { data ->
        data.copyTo(zip)
    }
    zip.closeEntry()
}

private fun saveMeta(zip: ZipOutputStream, zipEntryName: String, metaInfo: TraceMetaInfo?) {
    if (metaInfo == null) return
    zip.putNextEntry(ZipEntry(zipEntryName))
    val printer = PrintWriter(zip)
    metaInfo.print(printer)
    printer.flush() // Don't close, it will close ZIP stream too!
    zip.closeEntry()
}
