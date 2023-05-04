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

public class IntGen implements ParameterGenerator<Integer> {

    private final ExpandingRangeIntGenerator generator;

    public IntGen(RandomProvider randomProvider, String configuration) {
        generator = ExpandingRangeIntGenerator.create(randomProvider.createRandom(), configuration, Integer.MIN_VALUE, Integer.MAX_VALUE, "int");
    }

    public Integer generate() {
        return generator.nextInt();
    }

    @Override
    public void resetRange() {
        generator.restart();
    }
}
