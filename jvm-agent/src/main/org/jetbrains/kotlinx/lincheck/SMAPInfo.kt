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

import java.io.BufferedReader
import java.io.IOException
import java.io.Reader

const val KOTLIN_STRATA_NAME = "Kotlin"
const val KOTLIN_DEBUG_STRATA_NAME = "KotlinDebug"

/**
 * Copied and adapted from intellij's [com.intellij.debugger.mockJDI.types.SMAPInfo]
 */

class SMAPInfo private constructor(smapReader: Reader) {
    private val myReader: BufferedReader = BufferedReader(smapReader)
    private var outputFileName: String? = null
    private var myDefaultStratum: String? = null
    private val myId2Stratum: MutableMap<String, StratumInfo> = hashMapOf()
    private var myLastFileID: String? = null

    private fun doParse() {
        val s = myReader.readLine()
        if (SMAP_ID != s) {
            return
        }

        outputFileName = myReader.readLine()
        myDefaultStratum = myReader.readLine()
        var sectionHeader = myReader.readLine()
        var currentStratum: StratumInfo? = null
        var level = 0
        while (sectionHeader != null && END_SECTION_HEADER != sectionHeader) {
            if (sectionHeader.startsWith(OPEN_EMBEDDED_STRATUM_HEADER)) {
                ++level
            } else if (sectionHeader.startsWith(CLOSE_EMBEDDED_STRATUM_HEADER)) {
                --level
            } else if (level == 0) {
                if (sectionHeader.startsWith(STRATUM_SECTION_PREFIX)) {
                    val stratumId = sectionHeader.substring(STRATUM_SECTION_PREFIX.length)
                    currentStratum = StratumInfo(stratumId)
                    myId2Stratum[stratumId] = currentStratum
                } else if (sectionHeader == FILE_SECTION_HEADER) {
                    var fileInfo = myReader.readLine()
                    while (fileInfo != null && !fileInfo.startsWith("*")) {
                        parseFileInfo(currentStratum!!, fileInfo)
                        fileInfo = myReader.readLine()
                    }
                    sectionHeader = fileInfo
                    continue
                } else if (sectionHeader == LINE_SECTION_HEADER) {
                    myLastFileID = "0"
                    var lineInfo = myReader.readLine()
                    while (lineInfo != null && !lineInfo.startsWith("*")) {
                        parseLineInfo(currentStratum!!, lineInfo)
                        lineInfo = myReader.readLine()
                    }
                    sectionHeader = lineInfo
                    continue
                }
            }


            sectionHeader = myReader.readLine()
        }
    }

    private fun parseLineInfo(currentStratum: StratumInfo, lineInfo: String) {
        val colonIndex = lineInfo.indexOf(':')
        var inputInfo = lineInfo.take(colonIndex)
        var outputInfo = lineInfo.substring(colonIndex + 1)

        val repeatCount: Int
        val inputCommaIndex = inputInfo.indexOf(',')
        if (inputCommaIndex != -1) {
            repeatCount = inputInfo.substring(inputCommaIndex + 1).toInt()
            inputInfo = inputInfo.take(inputCommaIndex)
        } else {
            repeatCount = 1
        }

        val sharpIndex = inputInfo.indexOf('#')
        val fileId: String?
        if (sharpIndex != -1) {
            fileId = inputInfo.substring(sharpIndex + 1)
            inputInfo = inputInfo.take(sharpIndex)
        } else {
            fileId = myLastFileID
        }
        val inputStartLine = inputInfo.toInt()

        val outputCommaIndex = outputInfo.indexOf(',')
        val increment: Int
        if (outputCommaIndex != -1) {
            increment = outputInfo.substring(outputCommaIndex + 1).toInt()
            outputInfo = outputInfo.take(outputCommaIndex)
        } else {
            increment = 1
        }
        val outputStartLine = outputInfo.toInt()

        val info = currentStratum.myFileId2Info[fileId]!!
        for (i in 0..<repeatCount) {
            for (j in i * increment..<(i + 1) * increment) {
                info.myOutput2InputLine[outputStartLine + j] = inputStartLine + i
            }
        }
    }

