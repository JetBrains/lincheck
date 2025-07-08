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

import org.jetbrains.kotlinx.lincheck_test.trace.debugger.AbstractDeterministicTest
import org.jetbrains.lincheck.datastructures.Operation
import java.io.*

abstract class StreamOperationTest : AbstractDeterministicTest()

// FileInputStream Tests

class FileInputStreamReadByteTest : StreamOperationTest() {
    private val file = File("test-input-stream-byte.txt")

    @Operation
    fun operation(): Int {
        // Create and write to file
        file.writeText("A")

        try {
            // Read a single byte
            val inputStream = FileInputStream(file)
            inputStream.use { stream ->
                return stream.read()
            }
        } finally {
            // Clean up
            file.delete()
        }
    }
}

class FileInputStreamReadByteArrayTest : StreamOperationTest() {
    private val file = File("test-input-stream-byte-array.txt")

    @Operation
    fun operation(): String {
        // Create and write to file
        val content = "Hello, World!"
        file.writeText(content)

        try {
            // Read into byte array
            val inputStream = FileInputStream(file)
            inputStream.use { stream ->
                val bytes = ByteArray(content.length)
                val bytesRead = stream.read(bytes)
                return String(bytes, 0, bytesRead)
            }
        } finally {
            // Clean up
            file.delete()
        }
    }
}

class FileInputStreamReadByteArrayWithOffsetTest : StreamOperationTest() {
    private val file = File("test-input-stream-byte-array-offset.txt")

    @Operation
    fun operation(): String {
        // Create and write to file
        val content = "Hello, World!"
        file.writeText(content)

        try {
            // Read into byte array with offset
            val inputStream = FileInputStream(file)
            inputStream.use { stream ->
                val bytes = ByteArray(content.length + 5) // Extra space
                val offset = 2
                val length = content.length
                val bytesRead = stream.read(bytes, offset, length)
                return String(bytes, offset, bytesRead)
            }
        } finally {
            // Clean up
            file.delete()
        }
    }
}

class FileInputStreamSkipTest : StreamOperationTest() {
    private val file = File("test-input-stream-skip.txt")

    @Operation
    fun operation(): String {
        // Create and write to file
        val content = "Hello, World!"
        file.writeText(content)

        try {
            // Skip some bytes and read the rest
            val inputStream = FileInputStream(file)
            inputStream.use { stream ->
                val skipBytes = 7L // Skip "Hello, "
                stream.skip(skipBytes)
                val bytes = ByteArray(content.length - skipBytes.toInt())
                val bytesRead = stream.read(bytes)
                return String(bytes, 0, bytesRead)
            }
        } finally {
            // Clean up
            file.delete()
        }
    }
}

// FileOutputStream Tests

class FileOutputStreamWriteByteTest : StreamOperationTest() {
    private val file = File("test-output-stream-byte.txt")

    @Operation
    fun operation(): Int {
        try {
            // Write a single byte
            val outputStream = FileOutputStream(file)
            outputStream.use { stream ->
                val byteValue = 65 // ASCII 'A'
                stream.write(byteValue)
                return byteValue
            }
        } finally {
            // Verify and clean up
            val content = file.readText()
            require(content == "A") { "Expected 'A', got '$content'" }
            file.delete()
        }
    }
}

class FileOutputStreamWriteByteArrayTest : StreamOperationTest() {
    private val file = File("test-output-stream-byte-array.txt")

    @Operation
    fun operation(): String {
        val content = "Hello, World!"
        val bytes = content.toByteArray()

        try {
            // Write byte array
            val outputStream = FileOutputStream(file)
            outputStream.use { stream ->
                stream.write(bytes)
                return content
            }
        } finally {
            // Verify and clean up
            val readContent = file.readText()
            require(readContent == content) { "Expected '$content', got '$readContent'" }
            file.delete()
        }
    }
}

class FileOutputStreamWriteByteArrayWithOffsetTest : StreamOperationTest() {
    private val file = File("test-output-stream-byte-array-offset.txt")

    @Operation
    fun operation(): String {
        val fullContent = "Hello, World!"
        val bytes = fullContent.toByteArray()
        val offset = 7 // Start from "World!"
        val length = 6 // "World!"

        try {
            // Write byte array with offset
            val outputStream = FileOutputStream(file)
            outputStream.use { stream ->
                stream.write(bytes, offset, length)
                return fullContent.substring(offset, offset + length)
            }
        } finally {
            // Verify and clean up
            val readContent = file.readText()
            val expectedContent = fullContent.substring(offset, offset + length)
            require(readContent == expectedContent) { "Expected '$expectedContent', got '$readContent'" }
            file.delete()
        }
    }
}

class FileOutputStreamAppendTest : StreamOperationTest() {
    private val file = File("test-output-stream-append.txt")

    @Operation
    fun operation(): String {
        val firstContent = "Hello, "
        val secondContent = "World!"
        val fullContent = firstContent + secondContent

        try {
            // Write first part
            FileOutputStream(file).use { stream ->
                stream.write(firstContent.toByteArray())
            }

            // Append second part
            FileOutputStream(file, true).use { stream ->
                stream.write(secondContent.toByteArray())
            }

            return fullContent
        } finally {
            // Verify and clean up
            val readContent = file.readText()
            require(readContent == fullContent) { "Expected '$fullContent', got '$readContent'" }
            file.delete()
        }
    }
}

