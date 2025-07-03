/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.trace_debugger;

import org.jetbrains.lincheck.datastructures.Operation;

import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * This test is intended to check that {@link MethodHandles} is successfully created even on Java 8.
 * It is necessary for invokedynamic handling in the trace debugger.
 * <p> 
 * However, due to workaround for <a href="https://github.com/JetBrains/lincheck/issues/500">the issue</a>,
 * the tests are actually run on the JDK 17.
 * Nevertheless, when the issue is resolved,
 * the tests will actually check the correct {@link MethodHandles.Lookup} creation.
 */
public class CollectorsTest extends AbstractDeterministicTest {
    @Operation
    public Object operation() {
        return Arrays.stream(
                new List[] {
                    Arrays.stream(new String[]{"Hello ", "w"}).collect(Collectors.toList()),
                    Arrays.stream(new String[]{"Hello ", "w"}).collect(Collectors.toList())
                }
        ).flatMap(Collection::stream).collect(Collectors.toList());
    }
}
