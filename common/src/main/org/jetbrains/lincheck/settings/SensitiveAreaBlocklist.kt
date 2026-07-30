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

import org.jetbrains.lincheck.util.Logger
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A reusable, named set of rules describing code areas where breakpoints must never hit
 * (crypto and key handling, authentication, payment processing, compliance-scoped modules, …).
 *
 * A blocklist is organizational policy: it constrains what the debugging toolchain does,
 * it is not a sandbox against someone who controls the JVM command line.
 *
 * **Content-identity contract.** As for [SnapshotBreakpoint], [uuid] identifies the blocklist's
 * *content*, not a stable slot: any change to [name] or [rules] MUST produce a fresh [uuid].
 * [of] derives such a content-identity uuid deterministically, so structurally identical
 * blocklists coming from different sources collapse to the same identity.
 *
 * @property uuid Content-identity identifier; blocklists are compared by it alone.
 * @property name Human-readable label, e.g. "Crypto & key handling".
 * @property rules The rules whose union defines the blocked area.
 */
class SensitiveAreaBlocklist(
    val uuid: UUID,
    val name: String,
    val rules: List<BlocklistRule>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SensitiveAreaBlocklist) return false
        return uuid == other.uuid
    }

    override fun hashCode(): Int = uuid.hashCode()

    override fun toString(): String = "Blocklist \"$name\" #$uuid (${rules.size} rules)"

    /**
     * Encodes this blocklist as a single line for the wire protocol / startup file.
     *
     * Format: `uuid|base64(name)|rule,rule,…`.
     * The name is Base64-encoded so arbitrary label text cannot collide with the separators;
     * each rule is encoded by [BlocklistRule.encodeToString], which uses only `:` internally
     * (never `|`, `,` or a newline), so the three levels never clash.
     */
    fun encodeToString(): String {
        val encodedName = Base64.getEncoder().encodeToString(name.toByteArray(Charsets.UTF_8))
        val encodedRules = rules.joinToString(RULE_SEPARATOR) { it.encodeToString() }
        return listOf(uuid.toString(), encodedName, encodedRules).joinToString(FIELD_SEPARATOR)
    }

    companion object {
        private const val FIELD_SEPARATOR = "|"
        private const val RULE_SEPARATOR = ","

        /**
         * Builds a blocklist with a deterministic content-identity [uuid] derived from [name] and [rules],
         * honoring the content-identity contract without a caller-supplied uuid.
         */
        fun of(name: String, rules: List<BlocklistRule>): SensitiveAreaBlocklist {
            val contentKey = name + "\u0000" + rules.joinToString(RULE_SEPARATOR) { it.encodeToString() }
            val uuid = UUID.nameUUIDFromBytes(contentKey.toByteArray(Charsets.UTF_8))
            return SensitiveAreaBlocklist(uuid, name, rules)
        }

        /**
         * Decodes a blocklist produced by [encodeToString].
         */
        fun decodeFromString(string: String): SensitiveAreaBlocklist {
            // Split into exactly three fields; the rules field may itself be empty.
            val parts = string.split(FIELD_SEPARATOR, limit = 3)
            require(parts.size == 3) { "Malformed blocklist (expected 'uuid|name|rules'): $string" }
            val uuid = UUID.fromString(parts[0])
            val name = String(Base64.getDecoder().decode(parts[1]), Charsets.UTF_8)
            val rules = parts[2]
                .split(RULE_SEPARATOR)
                .filter { it.isNotEmpty() }
                .map { BlocklistRule.decodeFromString(it) }
            return SensitiveAreaBlocklist(uuid, name, rules)
        }
    }
}

/**
 * Encodes a list of blocklists as a newline-separated string.
 * A newline never appears inside a single [SensitiveAreaBlocklist.encodeToString],
 * so this round-trips unambiguously via [decodeBlocklistsFromString].
 */
fun List<SensitiveAreaBlocklist>.encodeToString(): String =
    joinToString("\n") { it.encodeToString() }

/**
 * Decodes a list produced by [List.encodeToString].
 *
 * Per-blocklist error isolation: one undecodable line is logged and skipped, the rest still apply.
 * Without it a single bad line would drop the whole policy push and leave the receiver on stale
 * policy — a worse failure than missing one list. Rules *inside* a blocklist stay all-or-nothing:
 * silently dropping an unparseable rule would weaken that list (fail-open).
 */
