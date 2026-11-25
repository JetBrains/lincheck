/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test.representation;

public class ArrayCloneRepresentationTest extends BaseTraceRepresentationTest {
    public ArrayCloneRepresentationTest() {
        super("array_clone", true);
    }

    @Override
    public void operation() {
        int[] source = {1};
        int[] clone = source.clone();
        clone[0] = 2;
        if (source[0] != 1 || clone[0] != 2) throw new AssertionError();
    }
}
