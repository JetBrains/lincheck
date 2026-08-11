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

import com.google.re2j.Pattern
import java.io.File
import java.util.UUID

/**
 * A named, content-addressed set of capture-time data-redaction rules.
 *
 * Rules are ordered. All configured templates combine by union. Capture checks name rules before
 * value rules for each slot; within each phase, the first ordered match supplies the safe
 * attribution displayed to the user.
 */
class RedactionTemplate(
    val uuid: UUID,
    val name: String,
    rules: List<RedactionRule>,
) {
    val rules: List<RedactionRule> = rules.toList()

    init {
        require(name.isNotBlank()) { "Redaction template name must not be blank" }
        require(name.length <= MAX_NAME_LENGTH) {
            "Redaction template name must not exceed $MAX_NAME_LENGTH characters"
        }
        require('\n' !in name && '\r' !in name) {
            "Redaction template name must be a single line"
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is RedactionTemplate && uuid == other.uuid

    override fun hashCode(): Int = uuid.hashCode()

    override fun toString(): String = "Redaction template \"$name\" #$uuid (${rules.size} rules)"

    companion object {
        /** Maximum template label length carried in a redaction marker. */
        const val MAX_NAME_LENGTH = 128

        /** Builds a template whose UUID is derived from its name and ordered rules. */
        fun of(name: String, rules: List<RedactionRule>): RedactionTemplate {
            val content = canonicalTemplateContent(name, rules)
            return RedactionTemplate(UUID.nameUUIDFromBytes(content.toByteArray(Charsets.UTF_8)), name, rules.toList())
        }
    }
}

/** A rule that replaces either a named slot or a scalar value with a redaction marker. */
sealed class RedactionRule {
    /**
     * Matches a captured slot name using a case-insensitive `*` glob.
     *
     * At most one scope may be set. A simple class scope matches that simple name in any package.
     * A qualified class scope includes generated/nested `$` descendants. Package scope includes
     * subpackages and observes package-segment boundaries.
     */
    data class ByName(
        val variableGlob: String,
        val className: String? = null,
        val packageName: String? = null,
    ) : RedactionRule() {
        init {
            require(variableGlob.isNotBlank()) { "Variable glob must not be blank" }
            require(className == null || packageName == null) {
                "A name rule cannot have both class and package scope"
            }
            require(className == null || className.isNotBlank()) { "Class scope must not be blank" }
            require(packageName == null || packageName.isNotBlank()) { "Package scope must not be blank" }
        }
    }

    /** Matches the bounded textual representation of a scalar using RE2/J `find()` semantics. */
    data class ByValue(val pattern: String) : RedactionRule() {
        init {
            require(pattern.isNotBlank()) { "Value regex must not be blank" }
        }
    }
}

/** The safe policy metadata associated with a redaction decision. */
data class RedactionMatch(val templateUuid: UUID, val templateName: String)

/**
 * Identifies one independently replaceable policy contribution.
 *
 * Startup-file and control-plane policies combine by union and are replaced independently.
 */
enum class PolicyOwner { STARTUP_FILE, CONTROL_PLANE }

/** Immutable, precompiled redaction policy used for one capture operation. */
class CompiledRedactionPolicy internal constructor(
    private val compiledTemplates: List<CompiledTemplate>,
) {
    val hasNameRules: Boolean =
        compiledTemplates.any { template -> template.rules.any { it is CompiledRule.ByName } }

    val isEmpty: Boolean get() = compiledTemplates.all { it.rules.isEmpty() }

    /** Returns the first name-rule match, or `null` when this slot name is permitted. */
    fun matchName(variableName: String, declaringClassName: String?): RedactionMatch? {
        for (template in compiledTemplates) {
            for (rule in template.rules) {
                if (rule is CompiledRule.ByName && rule.matches(variableName, declaringClassName)) {
                    return template.match
                }
            }
        }
        return null
    }

    /** Returns the first value-rule match using RE2/J `find()` semantics. */
    fun matchValue(boundedValue: String): RedactionMatch? {
        for (template in compiledTemplates) {
            for (rule in template.rules) {
                if (rule is CompiledRule.ByValue && rule.pattern.matcher(boundedValue).find()) {
                    return template.match
                }
            }
        }
        return null
    }

    companion object {
        val EMPTY = CompiledRedactionPolicy(emptyList())

        internal fun compile(templates: List<RedactionTemplate>): CompiledRedactionPolicy =
            if (templates.all { it.rules.isEmpty() }) EMPTY
            else CompiledRedactionPolicy(templates.map(::compileTemplate))
    }
}

/** Thread-safe, owner-partitioned registry of immutable redaction policies. */
class RedactionTemplateRegistry {
    private val byOwner = linkedMapOf<PolicyOwner, List<RedactionTemplate>>()
    @Volatile
    private var effectivePolicy: CompiledRedactionPolicy = CompiledRedactionPolicy.EMPTY

    /** Atomically replaces all templates belonging to [owner]. */
    @Synchronized
    fun replace(owner: PolicyOwner, templates: List<RedactionTemplate>) {
        val prospective = LinkedHashMap(byOwner)
        if (templates.isEmpty()) prospective.remove(owner) else prospective[owner] = templates.toList()
        val compiled = CompiledRedactionPolicy.compile(orderedTemplates(prospective))
        byOwner.clear()
        byOwner.putAll(prospective)
        effectivePolicy = compiled
    }

    /** Returns the cached immutable policy in one lock-free volatile read. */
    fun snapshot(): CompiledRedactionPolicy = effectivePolicy

    @Synchronized
    fun all(): List<RedactionTemplate> = orderedTemplates(byOwner)

    private fun orderedTemplates(
        owners: Map<PolicyOwner, List<RedactionTemplate>>,
    ): List<RedactionTemplate> = owners.entries
        .sortedBy { it.key.ordinal }
        .flatMap { it.value }
}

/**
 * Strict parser and canonical writer for redaction-template INI files.
 *
 * Repeatable rule keys retain their file order. Malformed UTF-8, unknown properties, duplicate
 * metadata, invalid UUIDs, UUID/content mismatches, and unsupported RE2 expressions reject the
 * complete file.
 */
object RedactionFileParser {
    private val sectionNameRegex = Regex("Redaction .+")

    private const val KEY_NAME = "name"
    private const val KEY_UUID = "uuid"
    private const val KEY_VARIABLE = "variable"
    private const val KEY_CLASS_VARIABLE = "classVariable"
    private const val KEY_PACKAGE_VARIABLE = "packageVariable"
    private const val KEY_VALUE_REGEX = "valueRegex"
    private const val SCOPE_SEPARATOR = "#"

    fun parseTemplatesFile(filePath: String): List<RedactionTemplate> {
        val file = File(filePath)
        check(file.exists()) { "Redaction file not found: $filePath" }
        check(file.canRead()) { "Cannot read redaction file: $filePath" }
        return parseTemplates(
            decodeStrictUtf8(file.readBytes(), "Redaction file '$filePath'"),
        )
    }

    fun parseTemplates(content: String): List<RedactionTemplate> {
        val sections = parseIniSections(content)
        return sections.map { (sectionName, entries) ->
            try {
                convertToTemplate(sectionName, entries)
            } catch (e: Exception) {
                throw IllegalArgumentException(
                    "Failed to process redaction template in section [$sectionName]: ${e.message}",
                    e,
                )
            }
        }
    }

    /** Renders templates as deterministic plain UTF-8 INI content. */
    fun renderTemplates(templates: List<RedactionTemplate>): String =
        templates.mapIndexed { index, template ->
            buildString {
                appendLine("[Redaction ${index + 1}]")
                appendLine("$KEY_NAME = ${template.name}")
                appendLine("$KEY_UUID = ${template.uuid}")
                for (rule in template.rules) {
                    when (rule) {
                        is RedactionRule.ByName -> when {
                            rule.className != null ->
                                appendLine("$KEY_CLASS_VARIABLE = ${rule.className}$SCOPE_SEPARATOR${rule.variableGlob}")
                            rule.packageName != null ->
                                appendLine("$KEY_PACKAGE_VARIABLE = ${rule.packageName}$SCOPE_SEPARATOR${rule.variableGlob}")
                            else -> appendLine("$KEY_VARIABLE = ${rule.variableGlob}")
                        }
                        is RedactionRule.ByValue -> appendLine("$KEY_VALUE_REGEX = ${rule.pattern}")
                    }
                }
            }.trimEnd()
        }.joinToString("\n\n", postfix = if (templates.isEmpty()) "" else "\n")

    private fun parseIniSections(content: String): List<Pair<String, List<Pair<String, String>>>> {
        val sections = mutableListOf<Pair<String, MutableList<Pair<String, String>>>>()
        var currentSection: Pair<String, MutableList<Pair<String, String>>>? = null
        val seenSections = mutableSetOf<String>()

        for ((lineIndex, line) in content.lines().withIndex()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) continue
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                val sectionName = trimmed.substring(1, trimmed.length - 1).trim()
                require(sectionNameRegex.matches(sectionName)) {
                    "Invalid section header: '$sectionName' (expected 'Redaction <id>')"
                }
                require(seenSections.add(sectionName)) { "Duplicate section [$sectionName]" }
                currentSection = sectionName to mutableListOf()
                sections.add(currentSection)
                continue
            }

            val eqIndex = trimmed.indexOf('=')
            require(eqIndex >= 0) { "Invalid line ${lineIndex + 1} (expected 'key = value'): $trimmed" }
            val key = trimmed.substring(0, eqIndex).trim()
            val value = trimmed.substring(eqIndex + 1).trim()
            val section = checkNotNull(currentSection) { "Property '$key' found outside of any section" }
            section.second.add(key to value)
        }
        return sections
    }

    private fun convertToTemplate(
        sectionName: String,
        entries: List<Pair<String, String>>,
    ): RedactionTemplate {
        var name: String? = null
        var configuredUuid: UUID? = null
        var nameSeen = false
        var uuidSeen = false
        val rules = mutableListOf<RedactionRule>()

        for ((key, value) in entries) {
            when (key) {
                KEY_NAME -> {
                    require(!nameSeen) { "Duplicate metadata property '$KEY_NAME'" }
                    require(value.isNotBlank()) { "Redaction template name must not be blank" }
                    nameSeen = true
                    name = value
                }
                KEY_UUID -> {
                    require(!uuidSeen) { "Duplicate metadata property '$KEY_UUID'" }
                    uuidSeen = true
                    configuredUuid = try {
                        UUID.fromString(value)
                    } catch (e: IllegalArgumentException) {
                        throw IllegalArgumentException("Invalid UUID: '$value'", e)
                    }
                }
                KEY_VARIABLE -> rules += RedactionRule.ByName(value)
                KEY_CLASS_VARIABLE -> rules += parseScopedNameRule(value, classScope = true)
                KEY_PACKAGE_VARIABLE -> rules += parseScopedNameRule(value, classScope = false)
                KEY_VALUE_REGEX -> rules += RedactionRule.ByValue(value)
                else -> throw IllegalArgumentException("Unknown redaction property '$key'")
            }
        }

        val effectiveName = name ?: sectionName.removePrefix("Redaction ").trim()
        val derived = RedactionTemplate.of(effectiveName, rules)
        CompiledRedactionPolicy.compile(listOf(derived))
        require(configuredUuid == null || configuredUuid == derived.uuid) {
            "UUID $configuredUuid does not match content-derived UUID ${derived.uuid}"
        }
        return derived
    }

    private fun parseScopedNameRule(value: String, classScope: Boolean): RedactionRule.ByName {
        val separator = value.indexOf(SCOPE_SEPARATOR)
        require(separator > 0 && separator < value.lastIndex) {
            "Invalid scoped variable rule '$value' (expected '<scope>#<variableGlob>')"
        }
        val scope = value.substring(0, separator)
        val glob = value.substring(separator + 1)
        return if (classScope) {
            RedactionRule.ByName(glob, className = scope)
        } else {
            RedactionRule.ByName(glob, packageName = scope)
        }
    }
}

