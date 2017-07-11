package com.devexperts.dxlab.lincheck;

import com.devexperts.dxlab.lincheck.annotations.CTest;
import com.devexperts.dxlab.lincheck.annotations.Operation;
import com.devexperts.dxlab.lincheck.annotations.Reset;
import org.junit.Test;

@CTest(actorsPerThread = {"3:5", "3:5", "3:5"}, iterations = 100, invocationsPerIteration = 10)
public class RunOnceTest {

    private A a;

    @Reset
    public void reload() {
        a = new A();
    }

    @Operation(runOnce = true)
    public void a() {
        a.a();
    }

    @Operation(runOnce = true)
    public void b() {
        a.b();
    }

    @Test
    public void test() {
        LinChecker.check(this);
    }

    class A {
        private boolean a, b;
        synchronized void a() {
            if (a)
                throw new AssertionError();
            a = true;
        }

        synchronized void b() {
            if (b)
                throw new AssertionError();
            b = true;
        }
    }
}
