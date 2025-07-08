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
import java.nio.file.*
import java.util.*
import java.util.function.Consumer
import java.util.stream.StreamSupport

abstract class FileSpecialCasesTest : AbstractDeterministicTest()

// Filesystem Operations Tests

class FileSystemGetRootDirectoriesTest : FileSpecialCasesTest() {
    @Operation
    fun operation(): List<String> {
        // Get root directories
        val fileSystem = FileSystems.getDefault()
        val rootDirectories = fileSystem.rootDirectories

        // Convert to list of strings for easier verification
        return rootDirectories.map { it.toString() }.toList()
    }
}

class FileSystemRootDirectoriesForEachTest : FileSpecialCasesTest() {
    @Operation
    fun operation(): List<String> {
        // Get root directories
        val fileSystem = FileSystems.getDefault()
        val rootDirectories = fileSystem.rootDirectories

        // Use forEach with Consumer to iterate over root directories
        val rootPaths = mutableListOf<String>()
        rootDirectories.forEach(Consumer { path ->
            rootPaths.add(path.toString())
        })

        return rootPaths
    }
}

class FileSystemRootDirectoriesSpliteratorTest : FileSpecialCasesTest() {
    @Operation
    fun operation(): List<String> {
        // Get root directories
        val fileSystem = FileSystems.getDefault()
        val rootDirectories = fileSystem.rootDirectories

        // Use spliterator to get root directory paths
        val rootPaths = mutableListOf<String>()
        val spliterator = rootDirectories.spliterator()
        StreamSupport.stream(spliterator, false)
            .forEach { path -> rootPaths.add(path.toString()) }

        return rootPaths
    }
}

class FileSystemGetFileStoresTest : FileSpecialCasesTest() {
    @Operation
    fun operation(): List<String> {
        // Get file stores
        val fileSystem = FileSystems.getDefault()
        val fileStores = fileSystem.fileStores

        // Convert to list of strings for easier verification
        return fileStores.map { it.name() }.toList()
    }
}

class FileSystemFileStoresForEachTest : FileSpecialCasesTest() {
    @Operation
    fun operation(): List<String> {
        // Get file stores
        val fileSystem = FileSystems.getDefault()
        val fileStores = fileSystem.fileStores

        // Use forEach with Consumer to iterate over file stores
        val storeNames = mutableListOf<String>()
        fileStores.forEach(Consumer { store ->
            storeNames.add(store.name())
        })

        return storeNames
    }
}

class FileSystemFileStoresSpliteratorTest : FileSpecialCasesTest() {
    @Operation
    fun operation(): List<String> {
        // Get file stores
        val fileSystem = FileSystems.getDefault()
        val fileStores = fileSystem.fileStores

        // Use spliterator to get file store names
        val storeNames = mutableListOf<String>()
        val spliterator = fileStores.spliterator()
        StreamSupport.stream(spliterator, false)
            .forEach { store -> storeNames.add(store.name()) }

        return storeNames
    }
}

// Path Iterator Tests

class PathIteratorTest : FileSpecialCasesTest() {
    private val dirPath = Paths.get("test-path-iterator")

    @Operation
    fun operation(): List<String> {
        try {
            // Create directory with subdirectories
            Files.createDirectories(dirPath)
            val subPath1 = dirPath.resolve("subdir1")
            val subPath2 = dirPath.resolve("subdir2")
            Files.createDirectories(subPath1)
            Files.createDirectories(subPath2)

            // Use iterator to get path names
            val pathNames = mutableListOf<String>()
            val iterator = dirPath.iterator()
            while (iterator.hasNext()) {
                val path = iterator.next()
                pathNames.add(path.fileName.toString())
            }

            return pathNames
        } finally {
            // Clean up
            Files.walk(dirPath)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.delete(it) }
        }
    }
}

class PathForEachTest : FileSpecialCasesTest() {
    private val dirPath = Paths.get("test-path-foreach")

    @Operation
    fun operation(): List<String> {
        try {
            // Create directory with subdirectories
            Files.createDirectories(dirPath)
            val subPath1 = dirPath.resolve("subdir1")
            val subPath2 = dirPath.resolve("subdir2")
            Files.createDirectories(subPath1)
            Files.createDirectories(subPath2)

            // Use forEach to get path names
            val pathNames = mutableListOf<String>()
            dirPath.forEach(Consumer { path ->
                pathNames.add(path.fileName.toString())
            })

            return pathNames
        } finally {
            // Clean up
            Files.walk(dirPath)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.delete(it) }
        }
    }
}

