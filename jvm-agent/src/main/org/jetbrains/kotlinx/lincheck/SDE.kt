/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck

import java.util.*

class SDE {
    private data class FileTableRecord(
        val fileId: Int,
        val sourceName: String,
        val className: String?,
        val isConverted: Boolean
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

    private inner class Stratum private constructor(private val sti: Int) {
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
                if (sourceName != null) {
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
            }
    }

    private val fileTable: MutableList<FileTableRecord> = mutableListOf()
    private val lineTable: MutableList<LineTableRecord> = mutableListOf()
    private val stratumTable: MutableList<StratumTableRecord> = mutableListOf()

    private var fileIndex = 0
    private var lineIndex = 0
    private var stratumIndex = 0
    private var currentFileId = 0

    private var defaultStratumIndex = -1
    private var baseStratumIndex = -2 /* so as not to match -1 above */
    private var sdePos = 0

    val sourceDebugExtension: String?
    var jplsFilename: String? = null
    var defaultStratumId: String? = null
    var isValid: Boolean = false

    constructor(sourceDebugExtension: String?) {
        this.sourceDebugExtension = sourceDebugExtension
        decode()
    }

    internal constructor() {
        sourceDebugExtension = null
        createProxyForAbsentSDE()
    }

    fun sdePeek(): Char {
        if (sdePos >= sourceDebugExtension!!.length) {
            syntax()
        }
        return sourceDebugExtension.get(sdePos)
    }

    fun sdeRead(): Char {
        if (sdePos >= sourceDebugExtension!!.length) {
            syntax()
        }
        return sourceDebugExtension.get(sdePos++)
    }

    fun sdeAdvance() {
        sdePos++
    }

    fun syntax() {
        throw InternalError(
            "bad SourceDebugExtension syntax - position " +
                    sdePos + " raw: " + sourceDebugExtension
        )
    }

    fun syntax(msg: String?) {
        throw InternalError("bad SourceDebugExtension syntax: " + msg)
    }

    fun assureLineTableSize() {
        val len = if (lineTable == null) 0 else lineTable!!.size
        if (lineIndex >= len) {
            var i: Int
            val newLen = if (len == 0) INIT_SIZE_LINE else len * 2
            val newTable: Array<LineTableRecord> =
                kotlin.arrayOfNulls<LineTableRecord>(newLen)
            i = 0
            while (i < len) {
                newTable[i] = lineTable!![i]
                ++i
            }
            while (i < newLen) {
                newTable[i] = LineTableRecord()
                ++i
            }
            lineTable = newTable
        }
    }

    fun assureFileTableSize() {
        val len = if (fileTable == null) 0 else fileTable!!.size
        if (fileIndex >= len) {
            var i: Int
            val newLen = if (len == 0) INIT_SIZE_FILE else len * 2
            val newTable: Array<FileTableRecord> =
                arrayOfNulls<FileTableRecord>(newLen)
            i = 0
            while (i < len) {
                newTable[i] = fileTable!![i]
                ++i
            }
            while (i < newLen) {
                newTable[i] = FileTableRecord()
                ++i
            }
            fileTable = newTable
        }
    }

    fun assureStratumTableSize() {
        val len = if (stratumTable == null) 0 else stratumTable!!.size
        if (stratumIndex >= len) {
            var i: Int
            val newLen = if (len == 0) INIT_SIZE_STRATUM else len * 2
            val newTable: Array<StratumTableRecord> =
                kotlin.arrayOfNulls<StratumTableRecord>(newLen)
            i = 0
            while (i < len) {
                newTable[i] = stratumTable!![i]
                ++i
            }
            while (i < newLen) {
                newTable[i] = StratumTableRecord()
                ++i
            }
            stratumTable = newTable
        }
    }

    fun readLine(): String {
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

    fun stratumTableIndex(stratumId: String?): Int {
        var i: Int

        if (stratumId == null) {
            return defaultStratumTableIndex()
        }
        i = 0
        while (i < (stratumIndex - 1)) {
            if (stratumTable!![i].id == stratumId) {
                return i
            }
            ++i
        }
        return defaultStratumTableIndex()
    }

    fun stratum(stratumID: String?): Stratum {
        val sti = stratumTableIndex(stratumID)
        return Stratum(sti)
    }

    fun availableStrata(): MutableList<String?> {
        val strata: MutableList<String?> = ArrayList<String?>()

        for (i in 0..<(stratumIndex - 1)) {
            val rec: StratumTableRecord = stratumTable!![i]
            strata.add(rec.id)
        }
        return strata
    }

    fun getLine(stratumID: String?, jplsLine: Int): LineAndSourcePath? {
        val lineStratum: LineStratum? = stratum(stratumID).lineStratum(null, jplsLine)
        if (lineStratum == null) return null
        var sourcePath: String?
        try {
            sourcePath = lineStratum.sourcePath()
        } catch (e: NullPointerException) {
            // Source path extraction takes ReferenceType instance as input, see SDE.FileTableRecord.getSourcePath
            // However, the source path is usually present in the SMAP, so we pass null.
            sourcePath = null
        }
        return LineAndSourcePath(lineStratum.lineNumber(), sourcePath)
    }

    class LineAndSourcePath(val line: Int, val sourcePath: String?) {
        override fun equals(o: Any?): Boolean {
            if (this === o) return true
            if (o !is LineAndSourcePath) return false

            return line == o.line && sourcePath == o.sourcePath
        }

        override fun hashCode(): Int {
            var result = line
            result = 31 * result + Objects.hashCode(sourcePath)
            return result
        }
    }

    /*****************************
     * below functions/methods are written to compile under either Java or C
     *
     * Needed support functions:
     * sdePeek()
     * sdeRead()
     * sdeAdvance()
     * readLine()
     * assureLineTableSize()
     * assureFileTableSize()
     * assureStratumTableSize()
     * syntax()
     *
     * stratumTableIndex(String)
     *
     * Needed support variables:
     * lineTable
     * lineIndex
     * fileTable
     * fileIndex
     * currentFileId
     *
     * Needed types:
     * String
     *
     * Needed constants:
     * NullString
     */
    fun ignoreWhite() {
        var ch: Char

        while (((sdePeek().also { ch = it }) == ' ') || (ch == '\t')) {
            sdeAdvance()
        }
    }

    fun ignoreLine() {
        var ch: Char

        while (((sdeRead().also { ch = it }) != '\n') && (ch != '\r')) {
        }
        /* check for CR LF */
        if ((ch == '\r') && (sdePeek() == '\n')) {
            sdeAdvance()
        }
        ignoreWhite() /* leading white */
    }

    fun readNumber(): Int {
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

    fun storeFile(fileId: Int, sourceName: String?, sourcePath: String?) {
        assureFileTableSize()
        fileTable!![fileIndex].fileId = fileId
        fileTable!![fileIndex].sourceName = sourceName
        fileTable!![fileIndex].className = sourcePath
        ++fileIndex
    }

    fun fileLine() {
        var hasAbsolute = 0 /* acts as boolean */
        val fileId: Int
        val sourceName: String?
        var sourcePath: String? = null

        /* is there an absolute filename? */
        if (sdePeek() == '+') {
            sdeAdvance()
            hasAbsolute = 1
        }
        fileId = readNumber()
        sourceName = readLine()
        if (hasAbsolute == 1) {
            sourcePath = readLine()
        }

        storeFile(fileId, sourceName, sourcePath)
    }

    fun storeLine(
        jplsStart: Int, jplsEnd: Int, jplsLineInc: Int,
        njplsStart: Int, njplsEnd: Int, fileId: Int
    ) {
        assureLineTableSize()
        lineTable!![lineIndex].jplsStart = jplsStart
        lineTable!![lineIndex].jplsEnd = jplsEnd
        lineTable!![lineIndex].jplsLineInc = jplsLineInc
        lineTable!![lineIndex].njplsStart = njplsStart
        lineTable!![lineIndex].njplsEnd = njplsEnd
        lineTable!![lineIndex].fileId = fileId
        ++lineIndex
    }

    /**
     * Parse line translation info.  Syntax is
     * <NJ-start-line> [ # <file-id> ] [ , <line-count> ] :
     * <J-start-line> [ , <line-increment> ] CR
    </line-increment></J-start-line></line-count></file-id></NJ-start-line> */
    fun lineLine() {
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
            storeLine(
                jplsStart,
                jplsStart + (lineCount * lineIncrement) - 1,
                lineIncrement,
                njplsStart,
                njplsStart + lineCount - 1,
                currentFileId
            )
        }
    }

    /**
     * Until the next stratum section, everything after this
     * is in stratumId - so, store the current indices.
     */
    fun storeStratum(stratumId: String) {
        /* remove redundant strata */
        if (stratumIndex > 0) {
            if ((stratumTable!![stratumIndex - 1].fileIndex
                        == fileIndex) &&
                (stratumTable!![stratumIndex - 1].lineIndex
                        == lineIndex)
            ) {
                /* nothing changed overwrite it */
                --stratumIndex
            }
        }
        /* store the results */
        assureStratumTableSize()
        stratumTable!![stratumIndex].id = stratumId
        stratumTable!![stratumIndex].fileIndex = fileIndex
        stratumTable!![stratumIndex].lineIndex = lineIndex
        ++stratumIndex
        currentFileId = 0
    }

    /**
     * The beginning of a stratum's info
     */
    fun stratumSection() {
        storeStratum(readLine())
    }

    fun fileSection() {
        ignoreLine()
        while (sdePeek() != '*') {
            fileLine()
        }
    }

    fun lineSection() {
        ignoreLine()
        while (sdePeek() != '*') {
            lineLine()
        }
    }

    /**
     * Ignore a section we don't know about.
     */
    fun ignoreSection() {
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
    fun createJavaStratum() {
        baseStratumIndex = stratumIndex
        storeStratum(BASE_STRATUM_NAME)
        storeFile(1, jplsFilename, NullString)
        /* JPL line numbers cannot exceed 65535 */
        storeLine(1, 65536, 1, 1, 65536, 1)
        storeStratum("Aux") /* in case they don't declare */
    }

    /**
     * Decode a SourceDebugExtension which is in SourceMap format.
     * This is the entry point into the recursive descent parser.
     */
    fun decode() {
        /* check for "SMAP" - allow EOF if not ours */
        if ((sourceDebugExtension!!.length < 4) ||
            (sdeRead() != 'S') ||
            (sdeRead() != 'M') ||
            (sdeRead() != 'A') ||
            (sdeRead() != 'P')
        ) {
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

    fun createProxyForAbsentSDE() {
        jplsFilename = null
        defaultStratumId = BASE_STRATUM_NAME
        defaultStratumIndex = stratumIndex
        createJavaStratum()
        storeStratum("*terminator*")
    }

    /***************** query functions  */
    private fun stiLineTableIndex(sti: Int, jplsLine: Int): Int {
        var i: Int
        val lineIndexStart: Int
        val lineIndexEnd: Int

        lineIndexStart = stratumTable!![sti].lineIndex
        /* one past end */
        lineIndexEnd = stratumTable!![sti + 1].lineIndex
        i = lineIndexStart
        while (i < lineIndexEnd) {
            if ((jplsLine >= lineTable!![i].jplsStart) &&
                (jplsLine <= lineTable!![i].jplsEnd)
            ) {
                return i
            }
            ++i
        }
        return -1
    }

    private fun stiLineNumber(sti: Int, lti: Int, jplsLine: Int): Int {
        return lineTable!![lti].njplsStart +
                (((jplsLine - lineTable!![lti].jplsStart) /
                        lineTable!![lti].jplsLineInc))
    }

    private fun fileTableIndex(sti: Int, fileId: Int): Int {
        var i: Int
        val fileIndexStart: Int = stratumTable!![sti].fileIndex
        /* one past end */
        val fileIndexEnd: Int = stratumTable!![sti + 1].fileIndex
        i = fileIndexStart
        while (i < fileIndexEnd) {
            if (fileTable!![i].fileId == fileId) {
                return i
            }
            ++i
        }
        return -1
    }

    private fun stiFileTableIndex(sti: Int, lti: Int): Int {
        return fileTableIndex(sti, lineTable!![lti].fileId)
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