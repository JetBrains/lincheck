/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.trace.debugger.io

import org.jetbrains.lincheck.datastructures.Operation
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

// FileInputStream Tests

class FileInputStreamReadNBytesTest : FileSpecialCasesTest() {
    private val file = File("test-input-stream-read-n-bytes.txt")

    @Operation
    fun operation(): String {
        // Create and write to file
        val content = "Hello, World!"
        file.writeText(content)

        try {
            // Read N bytes into byte array
            val inputStream = FileInputStream(file)
            inputStream.use { stream ->
                val bytes = ByteArray(content.length)
                val n = 5 // Read only first 5 bytes
                val bytesRead = stream.readNBytes(bytes, 0, n)
                return String(bytes, 0, bytesRead)
            }
        } finally {
            // Clean up
            file.delete()
        }
    }
}

class FileInputStreamTransferToFileOutputStreamTest : FileSpecialCasesTest() {
    private val sourceFile = File("test-input-stream-transfer-source.txt")
    private val targetFile = File("test-input-stream-transfer-target.txt")

    @Operation
    fun operation(): Long {
        // Create and write to source file
        val content = "Hello, World!"
        sourceFile.writeText(content)

        try {
            // Transfer content to target file
            val inputStream = FileInputStream(sourceFile)
            val outputStream = FileOutputStream(targetFile)

            val transferred = inputStream.use { input ->
                outputStream.use { output ->
                    input.transferTo(output)
                }
            }

            // Verify content
            val targetContent = targetFile.readText()
            require(targetContent == content) { "Content mismatch: expected '$content', got '$targetContent'" }

            return transferred
        } finally {
            // Clean up
            sourceFile.delete()
            targetFile.delete()
        }
    }
}

class FileInputStreamTransferToByteArrayOutputStreamTest : FileSpecialCasesTest() {
    private val sourceFile = File("test-input-stream-transfer-to-bytearray.txt")

    @Operation
    fun operation(): Long {
        // Create and write to source file
        val content = "Hello, World!"
        sourceFile.writeText(content)

        try {
            // Transfer content to ByteArrayOutputStream
            val inputStream = FileInputStream(sourceFile)
            val outputStream = ByteArrayOutputStream()

            val transferred = inputStream.use { input ->
                outputStream.use { output ->
                    input.transferTo(output)
                }
            }

            // Verify content
            val targetContent = String(outputStream.toByteArray())
            require(targetContent == content) { "Content mismatch: expected '$content', got '$targetContent'" }

            return transferred
        } finally {
            // Clean up
            sourceFile.delete()
        }
    }
}