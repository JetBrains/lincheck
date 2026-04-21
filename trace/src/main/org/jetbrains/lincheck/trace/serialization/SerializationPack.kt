/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.serialization

import org.jetbrains.lincheck.util.Logger
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.collections.iterator
import kotlin.io.path.Path

/**
 * This file contains all logic to make packed trace files from raw (recorded) data and open such files.
 *
 * Packed trace is ZIP file without compression (to make access faster). Names of packed files (entries) in this
 * ZIP archive are fixed. Such packed trace can contain both «simple» traces and diffs between traces.
 *
 * Entries in archive are:
 *  - [PACKED_META_ITEM_NAME] (now "info.txt"), mandatory — text file, which describes this trace (see [TraceMetaInfo])
 *  - [PACKED_DATA_ITEM_NAME] (now "trace.trace"), mandatory — complete binary trace, which can be read in isolation.
 *  - [PACKED_INDEX_ITEM_NAME] (now "trace.idx"), mandatory — index for all trace data, used to recreate all context
 *                              and speed up access to container tracepoints.
 *
 * Additional entries are present only if trace is diff:
 * - [PACKED_DIFF_MARKER_ITEM_NAME] (now ".diff"), optional — empty entry which marks this trace as proper diff.
 * - [PACKED_LEFT_META_ITEM_NAME] (now "info.left.txt"), optional — if "left" ("old") diff source had meta info,
 *                                 it is copied here (see [TraceMetaInfo.leftTraceMetaInfo]).
 * - [PACKED_RIGHT_META_ITEM_NAME] (now "info.right.txt"), optional — if "right" ("new") diff source had meta info,
 *                                 it is copied here (see [TraceMetaInfo.rightTraceMetaInfo]).
 * - [PACKED_THREAD_MAP_ITEM_NAME] (now "diff.threadmap"), optional — mapping without diff thread ids and left/right
 *                                 thread ids, as diff remaps thread ids in the process to ensure uniqueness.
 * - [PACKED_ID_MAP_ITEM_NAME] (now "diff.idmap"), optional — mapping between diff event ids and left/right event ids.
 *                             This map allows to bind any events in diff with their sources.
 *
 * File reader is very liberal for diffs: it can read diff without any optional information, but will warn about lost
 * entries.
 *
 * On the other hand, any unknown entries in archive is not allowed and cause error.
 *
 */

/**
 * Used by plugin to open files
 */
fun isPackedTrace(traceFileName: String): Boolean {
    return try {
        val zip = ZipFile(traceFileName)
        var hasMeta = false
        var hasData = false
        var hasIndex = false
        for (entry in zip.entries()) {
            if (!entry.isDirectory && entry.name == PACKED_DATA_ITEM_NAME) hasData = true
            if (!entry.isDirectory && entry.name == PACKED_META_ITEM_NAME) hasMeta = true
            if (!entry.isDirectory && entry.name == PACKED_INDEX_ITEM_NAME) hasIndex = true
            if (hasMeta && hasData && hasIndex) return true
        }
        false
    } catch (_: Throwable) {
        false
    }
}

/**
 * This class try to detect is provided path points to packed (ZIP) trace or
 * raw data file.
 *
 * If provided file is packed trace, it unpacks data file as it needs to be seekable
 * and packed file is not seekable, unfortunately.
 *
 * In any case it provides these data elements:
 *
 *  - [dataFileName] — name of file with main trace data. It is [traceFileName] if trace in question
 *    is not packed and name of temporary file with data unpacked to temporary file which will be deleted
 *    after trace is closed.
 *  - [indexStream] — stream with index, if it exists. It can be separate file in case of unpacked trace
 *    ot ZIP stream prepared for reading index entry. Index is only read sequential, so reading directly
 *    from ZIP is Ok.
 *  - [metaInfo] — Trace meta information, if it exists for provided trace.
 *  - [threadIdMap] — Map from diff's thread id to left (first) and right (second) thread ids, `-1` if thread
 *    doesn't present in corresponding trace. `null` if trace is not diff.
 *  - [eventIdMap] — Map from diff's event id to left (first) and right (second) event ids, `-1` if this event
 *    doesn't present in corresponding trace. `null` if trace is not diff.
 */
internal class TraceDataProvider(val traceFileName: String) : AutoCloseable {
    private class IdStreamList(
        private val data: SeekableDataInput,
        fileSize: Long
    ) : AbstractList<Pair<Int, Int>>() {
        override val size: Int = (fileSize / (Int.SIZE_BYTES * 2)).toInt()

        override fun get(index: Int): Pair<Int, Int> {
            if (index !in 0..<size) return -1 to -1
            data.seek(index * Int.SIZE_BYTES * 2L)
            val l = data.readInt()
            val r = data.readInt()
            return l to r
        }
    }

