/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.util

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes


// ===== Project package discovery =====

fun computeProjectPackages(root: Path, excludeDirPaths: List<Path> = emptyList()): List<String> {
    val files = mutableListOf<Path>()

    // Directories to skip fully during traversal
    val skipDirNames = setOf(
        "build", "out", "node_modules", "target", "dist", "bin"
    )

    // Normalize excludes: resolve relative paths against root, then normalize & toAbsolutePath
    val normalizedExcludes: List<Path> = excludeDirPaths
        .mapNotNull { p ->
            try {
                val resolved = if (p.isAbsolute) p else root.resolve(p)
                resolved.toAbsolutePath().normalize()
            } catch (_: Exception) { null }
        }

    Files.walkFileTree(
        root,
        object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val name = dir.fileName?.toString() ?: return FileVisitResult.CONTINUE
                // Skip common build/hidden and user-provided excludes
                if (name.startsWith('.') || name in skipDirNames) return FileVisitResult.SKIP_SUBTREE
                val abs = try { dir.toAbsolutePath().normalize() } catch (_: Exception) { dir }
                for (ex in normalizedExcludes) {
                    // If this directory is the excluded dir or lies under it — skip the subtree
                    if (abs == ex || abs.startsWith(ex)) return FileVisitResult.SKIP_SUBTREE
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val n = file.fileName.toString()
                if (n.endsWith(".kt") || n.endsWith(".java")) files.add(file)
                return FileVisitResult.CONTINUE
            }
        }
    )

    val maxLinesRead = 50
    val packages = mutableListOf<String>()
    for (file in files) {
        val pkg = readPackageName(file, maxLinesRead) ?: continue
        if (pkg.isNotEmpty()) packages.add(pkg)
    }
    return compressPackages(packages)
}

private fun compressPackages(packages: List<String>): List<String> {
    if (packages.isEmpty()) return emptyList()

    // Minimize: keep only top-most packages (skip subpackages of already added ones)
    val sorted = packages.sortedBy { it.length }
    val result = mutableSetOf<String>()
    for (p in sorted) {
        if (p in result || result.any { p.startsWith("$it.") }) continue
        result.add(p)
    }
    return result.toList()
}

private fun readPackageName(path: Path, maxLinesRead: Int = -1): String? {
    return try {
        Files.newBufferedReader(path).use { br ->
            var count = 0
            while (true) {
                val line = br.readLine() ?: break
                count++
                parsePackageFromLine(line)?.let { return it }
                if (maxLinesRead != -1 && count >= maxLinesRead) break
            }
            null
        }
    } catch (_: Exception) {
        null
    }
}

private fun parsePackageFromLine(rawLine: String): String? {
    var i = 0

    fun skipSpaces() {
        while (i < rawLine.length && rawLine[i].isWhitespace()) i++
    }

    // Skip leading spaces
    skipSpaces()
    // Quickly ignore comment or annotation lines
    if (i < rawLine.length && (rawLine[i] == '/' || rawLine[i] == '@')) return null

    // Expect keyword "package"
    val kw = "package"
    if (i + kw.length > rawLine.length) return null
    if (!rawLine.regionMatches(i, kw, 0, kw.length, ignoreCase = false)) return null
    i += kw.length
    if (i < rawLine.length && !rawLine[i].isWhitespace()) return null // must be followed by space
    skipSpaces()

    // Parse qualified name: Identifier( . Identifier )*
    val start = i

    fun isIdStart(ch: Char) = ch == '_' || ch.isLetter()
    fun isIdPart(ch: Char) = ch == '_' || ch.isLetterOrDigit()

    var expectId = true
    var seenChar = false
    while (i < rawLine.length) {
        val ch = rawLine[i]
        if (expectId) {
            if (!isIdStart(ch)) break
            i++
            while (i < rawLine.length && isIdPart(rawLine[i])) i++
            expectId = false
            seenChar = true
        } else {
            if (ch != '.') break
            i++
            expectId = true
        }
    }
    if (!seenChar) return null
    val pkg = rawLine.substring(start, i)
    return pkg.ifEmpty { null }
}