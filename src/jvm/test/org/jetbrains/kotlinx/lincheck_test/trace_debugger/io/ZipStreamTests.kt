/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.trace_debugger.io

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck_test.trace_debugger.AbstractDeterministicTest
import java.io.*
import java.util.zip.*

abstract class ZipStreamOperationTest : AbstractDeterministicTest()

// ZipOutputStream Tests

class ZipOutputStreamBasicTest : ZipStreamOperationTest() {
    private val zipFile = File("test-zip-output.zip")
    
    // TODO: remove after loop detector bug is fixed (IJTD-153)
    override fun ModelCheckingOptions.customize(): ModelCheckingOptions =
        hangingDetectionThreshold(1000)

    @Operation
    fun operation(): String {
        try {
            // Create a ZIP file with a single entry
            val entryName = "entry.txt"
            val content = "Test content"

            ZipOutputStream(FileOutputStream(zipFile)).use { zipOut ->
                // Create a new ZIP entry
                val entry = ZipEntry(entryName)
                zipOut.putNextEntry(entry)

                // Write content to the entry
                zipOut.write(content.toByteArray())

                // Close the entry
                zipOut.closeEntry()
            }

            // Verify the ZIP file exists
            require(zipFile.exists()) { "ZIP file was not created: $zipFile" }

            return entryName
        } finally {
            // Clean up
            zipFile.delete()
        }
    }
}

class ZipOutputStreamWithCompressionTest : ZipStreamOperationTest() {
    private val zipFile = File("test-zip-compression.zip")

    // TODO: remove after loop detector bug is fixed (IJTD-153)
    override fun ModelCheckingOptions.customize(): ModelCheckingOptions =
        hangingDetectionThreshold(1000)
    
    @Operation
    fun operation(): Map<String, Int> {
        try {
            // Create a ZIP file with two compression levels
            val entries = listOf(
                "no_compression.txt" to Deflater.NO_COMPRESSION,
                "best_compression.txt" to Deflater.BEST_COMPRESSION
            )

            val content = "Test content"
            val compressionLevels = mutableMapOf<String, Int>()

            ZipOutputStream(FileOutputStream(zipFile)).use { zipOut ->
                for ((entryName, compressionLevel) in entries) {
                    // Set compression level
                    zipOut.setLevel(compressionLevel)
                    compressionLevels[entryName] = compressionLevel

                    // Create a new ZIP entry
                    val entry = ZipEntry(entryName)
                    zipOut.putNextEntry(entry)

                    // Write content to the entry
                    zipOut.write(content.toByteArray())

                    // Close the entry
                    zipOut.closeEntry()
                }
            }

            // Verify the ZIP file exists
            require(zipFile.exists()) { "ZIP file was not created: $zipFile" }

            return compressionLevels
        } finally {
            // Clean up
            zipFile.delete()
        }
    }
}

// ZipInputStream Tests

class ZipInputStreamTest : ZipStreamOperationTest() {
    private val zipFile = File("test-zip-input.zip")
    
    // TODO: remove after loop detector bug is fixed (IJTD-153)
    override fun ModelCheckingOptions.customize(): ModelCheckingOptions =
        hangingDetectionThreshold(1000)

    @Operation
    fun operation(): String {
        try {
            // Create a ZIP file with a single entry
            val entryName = "entry.txt"
            val content = "Test content"

            // Write to ZIP file
            ZipOutputStream(FileOutputStream(zipFile)).use { zipOut ->
                val entry = ZipEntry(entryName)
                zipOut.putNextEntry(entry)
                zipOut.write(content.toByteArray())
                zipOut.closeEntry()
            }

            // Read from ZIP file
            var readContent = ""
            ZipInputStream(FileInputStream(zipFile)).use { zipIn ->
                val entry = zipIn.nextEntry
                if (entry != null) {
                    readContent = zipIn.readBytes().toString(Charsets.UTF_8)
                    zipIn.closeEntry()
                }
            }

            // Verify content
            require(readContent == content) { 
                "Content mismatch: expected '$content', got '$readContent'" 
            }

            return readContent
        } finally {
            // Clean up
            zipFile.delete()
        }
    }
}

