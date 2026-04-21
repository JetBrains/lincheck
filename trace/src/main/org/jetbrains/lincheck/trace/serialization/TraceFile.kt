package org.jetbrains.lincheck.trace.serialization

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * This function is used to create a pair of files for data and index in once.
 *
 * It uses [openNewFile] to open files and add [OUTPUT_BUFFER_SIZE] buffering
 * around "naked" stream.
 *
 * Data stream is created with passed name (without any additional extension),
 * and index file is named by adding [INDEX_FILENAME_EXT] extension to base name.
 */
internal fun openNewStandardDataAndIndex(baseFileName: String): TraceOutputStreams {
    val dataStream = openNewFile(baseFileName).buffered(OUTPUT_BUFFER_SIZE)
    val indexStream = openNewFile("$baseFileName.$INDEX_FILENAME_EXT").buffered(OUTPUT_BUFFER_SIZE)
    return TraceOutputStreams(dataStream, indexStream)
}

internal fun openNewFile(name: String): OutputStream {
    val f = File(name)
    f.parentFile?.mkdirs()
    f.createNewFile()
    return f.outputStream()
}

internal fun openExistingFile(name: String?): InputStream? {
    val f = File(name)
    if (!f.exists()) return null
    return f.inputStream()
}

data class TraceOutputStreams(
    val dataStream: OutputStream,
    val indexStream: OutputStream,
)

// This suffix is not enforced, but IDEA plugin rely on it
const val DATA_FILENAME_EXT = "trace"

// These are enforced
const val INDEX_FILENAME_EXT = "idx"
const val PACK_FILENAME_EXT = "packedtrace"
internal const val ID_MAP_FILENAME_EXT = "idmap"
internal const val THREAD_MAP_FILENAME_EXT = "threadmap"