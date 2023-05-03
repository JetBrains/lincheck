package org.jetbrains.kotlinx.lincheck.paramgen;

/*
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2015 - 2018 Devexperts, LLC
 * %%
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */

import org.jetbrains.kotlinx.lincheck.RandomProvider;

public class ShortGen extends ParameterGenerator<Short> {
    private final ExpandingRangeIntGenerator generator;

    public ShortGen(RandomProvider randomProvider, String configuration) {
        super(randomProvider, configuration);
        generator = ExpandingRangeIntGenerator.create(randomProvider.createRandom(), configuration, Byte.MIN_VALUE, Byte.MAX_VALUE, "byte");
    }

    public Short generate() {
        return (short) generator.nextInt();
    }
}