// Combined ZipInputStream and ZipOutputStream Test

class ZipStreamRoundTripTest : ZipStreamOperationTest() {
    private val zipFile = File("test-zip-round-trip.zip")

    // TODO: remove after loop detector bug is fixed (IJTD-153)
    override fun ModelCheckingOptions.customize(): ModelCheckingOptions =
        hangingDetectionThreshold(1000)

    @Operation
    fun operation(): Boolean {
        try {
            // Original data to zip
            val entryName = "test.txt"
            val content = "Test content"

            // Write to ZIP file
            ZipOutputStream(FileOutputStream(zipFile)).use { zipOut ->
                val entry = ZipEntry(entryName)
                zipOut.putNextEntry(entry)
                zipOut.write(content.toByteArray())
                zipOut.closeEntry()
            }

            // Read from ZIP file
            var readContent = ""
            ZipInputStream(FileInputStream(zipFile)).use { zipIn ->
                val entry = zipIn.nextEntry
                if (entry != null) {
                    readContent = zipIn.readBytes().toString(Charsets.UTF_8)
                    zipIn.closeEntry()
                }
            }

            // Verify content was read correctly
            require(readContent == content) { 
                "Content mismatch: expected '$content', got '$readContent'" 
            }

            return true
        } finally {
            // Clean up
            zipFile.delete()
        }
    }
}

// GZIPOutputStream and GZIPInputStream Tests

class GZIPOutputStreamTest : ZipStreamOperationTest() {
    private val gzipFile = File("test-gzip-output.gz")

    @Operation
    fun operation(): Int {
        try {
            // Content to compress
            val content = "Test content"
            val contentBytes = content.toByteArray()

            // Write to GZIP file
            GZIPOutputStream(FileOutputStream(gzipFile)).use { gzipOut ->
                gzipOut.write(contentBytes)
            }

            // Verify the GZIP file exists
            require(gzipFile.exists()) { "GZIP file was not created: $gzipFile" }

            return contentBytes.size
        } finally {
            // Clean up
            gzipFile.delete()
        }
    }
}

class GZIPInputStreamTest : ZipStreamOperationTest() {
    private val gzipFile = File("test-gzip-input.gz")

    @Operation
    fun operation(): String {
        try {
            // Content to compress
            val content = "Test content"

            // Write to GZIP file
            GZIPOutputStream(FileOutputStream(gzipFile)).use { gzipOut ->
                gzipOut.write(content.toByteArray())
            }

            // Read from GZIP file
            val decompressedContent = GZIPInputStream(FileInputStream(gzipFile)).use { gzipIn ->
                gzipIn.readBytes().toString(Charsets.UTF_8)
            }

            // Verify content
            require(decompressedContent == content) { 
                "Content mismatch: expected '$content', got '$decompressedContent'" 
            }

            return decompressedContent
        } finally {
            // Clean up
            gzipFile.delete()
        }
    }
}

class GZIPStreamRoundTripTest : ZipStreamOperationTest() {
    private val gzipFile = File("test-gzip-round-trip.gz")

    @Operation
    fun operation(): Pair<Int, Int> {
        try {
            // Generate a small content
            val contentBuilder = StringBuilder()
            repeat(10) {
                contentBuilder.append("Line $it\n")
            }
            val content = contentBuilder.toString()
            val contentBytes = content.toByteArray()

            // Write to GZIP file
            GZIPOutputStream(FileOutputStream(gzipFile)).use { gzipOut ->
                gzipOut.write(contentBytes)
            }

            // Get compressed size
            val compressedSize = gzipFile.length().toInt()

            // Read from GZIP file
            val decompressedContent = GZIPInputStream(FileInputStream(gzipFile)).use { gzipIn ->
                gzipIn.readBytes().toString(Charsets.UTF_8)
            }

            // Verify content
            require(decompressedContent == content) { 
                "Content mismatch after compression/decompression" 
            }

            // Return original and compressed sizes
            return Pair(contentBytes.size, compressedSize)
        } finally {
            // Clean up
            gzipFile.delete()
        }
    }
}