// FileReader Tests

class FileReaderReadCharTest : StreamOperationTest() {
    private val file = File("test-reader-char.txt")

    @Operation
    fun operation(): Char {
        // Create and write to file
        file.writeText("A")

        try {
            // Read a single char
            val reader = FileReader(file)
            reader.use { r ->
                val charValue = r.read().toChar()
                return charValue
            }
        } finally {
            // Clean up
            file.delete()
        }
    }
}

class FileReaderReadCharArrayTest : StreamOperationTest() {
    private val file = File("test-reader-char-array.txt")

    @Operation
    fun operation(): String {
        // Create and write to file
        val content = "Hello, World!"
        file.writeText(content)

        try {
            // Read into char array
            val reader = FileReader(file)
            reader.use { r ->
                val chars = CharArray(content.length)
                val charsRead = r.read(chars)
                return String(chars, 0, charsRead)
            }
        } finally {
            // Clean up
            file.delete()
        }
    }
}

class FileReaderReadCharArrayWithOffsetTest : StreamOperationTest() {
    private val file = File("test-reader-char-array-offset.txt")

    @Operation
    fun operation(): String {
        // Create and write to file
        val content = "Hello, World!"
        file.writeText(content)

        try {
            // Read into char array with offset
            val reader = FileReader(file)
            reader.use { r ->
                val chars = CharArray(content.length + 5) // Extra space
                val offset = 2
                val length = content.length
                val charsRead = r.read(chars, offset, length)
                return String(chars, offset, charsRead)
            }
        } finally {
            // Clean up
            file.delete()
        }
    }
}

class FileReaderSkipTest : StreamOperationTest() {
    private val file = File("test-reader-skip.txt")

    @Operation
    fun operation(): String {
        // Create and write to file
        val content = "Hello, World!"
        file.writeText(content)

        try {
            // Skip some chars and read the rest
            val reader = FileReader(file)
            reader.use { r ->
                val skipChars = 7L // Skip "Hello, "
                r.skip(skipChars)
                val chars = CharArray(content.length - skipChars.toInt())
                val charsRead = r.read(chars)
                return String(chars, 0, charsRead)
            }
        } finally {
            // Clean up
            file.delete()
        }
    }
}

// FileWriter Tests

class FileWriterWriteCharTest : StreamOperationTest() {
    private val file = File("test-writer-char.txt")

    @Operation
    fun operation(): Char {
        try {
            // Write a single char
            val writer = FileWriter(file)
            writer.use { w ->
                val charValue = 'A'
                w.write(charValue.code)
                return charValue
            }
        } finally {
            // Verify and clean up
            val content = file.readText()
            require(content == "A") { "Expected 'A', got '$content'" }
            file.delete()
        }
    }
}

class FileWriterWriteStringTest : StreamOperationTest() {
    private val file = File("test-writer-string.txt")

    @Operation
    fun operation(): String {
        val content = "Hello, World!"

        try {
            // Write string
            val writer = FileWriter(file)
            writer.use { w ->
                w.write(content)
                return content
            }
        } finally {
            // Verify and clean up
            val readContent = file.readText()
            require(readContent == content) { "Expected '$content', got '$readContent'" }
            file.delete()
        }
    }
}

class FileWriterWriteCharArrayTest : StreamOperationTest() {
    private val file = File("test-writer-char-array.txt")

    @Operation
    fun operation(): String {
        val content = "Hello, World!"
        val chars = content.toCharArray()

        try {
            // Write char array
            val writer = FileWriter(file)
            writer.use { w ->
                w.write(chars)
                return content
            }
        } finally {
            // Verify and clean up
            val readContent = file.readText()
            require(readContent == content) { "Expected '$content', got '$readContent'" }
            file.delete()
        }
    }
}

class FileWriterWriteCharArrayWithOffsetTest : StreamOperationTest() {
    private val file = File("test-writer-char-array-offset.txt")

    @Operation
    fun operation(): String {
        val fullContent = "Hello, World!"
        val chars = fullContent.toCharArray()
        val offset = 7 // Start from "World!"
        val length = 6 // "World!"

        try {
            // Write char array with offset
            val writer = FileWriter(file)
            writer.use { w ->
                w.write(chars, offset, length)
                return fullContent.substring(offset, offset + length)
            }
        } finally {
            // Verify and clean up
            val readContent = file.readText()
            val expectedContent = fullContent.substring(offset, offset + length)
            require(readContent == expectedContent) { "Expected '$expectedContent', got '$readContent'" }
            file.delete()
        }
    }
}

class FileWriterAppendTest : StreamOperationTest() {
    private val file = File("test-writer-append.txt")

    @Operation
    fun operation(): String {
        val firstContent = "Hello, "
        val secondContent = "World!"
        val fullContent = firstContent + secondContent

        try {
            // Write first part
            FileWriter(file).use { w ->
                w.write(firstContent)
            }

            // Append second part
            FileWriter(file, true).use { w ->
                w.write(secondContent)
            }

            return fullContent
        } finally {
            // Verify and clean up
            val readContent = file.readText()
            require(readContent == fullContent) { "Expected '$fullContent', got '$readContent'" }
            file.delete()
        }
    }
}
