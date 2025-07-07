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

import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.kotlinx.lincheck_test.trace_debugger.AbstractDeterministicTest
import org.junit.Ignore
import java.io.File
import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.Path

abstract class FileOperationTest : AbstractDeterministicTest()

class FilesCreateTempDirectoryTest : FileOperationTest() {
    @Operation
    fun operation(): List<String> = List(10) {
        val tempDir = Files.createTempDirectory("test-prefix")
        require(Files.exists(tempDir)) { "Directory was not created: $tempDir" }
        tempDir.toString()
    }
}

class FilesCreateTempFileTest : FileOperationTest() {
    @Operation
    fun operation(): List<String> = List(10) {
        val tempFile = Files.createTempFile("test-prefix", ".txt")
        require(Files.exists(tempFile)) { "File was not created: $tempFile" }
        tempFile.toString()
    }
}

class FilesCreateDirectoryTest : FileOperationTest() {
    private val dir = Path("test-prefix")

    @Operation
    fun operation(): String {
        require(!Files.exists(dir)) { "Directory does not exist: $dir" }
        val tempDir = Files.createDirectory(dir)
        require(Files.exists(tempDir)) { "Directory was not created: $tempDir" }
        Files.delete(dir)
        return tempDir.toString()
    }
}

class FilesCreateFileTest : FileOperationTest() {
    private val fileName = Path("test-prefix.txt")

    @Operation
    fun operation(): String {
        require(!Files.exists(fileName)) { "File does not exist: $fileName" }
        val file = Files.createFile(fileName)
        require(Files.exists(file)) { "File was not created: $file" }
        Files.delete(fileName)
        return file.toString()
    }
}

// Java IO API tests

class FileCreateTempFileTest : FileOperationTest() {
    @Operation
    fun operation(): List<String> = List(10) {
        val tempFile = File.createTempFile("test-prefix", ".txt")
        require(tempFile.exists()) { "File was not created: $tempFile" }
        tempFile.toString()
    }
}

class FileCreateTempDirectoryTest : FileOperationTest() {
    @Operation
    fun operation(): List<String> = List(10) {
        // Java IO doesn't have a direct method for creating temp directories,
        // so we create a temp file first and then replace it with a directory
        val tempFile = File.createTempFile("test-prefix", "")
        tempFile.delete() // Delete the file
        val tempDir = File(tempFile.absolutePath)
        require(tempDir.mkdir()) { "Failed to create directory: $tempDir" }
        require(tempDir.exists()) { "Directory was not created: $tempDir" }
        tempDir.toString()
    }
}

class FileCreateFileTest : FileOperationTest() {
    private val file = File("test-prefix-io.txt")

    @Operation
    fun operation(): String {
        require(!file.exists()) { "File already exists: $file" }
        require(file.createNewFile()) { "Failed to create file: $file" }
        require(file.exists()) { "File does not exist: $file" }
        require(file.delete()) { "Failed to delete file: $file" }
        require(!file.exists()) { "File still exists: $file" }
        return file.toString()
    }
}

class FileCreateDirectoryTest : FileOperationTest() {
    private val dir = File("test-prefix-io-dir")

    @Operation
    fun operation(): String {
        require(!dir.exists()) { "Directory already exists: $dir" }
        require(dir.mkdir()) { "Failed to create directory: $dir" }
        require(dir.exists()) { "Directory was not created: $dir" }
        require(dir.delete()) { "Failed to delete directory: $dir" }
        require(!dir.exists()) { "Directory still exists: $dir" }
        return dir.toString()
    }
}

// File Properties Tests - NIO API

class FilesPropertiesTest : FileOperationTest() {
    private val fileName = Path("test-properties.txt")

    @Operation
    fun operation(): Map<String, Any> {
        // Create a file
        val file = Files.createFile(fileName)
        require(Files.exists(file)) { "File was not created: $file" }

        try {
            // Get and verify file properties
            val attrs = Files.readAttributes(file, BasicFileAttributes::class.java)
            val properties = mutableMapOf<String, Any>()

            properties["isRegularFile"] = attrs.isRegularFile
            properties["isDirectory"] = attrs.isDirectory
            properties["size"] = attrs.size()
            properties["creationTime"] = attrs.creationTime().toString()
            properties["lastModifiedTime"] = attrs.lastModifiedTime().toString()

            return properties
        } finally {
            // Clean up
            Files.delete(fileName)
        }
    }
}