class PathSpliteratorTest : FileSpecialCasesTest() {
    private val dirPath = Paths.get("test-path-spliterator")

    @Operation
    fun operation(): List<String> {
        try {
            // Create directory with subdirectories
            Files.createDirectories(dirPath)
            val subPath1 = dirPath.resolve("subdir1")
            val subPath2 = dirPath.resolve("subdir2")
            Files.createDirectories(subPath1)
            Files.createDirectories(subPath2)

            // Use spliterator to get path names
            val pathNames = mutableListOf<String>()
            val spliterator = dirPath.spliterator()
            StreamSupport.stream(spliterator, false)
                .forEach { path -> pathNames.add(path.fileName.toString()) }

            return pathNames
        } finally {
            // Clean up
            Files.walk(dirPath)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.delete(it) }
        }
    }
}

class PathIteratorForEachRemainingTest : FileSpecialCasesTest() {
    private val dirPath = Paths.get("test-path-iterator-foreach-remaining")

    @Operation
    fun operation(): List<String> {
        try {
            // Create directory with subdirectories
            Files.createDirectories(dirPath)
            val subPath1 = dirPath.resolve("subdir1")
            val subPath2 = dirPath.resolve("subdir2")
            Files.createDirectories(subPath1)
            Files.createDirectories(subPath2)

            // Use iterator.forEachRemaining to get path names
            val pathNames = mutableListOf<String>()
            val iterator = dirPath.iterator()
            iterator.forEachRemaining { path ->
                pathNames.add(path.fileName.toString())
            }

            return pathNames
        } finally {
            // Clean up
            Files.walk(dirPath)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.delete(it) }
        }
    }
}

// DirectoryStream Tests

class DirectoryStreamIteratorTest : FileSpecialCasesTest() {
    private val dirPath = Paths.get("test-directory-stream-iterator")

    @Operation
    fun operation(): List<String> {
        try {
            // Create directory with files
            Files.createDirectories(dirPath)
            Files.createFile(dirPath.resolve("file1.txt"))
            Files.createFile(dirPath.resolve("file2.txt"))
            Files.createFile(dirPath.resolve("file3.txt"))

            // Use DirectoryStream iterator to get file names
            val fileNames = mutableListOf<String>()
            Files.newDirectoryStream(dirPath).use { directoryStream ->
                val iterator = directoryStream.iterator()
                while (iterator.hasNext()) {
                    val path = iterator.next()
                    fileNames.add(path.fileName.toString())
                }
            }

            return fileNames.sorted()
        } finally {
            // Clean up
            Files.walk(dirPath)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.delete(it) }
        }
    }
}

class DirectoryStreamForEachTest : FileSpecialCasesTest() {
    private val dirPath = Paths.get("test-directory-stream-foreach")

    @Operation
    fun operation(): List<String> {
        try {
            // Create directory with files
            Files.createDirectories(dirPath)
            Files.createFile(dirPath.resolve("file1.txt"))
            Files.createFile(dirPath.resolve("file2.txt"))
            Files.createFile(dirPath.resolve("file3.txt"))

            // Use DirectoryStream forEach to get file names
            val fileNames = mutableListOf<String>()
            Files.newDirectoryStream(dirPath).use { directoryStream ->
                directoryStream.forEach(Consumer { path ->
                    fileNames.add(path.fileName.toString())
                })
            }

            return fileNames.sorted()
        } finally {
            // Clean up
            Files.walk(dirPath)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.delete(it) }
        }
    }
}

class DirectoryStreamSpliteratorTest : FileSpecialCasesTest() {
    private val dirPath = Paths.get("test-directory-stream-spliterator")

    @Operation
    fun operation(): List<String> {
        try {
            // Create directory with files
            Files.createDirectories(dirPath)
            Files.createFile(dirPath.resolve("file1.txt"))
            Files.createFile(dirPath.resolve("file2.txt"))
            Files.createFile(dirPath.resolve("file3.txt"))

            // Use DirectoryStream spliterator to get file names
            val fileNames = mutableListOf<String>()
            Files.newDirectoryStream(dirPath).use { directoryStream ->
                val spliterator = directoryStream.spliterator()
                require(runCatching { spliterator.comparator }.isFailure)
                StreamSupport.stream(spliterator, false)
                    .forEach { path -> fileNames.add(path.fileName.toString()) }
            }

            return fileNames.sorted()
        } finally {
            // Clean up
            Files.walk(dirPath)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.delete(it) }
        }
    }
}
