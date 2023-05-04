/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.paramgen;

import org.jetbrains.kotlinx.lincheck.RandomProvider;

public class FloatGen implements ParameterGenerator<Float> {
    private final DoubleGen doubleGen;

    public FloatGen(RandomProvider randomProvider, String configuration) {
        doubleGen = new DoubleGen(randomProvider, configuration);
    }

    public Float generate() {
        return (float) (double) doubleGen.generate();
    }

    @Override
    public void resetRange() {
        doubleGen.resetRange();
    }
}