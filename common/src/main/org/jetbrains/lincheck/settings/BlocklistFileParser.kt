/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.settings

import java.io.File

/**
 * Parses [SensitiveAreaBlocklist]s from an INI configuration file (the agent `blocklistFile=` argument).
 *
 * Unlike [BreakpointsFileParser], rule keys are **repeatable** within a section — a blocklist
 * typically has many `package` / `class` / `method` lines.
 *
 * Expected file format:
 * ```
 * # comments are allowed
 * ; this is also a comment
 *
 * [Blocklist Crypto]
 * name = Crypto & key handling
 * package = com.corp.crypto
 * class = com.corp.KeyStore
 * method = com.corp.Auth#login
 * method = com.corp.Auth#login:(Ljava/lang/String;)V
 * ```
 *
 * Method values are `<className>#<methodName>` with an optional `:<descriptor>` suffix
 * (a `null` descriptor matches all overloads).
 *
 * The blocklist identity is always the content-derived uuid ([SensitiveAreaBlocklist.of]);
 * there is deliberately no `uuid` key — a pinned uuid would let an edited blocklist collide with
 * its previous version in the add-only registry and be dropped.
 */
object BlocklistFileParser {

    private val SECTION_NAME_REGEX = Regex("Blocklist .+")

    private const val KEY_NAME = "name"
    private const val KEY_PACKAGE = "package"
    private const val KEY_CLASS = "class"
    private const val KEY_METHOD = "method"

    private const val METHOD_CLASS_SEPARATOR = "#"
    private const val METHOD_DESCRIPTOR_SEPARATOR = ":"

    /**
     * Parses blocklists from an INI file.
     *
     * @param filePath path to the INI file containing blocklist definitions.
     * @return the parsed [SensitiveAreaBlocklist]s, one per `[Blocklist …]` section.
     */
    fun parseBlocklistsFile(filePath: String): List<SensitiveAreaBlocklist> {
        val file = File(filePath)
        check(file.exists()) { "Blocklist file not found: $filePath" }
        check(file.canRead()) { "Cannot read blocklist file: $filePath" }

        return parseBlocklists(file.readText())
    }

    /** Parses blocklists from raw INI [content]. */
    fun parseBlocklists(content: String): List<SensitiveAreaBlocklist> {
        val sections = parseIniSections(content)
        return sections.map { (sectionName, entries) ->
            try {
                convertToBlocklist(sectionName, entries)
            } catch (e: Exception) {
                throw IllegalArgumentException("Failed to process blocklist in section [$sectionName]: ${e.message}", e)
            }
        }
    }

    /** Parses INI content into (section name, ordered key-value entries) pairs, preserving repeated keys. */
    private fun parseIniSections(content: String): List<Pair<String, List<Pair<String, String>>>> {
        val sections = mutableListOf<Pair<String, MutableList<Pair<String, String>>>>()
        var currentSection: Pair<String, MutableList<Pair<String, String>>>? = null

        for (line in content.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) continue

            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                val sectionName = trimmed.substring(1, trimmed.length - 1).trim()
                require(SECTION_NAME_REGEX.matches(sectionName)) {
                    "Invalid section header: '$sectionName' (expected 'Blocklist <id>')"
                }
                currentSection = sectionName to mutableListOf()
                sections.add(currentSection)
                continue
            }

            val eqIndex = trimmed.indexOf('=')
            require(eqIndex >= 0) { "Invalid line (expected 'key = value'): $trimmed" }
            val key = trimmed.substring(0, eqIndex).trim()
            val value = trimmed.substring(eqIndex + 1).trim()
            val section = checkNotNull(currentSection) { "Property '$key' found outside of any section" }
            section.second.add(key to value)
        }
        return sections
    }

    private fun convertToBlocklist(
        sectionName: String,
        entries: List<Pair<String, String>>,
    ): SensitiveAreaBlocklist {
        var name: String? = null
        val rules = mutableListOf<BlocklistRule>()

        for ((key, value) in entries) {
            when (key) {
                KEY_NAME -> name = value
                KEY_PACKAGE -> {
                    require(value.isNotBlank()) { "Empty package name" }
                    rules.add(BlocklistRule.Package(value))
                }
                KEY_CLASS -> rules.add(parseClassRule(value))
                KEY_METHOD -> rules.add(parseMethodRule(value))
                else -> throw IllegalArgumentException("Unknown blocklist property '$key'")
            }
        }

        // The section id after "Blocklist " is the default display name when `name` is absent.
        val effectiveName = name ?: sectionName.removePrefix("Blocklist ").trim()
        return SensitiveAreaBlocklist.of(effectiveName, rules)
    }

    private fun parseClassRule(value: String): BlocklistRule.Class {
        require(value.isNotBlank()) { "Empty class name" }
        return BlocklistRule.Class(value)
    }

    private fun parseMethodRule(value: String): BlocklistRule.Method {
        val hashIndex = value.indexOf(METHOD_CLASS_SEPARATOR)
        require(hashIndex > 0) {
            "Invalid method rule '$value' (expected '<className>#<methodName>' or '<className>#<methodName>:<descriptor>')"
        }
        val className = value.substring(0, hashIndex)
        val rest = value.substring(hashIndex + 1)

        val descriptorIndex = rest.indexOf(METHOD_DESCRIPTOR_SEPARATOR)
        val methodName: String
        val descriptor: String?
        if (descriptorIndex >= 0) {
            methodName = rest.substring(0, descriptorIndex)
            descriptor = rest.substring(descriptorIndex + 1).ifBlank { null }
        } else {
            methodName = rest
            descriptor = null
        }
        require(methodName.isNotBlank()) { "Empty method name in method rule '$value'" }
        return BlocklistRule.Method(className, methodName, descriptor)
    }
}