    private var tmpDataFile: File? = null
    private var tmpIdMapFile: File? = null
    private var zip: ZipFile? = null
    private var idMapDataInput: SeekableDataInput? = null

    var indexStream: DataInputStream? = null
        private set

    var metaInfo: TraceMetaInfo? = null
        private set

    var threadIdMap: Map<Int, Pair<Int, Int>>? = null
        private set

    var eventIdMap: List<Pair<Int, Int>>? = null
        private set

    private fun tryReadZIP(): Boolean {
        // Don't propagate zip error on open, simply return false
        val zip = try {
            ZipFile(traceFileName)
        } catch (e: ZipException) {
            return false
        }

        var isDiff: Boolean = false
        var leftMetaInfo: TraceMetaInfo? = null
        var rightMetaInfo: TraceMetaInfo? = null
        var dataInputStream: InputStream? = null
        var metaInfoStream: InputStream? = null
        var idMapStream: InputStream? = null
        try {
            for (entry in zip.entries()) {
                if (entry.isDirectory) {
                    throwZipError(traceFileName, "entry ${entry.name} is directory")
                }
                when (entry.name) {
                    PACKED_DATA_ITEM_NAME -> {
                        dataInputStream = zip.getInputStream(entry)
                    }
                    PACKED_INDEX_ITEM_NAME -> {
                        indexStream = wrapStream(zip.getInputStream(entry))
                    }
                    PACKED_META_ITEM_NAME -> {
                        metaInfoStream = zip.getInputStream(entry)
                    }
                    PACKED_DIFF_MARKER_ITEM_NAME -> {
                        isDiff = true
                    }
                    PACKED_ID_MAP_ITEM_NAME -> {
                        idMapStream = zip.getInputStream(entry)
                    }
                    PACKED_THREAD_MAP_ITEM_NAME -> {
                        // Load error is not a fatal error
                        try {
                            val m = mutableMapOf<Int, Pair<Int, Int>>()
                            DataInputStream(zip.getInputStream(entry)).use { input ->
                                val size = input.readInt()
                                repeat(size) {
                                    m[input.readInt()] = input.readInt() to input.readInt()
                                }
                            }
                            threadIdMap = m
                        } catch (e: Throwable) {
                            Logger.warn { "Packed trace \"$traceFileName\" has broken thread map: ${e.message}" }
                        }
                    }
                    PACKED_LEFT_META_ITEM_NAME,
                    PACKED_RIGHT_META_ITEM_NAME -> {
                        // Load error is not a fatal error
                        try {
                            val meta = TraceMetaInfo.read(input = zip.getInputStream(entry))
                            if (entry.name == PACKED_LEFT_META_ITEM_NAME) {
                                leftMetaInfo = meta
                            } else {
                                rightMetaInfo = meta
                            }
                        } catch (e: Throwable) {
                            Logger.warn { "Packed trace \"$traceFileName\" has broken left meta info: ${e.message}" }
                        }
                    }
                    else -> throwZipError(traceFileName, "unknown entry name ${entry.name}")
                }
            }
            // Check fo minimal needed information, it is hard error
            if (metaInfoStream == null) throwZipError(traceFileName, "file doesn't contain meta info")
            if (dataInputStream == null) throwZipError(traceFileName, "file doesn't contain trace data")
            if (indexStream == null) throwZipError(traceFileName, "file doesn't contain trace index")

            this.zip = zip
        } catch (e: Throwable) {
            zip.close()
            throw e
        }

        // Process all data, unpack data and id map if needed
        if (!isDiff) {
            if (threadIdMap != null) Logger.warn { "Packed trace \"$traceFileName\" has thread id map but no diff marker" }
            if (idMapStream != null) Logger.warn { "Packed trace \"$traceFileName\" has diff id map but no diff marker" }
            if (leftMetaInfo != null) Logger.warn { "Packed trace \"$traceFileName\" has left trace meta info but no diff marker" }
            if (rightMetaInfo != null) Logger.warn { "Packed trace \"$traceFileName\" has right trace meta info but no diff marker" }
        }

        val maybeDiff =
                       threadIdMap != null
                    || idMapStream != null
                    || leftMetaInfo != null
                    || rightMetaInfo != null

        val totalDiff =
                       threadIdMap != null
                    && idMapStream != null
                    && leftMetaInfo != null
                    && rightMetaInfo != null

        if (!isDiff && totalDiff) {
            Logger.warn { "Packed trace \"$traceFileName\" has all data of diff but no diff marker, mark as diff" }
            isDiff = true
        } else if (!isDiff && maybeDiff) {
            Logger.warn { "Packed trace \"$traceFileName\" has some data of diff but no diff marker, mark as diff" }
            isDiff = true
        }

        if (idMapStream != null) {
            val rv = copyOutToTempFile("trace-unpacked-", ".$ID_MAP_FILENAME_EXT", idMapStream)
            if (rv.isFailure) {
                Logger.warn { "Packed trace \"$traceFileName\" has id map but it cannot be unpacked: ${rv.exceptionOrNull()?.message ?: "<unknown error>"}" }
            } else {
                tmpIdMapFile = rv.getOrNull()

                val channel = Files.newByteChannel(Path(tmpIdMapFile!!.absolutePath), StandardOpenOption.READ)

                idMapDataInput = SeekableDataInput(SeekableChannelBufferedInputStream(channel))
                eventIdMap = IdStreamList(idMapDataInput!!, channel.size())
            }
            idMapStream.close()
        }

        metaInfo = TraceMetaInfo.read(metaInfoStream, isDiff, leftMetaInfo, rightMetaInfo)
        tmpDataFile = copyOutToTempFile("trace-unpacked-", ".$DATA_FILENAME_EXT", dataInputStream).getOrThrow()

        return true
    }

