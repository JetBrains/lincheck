/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.transformers;

public class MethodCallInterceptionFixture {
    public int calls;
    public final RuntimeException failure = new RuntimeException("original");

    public int intResult() { calls++; return 1; }
    public long longResult() { calls++; return 2L; }
    public double doubleResult() { calls++; return 3.0; }
    public Object objectResult() { calls++; return "original"; }
    public void voidResult() { calls++; }
    public int throwingResult() { calls++; throw failure; }

    public int callInt() { return intResult(); }
    public long callLong() { return longResult(); }
    public double callDouble() { return doubleResult(); }
    public Object callObject() { return objectResult(); }
    public void callVoid() { voidResult(); }
    public int callThrowing() { return throwingResult(); }
    public int callNested() { return callInt(); }
    public Object callConstructor() { return new StringBuilder(); }
}
