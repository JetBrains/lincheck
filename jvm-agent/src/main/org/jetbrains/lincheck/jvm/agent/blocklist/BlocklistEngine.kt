/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.blocklist

import org.jetbrains.lincheck.settings.BlockMatch
import org.jetbrains.lincheck.settings.BlocklistRule
import org.jetbrains.lincheck.settings.SensitiveAreaBlocklistRegistry

/**
 * The authoritative blocklist matcher on the agent side.
 *
 * All matching is name-based (package / class / method rules in the [registry]);
 * inheritance-aware matching (blocking inheritors / overrides) is not supported.
 *
 * Two granularities, mirroring §5's Stage 2:
 *  - [classBlock] — is the *whole class* a blocked area? (package and class rules,
 *    plus kotlinc method-body nested classes). Used at class level to skip injecting for the class entirely.
 *  - [methodBlock] — is this specific *method* blocked? (method rules: the method itself
 *    and its javac `lambda$…` siblings). Used per method.
 *
 * Stack-frame checks (dynamic-extent / Stage 3) ask the same questions; a frame carries no method
 * descriptor, so descriptor-pinned method rules are matched against every overload via a
 * `null` descriptor.
 */
class BlocklistEngine(private val registry: SensitiveAreaBlocklistRegistry) {

    /** True when no rule is active — the fast path that skips all blocklist work. */
    fun isEmpty(): Boolean = registry.isEmpty()

    /** Returns the match if the whole class [canonicalClassName] is a blocked area, else `null`. */
    fun classBlock(canonicalClassName: String): BlockMatch? =
        registry.staticBlockMatch(canonicalClassName)

    /**
     * Returns the match if the method `[canonicalClassName].[methodName][descriptor]` is blocked, else `null`.
     * A `null` [descriptor] (a stack frame carries none) matches descriptor-pinned rules against
     * every overload. Call only for classes that [classBlock] did not already block.
     */
    fun methodBlock(
        canonicalClassName: String,
        methodName: String,
        descriptor: String?,
    ): BlockMatch? {
        for (blocklist in registry.all()) {
            for (rule in blocklist.rules) {
                if (rule is BlocklistRule.Method &&
                    methodBlockedByRule(canonicalClassName, methodName, descriptor, rule)
                ) {
                    return BlockMatch(blocklist.name, rule)
                }
            }
        }
        return null
    }

    /**
     * True when some method rule owns methods of [canonicalClassName], i.e. names the class itself.
     * Classifies frame classes that need per-method checks.
     */
    fun classOwnsMethodRules(canonicalClassName: String): Boolean =
        registry.allRules().any { it is BlocklistRule.Method && it.className == canonicalClassName }

    private fun methodBlockedByRule(
        className: String,
        methodName: String,
        descriptor: String?,
        rule: BlocklistRule.Method,
    ): Boolean {
        if (className != rule.className) return false
        // The method itself. A null descriptor means the caller has no descriptor to offer
        // (a stack frame) — a descriptor-pinned rule then matches all overloads, fail-closed.
        if (methodName == rule.methodName &&
            (rule.descriptor == null || descriptor == null || rule.descriptor == descriptor)
        ) {
            return true
        }
        // javac lambda body emitted from the method — a synthetic sibling in the same class.
        // Its descriptor differs from the method's, so descriptor pinning does not apply here.
        if (methodName.startsWith("lambda\$${rule.methodName}\$")) return true
        return false
    }
}
