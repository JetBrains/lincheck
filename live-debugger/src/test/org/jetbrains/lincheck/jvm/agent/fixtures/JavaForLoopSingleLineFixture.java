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
 * Single-line counting {@code for} loop. {@code javac} emits only ONE {@code LINENUMBER}
 * for the entire loop construct — init, header, body, and increment all share the same
 * source line, so the multi-line for-loop's two-{@code LINENUMBER}-header pattern doesn't
 * occur here.
 */
public final class JavaForLoopSingleLineFixture {

    public int forLoopSingleLine(int bound) {
        int s = 0;
        for (int i = 0; i < bound; i++) { s += i; }
        return s;
    }
}
