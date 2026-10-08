/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.expressions.fixtures;

public class InheritedExpressionTarget extends InheritedExpressionBase {
    public int count = 10;

    private int token() {
        return 2;
    }

    private int code() {
        return 7;
    }
}
