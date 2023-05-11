/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.representation;

import org.jetbrains.kotlinx.lincheck.LinChecker;
import org.jetbrains.kotlinx.lincheck.LincheckAssertionError;
import org.jetbrains.kotlinx.lincheck.annotations.Operation;
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions;
import org.junit.Test;

import static org.jetbrains.kotlinx.lincheck_test.util.TestUtilsKt.getExpectedLogFromResources;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

@SuppressWarnings({"unused"})
public class CustomScenarioTestSuggestionInOutputJavaTest {

    private boolean canEnterForbiddenSection = false;

    @Operation
    public int operation1(byte byteValue, String stringValue, short shortValue) {
        canEnterForbiddenSection = true;
        canEnterForbiddenSection = false;
        return 1;
    }


    @Operation(handleExceptionsAsResult = {IllegalStateException.class})
    public void operation2(int intValue, double doubleValue, float floatValue, boolean booleanValue, long longValue) {
        if (canEnterForbiddenSection) {
            throw new IllegalStateException("expected exception");
        }
    }

    @Operation
    public void operationNoArguments() {
    }

    @Test
    public void test() {
        var options = new ModelCheckingOptions();

        options.iterations(30);
        options.actorsBefore(2);
        options.threads(3);
        options.actorsPerThread(2);
        options.actorsAfter(2);
        options.minimizeFailedScenario(false);

        String actualOutput = null;
        try {
            new LinChecker(this.getClass(), options).check();
        } catch (LincheckAssertionError error) {
            actualOutput = error.getMessage();
        }

        assertNotNull("test should fail", actualOutput);

        String expectedOutput = getExpectedLogFromResources("suggested_custom_scenario_in_java.txt");

        assertEquals(expectedOutput, actualOutput);
    }


}