internal sealed class CompiledRule {
    class ByName(
        private val globRegex: Pattern,
        private val className: String?,
        private val packageName: String?,
    ) : CompiledRule() {
        fun matches(variableName: String, declaringClassName: String?): Boolean {
            if (!globRegex.matcher(variableName).matches()) return false
            return when {
                className != null ->
                    declaringClassName != null &&
                        classScopeMatches(declaringClassName, className)
                packageName != null ->
                    declaringClassName != null && classInPackage(declaringClassName, packageName)
                else -> true
            }
        }
    }

    class ByValue(val pattern: Pattern) : CompiledRule()
}

private fun classScopeMatches(declaringClassName: String, configuredClassName: String): Boolean =
    if ('.' in configuredClassName) {
        classNameOrGeneratedDescendant(declaringClassName, configuredClassName)
    } else {
        declaringClassName.substringAfterLast('.') == configuredClassName
    }

internal data class CompiledTemplate(
    val match: RedactionMatch,
    val rules: List<CompiledRule>,
)

private fun compileTemplate(template: RedactionTemplate): CompiledTemplate =
    CompiledTemplate(
        RedactionMatch(template.uuid, template.name),
        template.rules.map { rule ->
            when (rule) {
                is RedactionRule.ByName -> CompiledRule.ByName(
                    globRegex = compileGlob(rule.variableGlob),
                    className = rule.className,
                    packageName = rule.packageName,
                )
                is RedactionRule.ByValue -> CompiledRule.ByValue(compileValuePattern(rule.pattern))
            }
        },
    )

