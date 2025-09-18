/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test.representation.loops;

import org.jetbrains.kotlinx.lincheck_test.representation.BaseTraceRepresentationTest;

public class JavaDoLoopRepresentationTest extends BaseTraceRepresentationTest {
    public Object escape = null;

    public JavaDoLoopRepresentationTest() {
        super("loops/java_do_representation", true);
    }

    @Override
    public void operation() {
        int i = 1;
        escape = "START";
        do {
            Object a = i;
            escape = a.toString();
            i++;
        } while (i < 3);
        escape = "END";
    }
}
