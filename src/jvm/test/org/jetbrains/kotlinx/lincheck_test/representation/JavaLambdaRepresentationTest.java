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

public class JavaLambdaRepresentationTest extends BaseTraceRepresentationTest {
    public JavaLambdaRepresentationTest() {
        super("java_lambda_argument_representation");
    }
    
    @Override
    public void operation() {
        throw new IllegalStateException("Bla");
    }
}