private fun compileGlob(glob: String): Pattern {
    val regex = buildString {
        append("(?i)^")
        var literalStart = 0
        for (index in glob.indices) {
            if (glob[index] != '*') continue
            if (index > literalStart) append(Pattern.quote(glob.substring(literalStart, index)))
            append(".*")
            literalStart = index + 1
        }
        if (literalStart < glob.length) append(Pattern.quote(glob.substring(literalStart)))
        append('$')
    }
    return Pattern.compile(regex)
}

private fun compileValuePattern(pattern: String): Pattern =
    try {
        Pattern.compile(pattern)
    } catch (e: RuntimeException) {
        throw IllegalArgumentException("Unsupported RE2 value regex '$pattern': ${e.message}", e)
    }

private fun canonicalTemplateContent(name: String, rules: List<RedactionRule>): String =
    buildString {
        append(name)
        for (rule in rules) {
            append('\u0000')
            when (rule) {
                is RedactionRule.ByName -> {
                    append("N\u0000")
                    append(rule.variableGlob)
                    append('\u0000')
                    append(rule.className.orEmpty())
                    append('\u0000')
                    append(rule.packageName.orEmpty())
                }
                is RedactionRule.ByValue -> {
                    append("V\u0000")
                    append(rule.pattern)
                }
            }
        }
    }
