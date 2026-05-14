/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.fixtures

import java.util.Optional

/**
 * Kotlin counterpart of [JavaChainedCallShapeFixture]. Lambda-free chained call broken
 * across source lines: `Optional.ofNullable(id).orElse(Any())` reached through an
 * `if/else` whose false-branch is the chained call. `kotlinc` emits at least two
 * `LINENUMBER` directives for the chained-call line, separated only by an intermediate
 * anchor / unrelated line — both directives land in the same basic block, so the
 * basic-block same-line dedup collapses them to a single hook.
 *
 * The fixture deliberately omits the lambda body that [KotlinChainedCallFixture]
 * exercises, so the same-basic-block dedup behavior can be asserted independently of the
 * lambda-shadow skip.
 */
class KotlinChainedCallShapeFixture {

    fun chainedCall(id: Int?): Any =
        if (id == null) Any()
        else Optional.ofNullable<Any>(id)
            .orElse(Any())
}