fun decodeBlocklistsFromString(string: String): List<SensitiveAreaBlocklist> =
    string.split("\n").filter { it.isNotBlank() }.mapNotNull { line ->
        try {
            SensitiveAreaBlocklist.decodeFromString(line)
        } catch (e: Exception) {
            Logger.error { "Skipping undecodable blocklist line (${e.message}): $line" }
            null
        }
    }

/**
 * A single rule matched against a code location.
 *
 * All matching is name-based: package containment, class name (with its generated `$`-descendants),
 * and method name. Inheritance-aware matching (blocking inheritors / overrides) is not supported.
 */
sealed class BlocklistRule {

    /**
     * A specific method, matched by name (all overloads unless [descriptor] is given).
     *
     * Also covers compiler-generated bodies emitted *from* the method:
     * javac `lambda$<method>$*` synthetic siblings (same class) and
     * kotlinc `<owner>$<method>$*` nested classes.
     */
    class Method(
        val className: String,
        val methodName: String,
        val descriptor: String? = null,
    ) : BlocklistRule() {
        override fun encodeToString(): String =
            listOf(TAG_METHOD, className, methodName, descriptor ?: "")
                .joinToString(RULE_FIELD_SEPARATOR)

        override fun equals(other: Any?): Boolean =
            other is Method && className == other.className && methodName == other.methodName &&
                descriptor == other.descriptor

        override fun hashCode(): Int =
            listOf(className, methodName, descriptor).hashCode()

        override fun toString(): String =
            "Method($className#$methodName${descriptor ?: ""})"
    }

    /**
     * A class, always including its compiler-generated `<owner>$…` descendants
     * (lambdas, inner/anonymous classes, suspend continuations).
     */
    class Class(
        val className: String,
    ) : BlocklistRule() {
        override fun encodeToString(): String =
            listOf(TAG_CLASS, className).joinToString(RULE_FIELD_SEPARATOR)

        override fun equals(other: Any?): Boolean =
            other is Class && className == other.className

        override fun hashCode(): Int = className.hashCode()

        override fun toString(): String = "Class($className)"
    }

    /** A package including all of its subpackages. */
    class Package(
        val packageName: String,
    ) : BlocklistRule() {
        override fun encodeToString(): String =
            listOf(TAG_PACKAGE, packageName).joinToString(RULE_FIELD_SEPARATOR)

        override fun equals(other: Any?): Boolean =
            other is Package && packageName == other.packageName

        override fun hashCode(): Int = packageName.hashCode()

        override fun toString(): String = "Package($packageName)"
    }

    /** Encodes this rule using `:` as the only separator (safe: no rule field contains `:`). */
    abstract fun encodeToString(): String

    /**
     * Returns true if this rule blocks the *whole class* [canonicalClassName].
     * [Package] and [Class] rules are fully decidable this way; a [Method] rule blocks a whole
     * class only when it is the kotlinc nested body class of the rule's method —
     * matching the method itself needs the method name and is done per method.
     */
    fun matchesClassStatically(canonicalClassName: String): Boolean = when (this) {
        is Package -> classInPackage(canonicalClassName, packageName)
        is Class -> classNameOrGeneratedDescendant(canonicalClassName, className)
        is Method -> matchesMethodBodyClass(canonicalClassName)
    }

    /**
     * For a [Method] rule, returns true if [canonicalClassName] is a kotlinc nested class
     * generated as a body of the rule's method (`<owner>$<method>$…`) — the whole such class
     * is part of the sensitive area. Always false for other rule kinds.
     */
    fun matchesMethodBodyClass(canonicalClassName: String): Boolean = when (this) {
        is Method -> canonicalClassName.startsWith("$className\$$methodName\$")
        else -> false
    }