class FilesDirectoryPropertiesTest : FileOperationTest() {
    private val dirName = Path("test-dir-properties")

    @Operation
    fun operation(): Map<String, Any> {
        // Create a directory
        val dir = Files.createDirectory(dirName)
        require(Files.exists(dir)) { "Directory was not created: $dir" }

        try {
            // Get and verify directory properties
            val attrs = Files.readAttributes(dir, BasicFileAttributes::class.java)
            val properties = mutableMapOf<String, Any>()

            properties["isRegularFile"] = attrs.isRegularFile
            properties["isDirectory"] = attrs.isDirectory
            properties["size"] = attrs.size()
            properties["creationTime"] = attrs.creationTime().toString()
            properties["lastModifiedTime"] = attrs.lastModifiedTime().toString()

            return properties
        } finally {
            // Clean up
            Files.delete(dirName)
        }
    }
}

// File Properties Tests - IO API

class FilePropertiesTest : FileOperationTest() {
    private val file = File("test-properties-io.txt")

    @Operation
    fun operation(): Map<String, Any> {
        // Create a file
        require(file.createNewFile()) { "Failed to create file: $file" }
        require(file.exists()) { "File was not created: $file" }

        try {
            // Get and verify file properties
            val properties = mutableMapOf<String, Any>()

            properties["isFile"] = file.isFile
            properties["isDirectory"] = file.isDirectory
            properties["length"] = file.length()
            properties["lastModified"] = file.lastModified()
            properties["canRead"] = file.canRead()
            properties["canWrite"] = file.canWrite()
            properties["absolutePath"] = file.absolutePath

            return properties
        } finally {
            // Clean up
            require(file.delete()) { "Failed to delete file: $file" }
        }
    }
}

class FileDirectoryPropertiesTest : FileOperationTest() {
    private val dir = File("test-dir-properties-io")

    @Operation
    fun operation(): Map<String, Any> {
        // Create a directory
        require(dir.mkdir()) { "Failed to create directory: $dir" }
        require(dir.exists()) { "Directory was not created: $dir" }

        try {
            // Get and verify directory properties
            val properties = mutableMapOf<String, Any>()

            properties["isFile"] = dir.isFile
            properties["isDirectory"] = dir.isDirectory
            properties["length"] = dir.length()
            properties["lastModified"] = dir.lastModified()
            properties["canRead"] = dir.canRead()
            properties["canWrite"] = dir.canWrite()
            properties["absolutePath"] = dir.absolutePath

            return properties
        } finally {
            // Clean up
            require(dir.delete()) { "Failed to delete directory: $dir" }
        }
    }
}

// File Deletion Tests - NIO API

class FilesDeleteTest : FileOperationTest() {
    private val fileName = Path("test-delete.txt")

    @Operation
    fun operation(): Boolean {
        // Create a file
        val file = Files.createFile(fileName)
        require(Files.exists(file)) { "File was not created: $file" }

        // Delete the file
        Files.delete(file)

        // Verify deletion
        return !Files.exists(file)
    }
}

class FilesDeleteDirectoryTest : FileOperationTest() {
    private val dirName = Path("test-delete-dir")

    @Operation
    fun operation(): Boolean {
        // Create a directory
        val dir = Files.createDirectory(dirName)
        require(Files.exists(dir)) { "Directory was not created: $dir" }

        // Delete the directory
        Files.delete(dir)

        // Verify deletion
        return !Files.exists(dir)
    }
}

// File Deletion Tests - IO API

class FileDeleteTest : FileOperationTest() {
    private val file = File("test-delete-io.txt")

    @Operation
    fun operation(): Boolean {
        // Create a file
        require(file.createNewFile()) { "Failed to create file: $file" }
        require(file.exists()) { "File was not created: $file" }

        // Delete the file
        val deleteResult = file.delete()

        // Verify deletion
        return deleteResult && !file.exists()
    }
}