    init {
        // It is not ZIP, it is raw trace file (we hope)
        if (!tryReadZIP()) {
            indexStream = wrapStream(openExistingFile("$traceFileName.$INDEX_FILENAME_EXT"))
        }
    }

    val dataFileName: String = tmpDataFile?.absolutePath ?: traceFileName

    override fun close() {
        idMapDataInput?.close()
        tmpDataFile?.delete()
        tmpIdMapFile?.delete()
        indexStream?.close()
        zip?.close()
    }
}

fun packRecordedTrace(
    dataFileName: String,
    indexFileName: String,
    outputFileName: String,
    metaInfo: TraceMetaInfo,
    deleteSources: Boolean = true
) {
    try {
        ZipOutputStream(openNewFile(outputFileName).buffered(OUTPUT_BUFFER_SIZE)).use { zip ->
            // Don't compress for now, but STORED requires pre-compute CRC32, and we don't want to do it.
            zip.setMethod(ZipOutputStream.DEFLATED)
            zip.setLevel(0)

            // Save main meta
            saveMeta(zip, PACKED_META_ITEM_NAME, metaInfo)
            // Store data
            copyFileIntoZip(zip, PACKED_DATA_ITEM_NAME, dataFileName, "data")
            // Store Index
            copyFileIntoZip(zip, PACKED_INDEX_ITEM_NAME, indexFileName, "index")
        }
        if (deleteSources) {
            Files.deleteIfExists(Path(dataFileName))
            Files.deleteIfExists(Path(indexFileName))
        }
        Logger.info { "Packed trace size: ${Files.size(Path(outputFileName))} bytes" }
    } catch (e: Throwable) {
        Files.deleteIfExists(Path(outputFileName))
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

private fun copyOutToTempFile(prefix: String, suffix: String, input: InputStream): Result<File> {
    val tmp = File.createTempFile(prefix, suffix)
    tmp.deleteOnExit()
    try {
        val dataOutput = openNewFile(tmp.absolutePath)
        dataOutput.use {
            input.copyTo(it)
        }
        return Result.success(tmp)
    } catch (e: IOException) {
        return Result.failure(e)
    }
}

private fun saveMeta(zip: ZipOutputStream, zipEntryName: String, metaInfo: TraceMetaInfo?) {
    if (metaInfo == null) return
    zip.putNextEntry(ZipEntry(zipEntryName))
    val printer = PrintWriter(zip)
    metaInfo.print(printer)
    printer.flush() // Don't close, it will close ZIP stream too!
    zip.closeEntry()
}

private fun wrapStream(input: InputStream?): DataInputStream? {
    if (input == null) return null
    return DataInputStream(input.buffered(INPUT_BUFFER_SIZE))
}

@Suppress("NOTHING_TO_INLINE")
private inline fun throwZipError(fileName: String, details: String? = null, cause: Throwable? = null): Nothing {
    val d = if (details != null) " ($details)" else ""
    throw IllegalArgumentException("File \"$fileName\" is a ZIP archive but is not a packed trace$d", cause)
}
