/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.litmus;

// javac uses an array owner for clone(); Kotlin uses java.lang.Object instead.
public class ArrayCloning {
    public enum Color { RED, GREEN }

    public static int[] clone(int[] source) {
        return source.clone();
    }
}
