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
 * Multi-line {@code while} loop. Unlike a counting {@code for} loop, {@code javac} emits
 * only ONE {@code LINENUMBER} for the {@code while}-header line (no init/increment to
 * duplicate); the body gets its own LINENUMBER on its own line.
 */
public final class JavaWhileLoopMultiLineFixture {

    public int whileLoopMultiLine(int bound) {
        int i = 0;
        while (i < bound) {
            i++;
        }
        return i;
    }
}
