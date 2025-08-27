/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.transformation

import java.util.Objects

/*
  This code was heavily adapted from `SDE.java` from IDEA monorepo.
 */
class SMAPInfo {
    private data class FileTableRecord(
        val fileId: Int,
        val sourceName: String,
        val className: String?
    )

    private data class LineTableRecord (
        val jplsStart: Int,
        val jplsEnd: Int,
        val jplsLineInc: Int,
        val njplsStart: Int,
        val njplsEnd: Int,
        val fileId: Int
    ) {
        /**
         * Checks whether this mapping contains the provided line number in the mapped range.
         */
        fun containsMappedLine(njplsLine: Int): Boolean {
            return njplsStart <= njplsLine && njplsLine <= njplsEnd
        }
    }

    private data class StratumTableRecord(
        val id: String,
        val fileIndex: Int,
        val lineIndex: Int
    )

    private inner class Stratum(private val sti: Int) {
        val id: String
            get() = stratumTable[sti].id

        val isJava: Boolean
            get() = sti == baseStratumIndex

        /**
         * Return all the sourceNames for this stratum.
         * Look from our starting fileIndex up to the starting
         * fileIndex of next stratum - can do this since there
         * is always a terminator stratum.
         * Default sourceName (the first one) must be first.
         */
        fun sourceNames(): List<String> {
            val fileIndexStart: Int = stratumTable[sti].fileIndex
            /* one past end */
            val fileIndexEnd: Int = stratumTable[sti + 1].fileIndex
            val result: MutableList<String> = mutableListOf()
            for (i in fileIndexStart ..< fileIndexEnd) {
                result.add(fileTable[i].sourceName)
            }
            return result
        }

        fun hasMappedLineTo(targetSourceName: String, njplsLine: Int): Boolean {
            val lineIndexStart: Int = stratumTable[sti].lineIndex
            val lineIndexEnd: Int = stratumTable[sti + 1].lineIndex
            for (lti in lineIndexStart..<lineIndexEnd) {
                val record: LineTableRecord = lineTable[lti]
                if (record.containsMappedLine(njplsLine)) {
                    val fti = stiFileTableIndex(sti, lti)
                    if (fti == -1) {
                        throw InternalError(
                            ("Bad SourceDebugExtension, no matching source id "
                                    + lineTable[lti].fileId + "\n" + sourceDebugExtension)
                        )
                    }
                    val ftr: FileTableRecord = fileTable[fti]
                    val sourceName: String = ftr.sourceName
                    if (targetSourceName == sourceName) {
                        return true
                    }
                }
            }
            return false
        }

        fun lineStratum(jplsLine: Int): LineStratum? {
            val lti = stiLineTableIndex(sti, jplsLine)
            if (lti < 0) {
                return null
            } else {
                return LineStratum(sti, lti,jplsLine)
            }
        }
    }

    internal inner class LineStratum(
        private val sti: Int,
        private val lti: Int,
        private val jplsLine: Int
    ) {
        var sourceName: String? = null
            get() {
                calculateSourceInfo()
                return field
            }
            private set

        var className: String? = null
            get() {
                calculateSourceInfo()
                return field
            }
            private set

        private var initialized = false

        override fun equals(other: Any?): Boolean {
            return (other is LineStratum)
                    && (lti == other.lti)
                    && (sti == other.sti)
                    && (lineNumber() == other.lineNumber())
        }

        override fun hashCode(): Int = Objects.hash(lineNumber())

        fun lineNumber(): Int {
            return stiLineNumber(sti, lti, jplsLine)
        }

        private fun calculateSourceInfo() {
            if (initialized) {
                // already done
                return
            }
            val fti = stiFileTableIndex(sti, lti)
            if (fti == -1) {
                throw InternalError(
                    "Bad SourceDebugExtension, no matching source id " +
                            lineTable[lti].fileId + " jplsLine: " + jplsLine
                )
            }
            val ftr: FileTableRecord = fileTable[fti]
            sourceName = ftr.sourceName
            className = ftr.className
            initialized = true
        }
    }

    private val fileTable: MutableList<FileTableRecord> = mutableListOf()
    private val lineTable: MutableList<LineTableRecord> = mutableListOf()
    private val stratumTable: MutableList<StratumTableRecord> = mutableListOf()

    private var currentFileId = 0

    private var defaultStratumIndex = -1
    private var baseStratumIndex = -2 /* so as not to match -1 above */
    private var sdePos = 0

    private val sourceDebugExtension: String
    private var jplsFilename: String = ""
    private var defaultStratumId: String? = null
    private var isValid: Boolean = false

    constructor(sourceDebugExtension: String) {
        this.sourceDebugExtension = sourceDebugExtension
        decode()
    }

    private fun sdePeek(): Char {
        if (sdePos >= sourceDebugExtension.length) {
            syntax()
        }
        return sourceDebugExtension.get(sdePos)
    }

