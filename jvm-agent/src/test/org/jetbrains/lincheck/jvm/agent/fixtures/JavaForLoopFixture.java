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
 * JBRes-9242 reproducer for the for-loop back-edge case. A counting for-loop whose header
 * line carries the initialiser ({@code int i = 0}) and the increment ({@code i++}).
 * {@code javac} emits two {@code LINENUMBER 25} directives, one at the initialiser and
 * one at the increment, sitting in disjoint basic blocks separated by the loop body BB
 * (the back-edge target at the loop condition starts a fresh basic block too).
 * <p>
 *
 * Matches the JDI spec's "a line may have more than one executable location" semantics and
 * the per-iteration breakpoint expectations for for-loop headers.
 */
public final class JavaForLoopFixture {

    public int forLoopShape(int bound) {
        int sum = 0;
        for (int i = 0; i < bound; i++) {
            sum += i;
        }
        return sum;
    }
}