    @Throws(IOException::class)
    private fun parseFileInfo(currentStratum: StratumInfo, fileInfo: String) {
        var fileInfo = fileInfo
        var filePath: String? = null
        if (fileInfo.startsWith("+ ")) {
            fileInfo = fileInfo.substring(2)
            filePath = myReader.readLine()
        }
        val index = fileInfo.indexOf(' ')
        val id = fileInfo.take(index)
        val fileName = fileInfo.substring(index + 1)
        currentStratum.myFileId2Info[id] = FileInfo(fileName, filePath)
    }

    fun getStratum(id: String?): StratumInfo? {
        var id = id
        if (id == null) {
            id = myDefaultStratum
        }
        var stratumInfo = myId2Stratum[id]
        if (stratumInfo == null) {
            stratumInfo = myId2Stratum[myDefaultStratum]
        }
        return stratumInfo
    }

    class StratumInfo(val stratumId: String) {
        internal val myFileId2Info: MutableMap<String, FileInfo> = hashMapOf()

        val fileInfos: Array<FileInfo> = myFileId2Info.values.toTypedArray<FileInfo>()
    }

    class FileInfo(val name: String?, val path: String?) {
        internal val myOutput2InputLine: BidirectionalMap<Int, Int> = BidirectionalMap()

        fun getOutputLines(inputLine: Int): List<Int>? = myOutput2InputLine.getKeysByValue(inputLine)

        fun getInputLine(outputLine: Int): Int = myOutput2InputLine[outputLine] ?: -1
    }

    companion object {
        private const val SMAP_ID = "SMAP"
        private const val STRATUM_SECTION_PREFIX = "*S "
        private const val END_SECTION_HEADER = "*E"
        private const val FILE_SECTION_HEADER = "*F"
        private const val LINE_SECTION_HEADER = "*L"
        private const val OPEN_EMBEDDED_STRATUM_HEADER = "*O"
        private const val CLOSE_EMBEDDED_STRATUM_HEADER = "*C"

        fun parse(smapReader: Reader): SMAPInfo? {
            val smapInfo = SMAPInfo(smapReader)
            try {
                smapInfo.doParse()
            } catch (e: Exception) {
                return null
            }
            if (smapInfo.myId2Stratum.isEmpty()) {
                return null
            }
            return smapInfo
        }
    }
}

internal class BidirectionalMap<K, V>(
    private val directMap: MutableMap<K, V> = hashMapOf(),
    private val reversedMap: MutableMap<V, MutableList<K>> = hashMapOf()
) : MutableMap<K, V> by directMap {

    override fun clear() {
        directMap.clear()
        reversedMap.clear()
    }

    override fun put(key: K, value: V): V? {
        val oldValue = directMap.put(key, value)
        if (oldValue != null) {
            if (oldValue == value) {
                return oldValue
            }
            val array = reversedMap[oldValue]!!
            array.remove(key)
            if (array.isEmpty()) {
                reversedMap.remove(oldValue)
            }
        }
        return oldValue
    }

    fun removeValue(v: V?) {
        reversedMap.remove(v)?.also {
            it.forEach { key-> directMap.remove(key) }
        }
    }

    override fun remove(key: K): V? {
        val value = directMap.remove(key)
        val ks = reversedMap[value]
        if (ks != null) {
            if (ks.size > 1) {
                ks.remove(key)
            } else {
                reversedMap.remove(value)
            }
        }
        return value
    }

    override fun putAll(from: Map<out K, V>): Unit =
        from.forEach {
            put(it.key, it.value)
        }

    fun getKeysByValue(value: V): List<K>?  = reversedMap[value]
}