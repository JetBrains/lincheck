/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2021 JetBrains s.r.o.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck.test.transformation;

import org.jetbrains.kotlinx.lincheck.LinChecker;
import org.jetbrains.kotlinx.lincheck.annotations.Operation;
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
