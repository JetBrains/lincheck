/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.fixtures

/**
 * JBRes-9243 reproducer: the `object : Function1<...>` body inside [run] is compiled
 * into a separate synthetic JVM class `LambdaFixture$run$1`. A breakpoint set on a
 * line in that body must reach the synthetic descendant, not just the outer class.
 *
 * The fixture lives under `org.jetbrains.kotlinx.lincheck_test.*` (the repo's
 * test-package convention) so `isInLincheckPackage` does not reject it before
 * the live-debugger filter sees the class.
 *
 * The object expression is inlined into the `.map(...)` call so the Kotlin compiler
 * does not append the variable name to the anonymous class name.
 */
internal class LambdaFixture {
    fun run(name: String): String {
        return listOf("Davis", "Franklin").map(object : Function1<String, String> {
            override fun invoke(item: String): String {
                return "$item:$name"
            }
        }).joinToString()
    }
}