    private fun sdeRead(): Char {
        if (sdePos >= sourceDebugExtension.length) {
            syntax()
        }
        return sourceDebugExtension.get(sdePos++)
    }

    private fun sdeAdvance() {
        sdePos++
    }

    private fun syntax() {
        throw InternalError("Invalid SourceDebugExtension syntax - position $sdePos, raw: \"$sourceDebugExtension\"")
    }

    private fun readLine(): String {
        val sb = StringBuilder()
        var ch: Char

        ignoreWhite()
        while (((sdeRead().also { ch = it }) != '\n') && (ch != '\r')) {
            sb.append(ch)
        }
        // check for CR LF
        if ((ch == '\r') && (sdePeek() == '\n')) {
            sdeRead()
        }
        ignoreWhite() // leading white
        return sb.toString()
    }

    private fun defaultStratumTableIndex(): Int {
        if ((defaultStratumIndex == -1) && (defaultStratumId != null)) {
            defaultStratumIndex =
                stratumTableIndex(defaultStratumId)
        }
        return defaultStratumIndex
    }

    private fun stratumTableIndex(stratumId: String?): Int {
        if (stratumId == null) {
            return defaultStratumIndex
        }
        for (i in 0 ..< stratumTable.size) {
            if (stratumTable[i].id == stratumId) {
                return i
            }
        }
        return -1
    }

    private fun stratum(stratumID: String?): Stratum? {
        val sti = stratumTableIndex(stratumID)
        return if (sti < 0) null else Stratum(sti)
    }

    fun availableStrata(): List<String> {
        val strata: MutableList<String> = mutableListOf()

        for (i in 0 ..< stratumTable.size - 1) {
            val rec: StratumTableRecord = stratumTable[i]
            strata.add(rec.id)
        }
        return strata
    }

    fun getLine(stratumID: String?, jplsLine: Int): LineClassAndSourcePath? {
        val lineStratum: LineStratum = stratum(stratumID)?.lineStratum(jplsLine) ?: return null
        return LineClassAndSourcePath(lineStratum.lineNumber(), lineStratum.sourceName!!, lineStratum.className)
    }

    data class LineClassAndSourcePath(val line: Int, val sourceName: String, val className: String?)

    private fun ignoreWhite() {
        var ch: Char

        while (((sdePeek().also { ch = it }) == ' ') || (ch == '\t')) {
            sdeAdvance()
        }
    }

    private fun ignoreLine() {
        var ch: Char

        while (((sdeRead().also { ch = it }) != '\n') && (ch != '\r')) {
        }
        /* check for CR LF */
        if ((ch == '\r') && (sdePeek() == '\n')) {
            sdeAdvance()
        }
        ignoreWhite() /* leading white */
    }

    private fun readNumber(): Int {
        var value = 0
        var positive = true
        var ch: Char

        ignoreWhite()

        if (sdePeek() == '-') {
            sdeAdvance()
            positive = false
        }
        while (((sdePeek().also { ch = it }) >= '0') && (ch <= '9')) {
            sdeAdvance()
            value = (value * 10) + ch.code - '0'.code
        }
        ignoreWhite()
        return if (positive) value else -value
    }

    private fun fileLine() {
        /* is there a class name (in spec it is an absolute filename)? */
        val hasClassName = if (sdePeek() == '+') {
            sdeAdvance()
            true
        } else {
            false
        }

        val fileId = readNumber()
        val sourceName = readLine()
        val className = if (hasClassName) {
            readLine()
        } else {
            null
        }

        fileTable.add(
            FileTableRecord(
                fileId = fileId,
                sourceName = sourceName,
                className = className
            )
        )
    }

    /**
     * Parse line translation info.  Syntax is
     * <NJ-start-line> [ # <file-id> ] [ , <line-count> ] :
     * <J-start-line> [ , <line-increment> ] CR
    </line-increment></J-start-line></line-count></file-id></NJ-start-line> */
    private fun lineLine() {
        var lineCount = 1
        var lineIncrement = 1
        val njplsStart: Int
        val jplsStart: Int

        njplsStart = readNumber()

        /* is there a fileID? */
        if (sdePeek() == '#') {
            sdeAdvance()
            currentFileId = readNumber()
        }

        /* is there a line count? */
        if (sdePeek() == ',') {
            sdeAdvance()
            lineCount = readNumber()
        }

        if (sdeRead() != ':') {
            syntax()
        }
        jplsStart = readNumber()
        if (sdePeek() == ',') {
            sdeAdvance()
            lineIncrement = readNumber()
        }
        ignoreLine() /* flush the rest */

        if (njplsStart >= 0) { // skip incorrect lines
            lineTable.add(
                LineTableRecord(
                    jplsStart = jplsStart,
                    jplsEnd = jplsStart + (lineCount * lineIncrement) - 1,
                    jplsLineInc = lineIncrement,
                    njplsStart = njplsStart,
                    njplsEnd = njplsStart + lineCount - 1,
                    fileId = currentFileId
                )
            )
        }
    }