class FileDeleteDirectoryTest : FileOperationTest() {
    private val dir = File("test-delete-io-dir")

    @Operation
    fun operation(): Boolean {
        // Create a directory
        require(dir.mkdir()) { "Failed to create directory: $dir" }
        require(dir.exists()) { "Directory was not created: $dir" }

        // Delete the directory
        val deleteResult = dir.delete()

        // Verify deletion
        return deleteResult && !dir.exists()
    }
}

// File Editing Tests - NIO API

@Ignore("java.nio.channels are not supported yet")
class FilesWriteReadTest : FileOperationTest() {
    private val fileName = Path("test-write-read.txt")

    @Operation
    fun operation(): String {
        // Create a file
        val file = Files.createFile(fileName)
        require(Files.exists(file)) { "File was not created: $file" }

        try {
            // Write to file
            val content = "Hello, NIO World!"
            Files.write(file, content.toByteArray(), StandardOpenOption.WRITE)

            // Read from file
            val readContent = String(Files.readAllBytes(file))

            // Verify content
            require(readContent == content) { "Content mismatch: expected '$content', got '$readContent'" }

            return readContent
        } finally {
            // Clean up
            Files.delete(fileName)
        }
    }
}

@Ignore("java.nio.channels are not supported yet")
class FilesAppendTest : FileOperationTest() {
    private val fileName = Path("test-append.txt")

    @Operation
    fun operation(): String {
        // Create a file
        val file = Files.createFile(fileName)
        require(Files.exists(file)) { "File was not created: $file" }

        try {
            // Write initial content
            val initialContent = "Hello, "
            Files.write(file, initialContent.toByteArray(), StandardOpenOption.WRITE)

            // Append content
            val appendContent = "NIO World!"
            Files.write(file, appendContent.toByteArray(), StandardOpenOption.APPEND)

            // Read full content
            val readContent = String(Files.readAllBytes(file))
            val expectedContent = initialContent + appendContent

            // Verify content
            require(readContent == expectedContent) { 
                "Content mismatch: expected '$expectedContent', got '$readContent'" 
            }

            return readContent
        } finally {
            // Clean up
            Files.delete(fileName)
        }
    }
}

// File Editing Tests - IO API

class FileWriteReadTest : FileOperationTest() {
    private val file = File("test-write-read-io.txt")

    @Operation
    fun operation(): String {
        // Create a file
        require(file.createNewFile()) { "Failed to create file: $file" }
        require(file.exists()) { "File was not created: $file" }

        try {
            // Write to file
            val content = "Hello, IO World!"
            FileWriter(file).use { writer ->
                writer.write(content)
            }

            // Read from file
            val readContent = file.inputStream().reader().use { it.readText() }

            // Verify content
            require(readContent == content) { "Content mismatch: expected '$content', got '$readContent'" }

            return readContent
        } finally {
            // Clean up
            require(file.delete()) { "Failed to delete file: $file" }
            require(!file.exists()) { "File was not deleted: $file" }
        }
    }
}

class FileAppendTest : FileOperationTest() {
    private val file = File("test-append-io.txt")

    @Operation
    fun operation(): String {
        // Create a file
        require(file.createNewFile()) { "Failed to create file: $file" }
        require(file.exists()) { "File was not created: $file" }

        try {
            // Write initial content
            val initialContent = "Hello, "
            FileWriter(file).use { writer ->
                writer.write(initialContent)
            }

            // Append content
            val appendContent = "IO World!"
            FileWriter(file, true).use { writer ->
                writer.write(appendContent)
            }

            // Read full content
            val readContent = file.readText()
            val expectedContent = initialContent + appendContent

            // Verify content
            require(readContent == expectedContent) { 
                "Content mismatch: expected '$expectedContent', got '$readContent'" 
            }

            return readContent
        } finally {
            // Clean up
            require(file.delete()) { "Failed to delete file: $file" }
            require(!file.exists()) { "File was not deleted: $file" }
        }
    }
}
