/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.representation;

import org.jetbrains.lincheck.datastructures.ModelCheckingOptions;
import java.util.HashMap;

public class JavaLambdaRepresentationTest extends BaseTraceRepresentationTest {
    public JavaLambdaRepresentationTest() {
        super("java_lambda_argument_representation", true);
    }
    
    private HashMap<Integer, Integer> hm = new HashMap<>();
    
    @Override
    public void operation() {
        hm.computeIfAbsent(1, k -> 1);
    }

    @Override
    public void customize(ModelCheckingOptions $this$customize) {
        $this$customize.analyzeStdLib$lincheck(false);
    }
}