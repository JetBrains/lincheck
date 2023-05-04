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

import org.jetbrains.kotlinx.lincheck.annotations.Operation;

/**
 * The implementation of this interface is used to generate parameters
 * for {@link Operation operation}.
  */
public interface ParameterGenerator<T> {
    T generate();

    void resetRange();

    final class Dummy implements ParameterGenerator<Object> {
        @Override
        public Object generate() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void resetRange() {
        }
    }
}
