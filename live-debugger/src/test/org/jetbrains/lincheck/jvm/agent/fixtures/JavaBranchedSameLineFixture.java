/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.fixtures;

/**
 * JBRes-9242 reproducer for the mutually-exclusive-branches case. A for-loop with an
 * inline {@code continue} for odd values forces the increment {@code i++} into its own
 * basic block, reachable both from the body fall-through and from the {@code continue}'s
 * forward jump. {@code javac} emits two {@code LINENUMBER 26} directives — one at the
 * initializer and one at the increment — landing in disjoint basic blocks separated by
 * the body's branching code.
 * <p>
 *
 * Matches JDI's "a line may have more than one executable location" semantics for disjoint executable locations.
 * <p>
 *
 * This is the same dedup-doesn't-over-collapse invariant exercised by
 * {@link JavaForLoopFixture}, varied to use a {@code continue} so the increment's basic
 * block has multiple predecessors instead of a single fall-through edge.
 */
public final class JavaBranchedSameLineFixture {

    public int branchedSameLine(int bound) {
        int sum = 0;
        for (int i = 0; i < bound; i++) {
            if (i % 2 == 0) continue;
            sum += i;
        }
        return sum;
    }
}