    companion object {
        internal const val RULE_FIELD_SEPARATOR = ":"
        internal const val TAG_METHOD = "M"
        internal const val TAG_CLASS = "C"
        internal const val TAG_PACKAGE = "P"

        fun decodeFromString(string: String): BlocklistRule {
            val parts = string.split(RULE_FIELD_SEPARATOR)
            // Extra trailing fields are tolerated (>=, not ==): a future writer may append rule
            // options, and an older reader must still enforce the rule it understands rather than
            // reject the whole policy push.
            return when (val tag = parts.getOrNull(0)) {
                TAG_METHOD -> {
                    require(parts.size >= 4) { "Malformed Method rule: $string" }
                    Method(
                        className = parts[1],
                        methodName = parts[2],
                        descriptor = parts[3].ifEmpty { null },
                    )
                }
                TAG_CLASS -> {
                    require(parts.size >= 2) { "Malformed Class rule: $string" }
                    Class(className = parts[1])
                }
                TAG_PACKAGE -> {
                    require(parts.size >= 2) { "Malformed Package rule: $string" }
                    Package(packageName = parts[1])
                }
                else -> throw IllegalArgumentException("Unknown blocklist rule tag '$tag' in: $string")
            }
        }
    }
}

/**
 * Segment-aware package containment: `com.corp.crypto` blocks `com.corp.crypto.Aes`
 * and `com.corp.crypto.aes.Cipher`, but not `com.corp.cryptox.Y`.
 */
fun classInPackage(canonicalClassName: String, packageName: String): Boolean =
    canonicalClassName.startsWith("$packageName.")

/**
 * Class-name match: the canonical name itself, or a compiler-generated `<owner>$…` descendant.
 * The `$` boundary keeps `com.Foo` from matching an unrelated `com.FooBar`.
 */
fun classNameOrGeneratedDescendant(canonicalClassName: String, ruleClassName: String): Boolean =
    canonicalClassName == ruleClassName || canonicalClassName.startsWith("$ruleClassName\$")

/**
 * A blocklist rule that matched a code location, together with the owning blocklist's name,
 * so enforcement can tell the user *which* policy rejected their breakpoint.
 */
data class BlockMatch(val blocklistName: String, val rule: BlocklistRule) {
    val reason: String get() = "blocked by blocklist \"$blocklistName\", rule $rule"
}

/**
 * Thread-safe registry of the active blocklists.
 *
 * **Add-only.** Blocklists can only be added at runtime, never removed or replaced —
 * the effective policy only ever tightens; relaxing it requires an agent restart with a new
 * configuration. Additions are idempotent per content-identity [SensitiveAreaBlocklist.uuid],
 * so a reconnecting source re-pushing the same policy is a no-op.
 * The effective rule set is the union of every added blocklist; there are no allow-exceptions.
 */
class SensitiveAreaBlocklistRegistry {
    private val byUuid = ConcurrentHashMap<UUID, SensitiveAreaBlocklist>()

    /** Adds the given blocklists; already-present ones (same content-identity uuid) are skipped. */
    fun add(blocklists: List<SensitiveAreaBlocklist>) {
        for (blocklist in blocklists) {
            val existing = byUuid.putIfAbsent(blocklist.uuid, blocklist)
            // Same uuid with different content violates the content-identity contract
            // (a sender must derive a fresh uuid for edited content). Keep the first
            // registration deterministically, but never drop a policy update silently.
            if (existing != null && (existing.name != blocklist.name || existing.rules != blocklist.rules)) {
                Logger.warn {
                    "Ignoring blocklist ${blocklist.uuid} with content differing from the already-registered one " +
                        "(content-identity contract violation); keeping: $existing"
                }
            }
        }
    }

    /** All active blocklists. */
    fun all(): List<SensitiveAreaBlocklist> = byUuid.values.toList()

    /** The union of all active rules. */
    fun allRules(): List<BlocklistRule> = byUuid.values.flatMap { it.rules }

    /** True when no active blocklist has any rule. */
    fun isEmpty(): Boolean = byUuid.values.all { it.rules.isEmpty() }

    /**
     * Whole-class check: the first rule that blocks [canonicalClassName], or `null` if none does.
     * Used both at registration time (Stage 1) and by the instrumentation-time engine.
     * Per-method rules are not decidable from the class name alone, so a `null` result is
     * *not* an approval — the instrumentation stage is authoritative.
     */
    fun staticBlockMatch(canonicalClassName: String): BlockMatch? {
        for (blocklist in all()) {
            val rule = blocklist.rules.firstOrNull { it.matchesClassStatically(canonicalClassName) }
            if (rule != null) return BlockMatch(blocklist.name, rule)
        }
        return null
    }
}
