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

/** Multi-line {@code if/else}. {@code javac} emits one LINENUMBER per branch body. */
public final class JavaIfElseMultiLineFixture {

    public int ifElseMultiLine(int x) {
        int s;
        if (x > 0) {
            s = 1;
        } else {
            s = 2;
        }
        return s;
    }
}