    /**
     * Until the next stratum section, everything after this
     * is in stratumId - so, store the current indices.
     */
    private fun storeStratum(stratumId: String) {
        /* remove redundant strata */
        if (stratumTable.isNotEmpty()) {
            if ((stratumTable.last().fileIndex == fileTable.size) &&
                (stratumTable.last().lineIndex == lineTable.size)
            ) {
                /* nothing changed overwrite it */
                stratumTable.removeLast()
            }
        }
        /* store the results */
        stratumTable.add(
            StratumTableRecord(
                id = stratumId,
                fileIndex = fileTable.size,
                lineIndex = lineTable.size,
            )
        )
        currentFileId = 0
    }

    /**
     * The beginning of a stratum's info
     */
    private fun stratumSection() {
        storeStratum(readLine())
    }

    private fun fileSection() {
        ignoreLine()
        while (sdePeek() != '*') {
            fileLine()
        }
    }

    private fun lineSection() {
        ignoreLine()
        while (sdePeek() != '*') {
            lineLine()
        }
    }

    /**
     * Ignore a section we don't know about.
     */
    private fun ignoreSection() {
        ignoreLine()
        while (sdePeek() != '*') {
            ignoreLine()
        }
    }

    /**
     * A base "Java" stratum is always available, though
     * it is not in the SourceDebugExtension.
     * Create the base stratum.
     */
    private fun createJavaStratum() {
        baseStratumIndex = stratumTable.size
        storeStratum(BASE_STRATUM_NAME)
        fileTable.add(
            FileTableRecord(
                fileId = 1,
                sourceName = jplsFilename!!,
                className = NullString
            )
        )
        /* JPL line numbers cannot exceed 65535 */
        lineTable.add(
            LineTableRecord(
                jplsStart = 1,
                jplsEnd = 65536,
                jplsLineInc = 1,
                njplsStart = 1,
                njplsEnd = 65536,
                fileId = 1
            )
        )
        storeStratum("Aux") /* in case they don't declare */
    }

    /**
     * Decode a SourceDebugExtension which is in SourceMap format.
     * This is the entry point into the recursive descent parser.
     */
    private fun decode() {
        /* check for "SMAP" - allow EOF if not ours */
        if ((sourceDebugExtension.length < 4) ||
            (sdeRead() != 'S') ||
            (sdeRead() != 'M') ||
            (sdeRead() != 'A') ||
            (sdeRead() != 'P')
        ) {
            createProxyForAbsentSDE()
            return  /* not our info */
        }

        ignoreLine() /* flush the rest */
        jplsFilename = readLine()
        defaultStratumId = readLine()
        createJavaStratum()
        while (true) {
            if (sdeRead() != '*') {
                syntax()
            }
            when (sdeRead()) {
                'S' -> stratumSection()
                'F' -> fileSection()
                'L' -> lineSection()
                'E' -> {
                    /* set end points */
                    storeStratum("*terminator*")
                    isValid = true
                    return
                }

                else -> ignoreSection()
            }
        }
    }

    private fun createProxyForAbsentSDE() {
        jplsFilename = ""
        defaultStratumId = BASE_STRATUM_NAME
        defaultStratumIndex = stratumTable.size
        createJavaStratum()
        storeStratum("*terminator*")
    }

    /***************** query functions  */
    private fun stiLineTableIndex(sti: Int, jplsLine: Int): Int {

        val lineIndexStart: Int = stratumTable[sti].lineIndex
        /* one past end */
        val lineIndexEnd: Int = stratumTable[sti + 1].lineIndex
        for (i in lineIndexStart ..< lineIndexEnd) {
            if ((jplsLine >= lineTable[i].jplsStart) &&
                (jplsLine <= lineTable[i].jplsEnd)
            ) {
                return i
            }
        }
        return -1
    }

    private fun stiLineNumber(sti: Int, lti: Int, jplsLine: Int): Int {
        return lineTable[lti].njplsStart +
                (((jplsLine - lineTable[lti].jplsStart) /
                        lineTable[lti].jplsLineInc))
    }

    private fun fileTableIndex(sti: Int, fileId: Int): Int {
        val fileIndexStart: Int = stratumTable[sti].fileIndex
        /* one past end */
        val fileIndexEnd: Int = stratumTable[sti + 1].fileIndex
        for (i in fileIndexStart ..< fileIndexEnd) {
            if (fileTable[i].fileId == fileId) {
                return i
            }
        }
        return -1
    }

    private fun stiFileTableIndex(sti: Int, lti: Int): Int {
        return fileTableIndex(sti, lineTable[lti].fileId)
    }

    companion object {
        private const val INIT_SIZE_FILE = 3
        private const val INIT_SIZE_LINE = 100
        private const val INIT_SIZE_STRATUM = 3

        const val BASE_STRATUM_NAME: String = "Java"

        /* for C capatibility */
        val NullString: String? = null
    }
}