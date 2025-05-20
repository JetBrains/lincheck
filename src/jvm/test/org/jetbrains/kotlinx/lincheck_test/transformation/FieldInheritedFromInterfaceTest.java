/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.transformation;

import org.jetbrains.kotlinx.lincheck.LinChecker;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingCTest;
import org.junit.Test;

@ModelCheckingCTest(iterations = 1)
public class FieldInheritedFromInterfaceTest implements InterfaceWithField {
    @Operation
    public int get() { return INTERFACE_CONSTANT.getValue(); }

    @Test
    public void test() {
        new LinChecker(FieldInheritedFromInterfaceTest.class, null).check();
    }
}

interface InterfaceWithField {
    public static final ValueHolder INTERFACE_CONSTANT = new ValueHolder(6);
}
