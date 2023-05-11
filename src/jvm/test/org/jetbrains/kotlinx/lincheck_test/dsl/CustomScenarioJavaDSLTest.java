/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.dsl;

import org.jetbrains.kotlinx.lincheck.Actor;
import org.jetbrains.kotlinx.lincheck.annotations.Operation;
import org.jetbrains.kotlinx.lincheck.dsl.ScenarioBuilder;
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.List;

import static java.util.Collections.emptyList;
import static org.jetbrains.kotlinx.lincheck.dsl.ScenarioBuilder.actor;
import static org.jetbrains.kotlinx.lincheck.dsl.ScenarioBuilder.thread;
import static org.jetbrains.kotlinx.lincheck_test.util.TestUtilsKt.assertScenariosEquals;
import static org.jetbrains.kotlinx.lincheck_test.util.TestUtilsKt.getMethod;
import static org.junit.Assert.assertEquals;

@SuppressWarnings("unused")
public class CustomScenarioJavaDSLTest {

    private static final Method intOperation = getMethod(CustomScenarioJavaDSLTest.class, "intOperation", 0);
    private static final Method intOperationOneArg = getMethod(CustomScenarioJavaDSLTest.class, "intOperation", 1);
    private static final Method intOperationTwoArgs = getMethod(CustomScenarioJavaDSLTest.class, "intOperation", 2);
    private static final Method getOperation = getMethod(CustomScenarioJavaDSLTest.class, "getOperation", 1);
    private static final Method operationWithNoParameters = getMethod(CustomScenarioJavaDSLTest.class, "operationWithNoParameters", 0);
    private static final Method operationWithTwoParameters = getMethod(CustomScenarioJavaDSLTest.class, "operationWithTwoParameters", 2);
    private static final Method operationWithManySettings = getMethod(CustomScenarioJavaDSLTest.class, "operationWithManySettings", 0);

    @Test
    public void shouldBuildScenario() {
        ExecutionScenario scenario = new ScenarioBuilder(this.getClass())
                .initial(
                        actor("intOperation", 1),
                        actor("operationWithNoParameters")
                )
                .parallel(
                        thread(
                                actor("getOperation", 2),
                                actor("getOperation", 3)
                        ),
                        thread(
                                actor("operationWithNoParameters"),
                                actor("getOperation", 4)
                        )
                )
                .post(
                        actor("operationWithNoParameters"),
                        actor("operationWithTwoParameters", 3, "123")
                )
                .build();

        ExecutionScenario expectedScenario = new ExecutionScenario(
                List.of(
                        new Actor(intOperationOneArg, List.of(1)),
                        new Actor(operationWithNoParameters, emptyList())
                ),
                List.of(
                        List.of(
                                new Actor(getOperation, List.of(2)),
                                new Actor(getOperation, List.of(3))
                        ),
                        List.of(
                                new Actor(operationWithNoParameters, emptyList()),
                                new Actor(getOperation, List.of(4))
                        )
                ),
                List.of(
                        new Actor(operationWithNoParameters, emptyList()),
                        new Actor(operationWithTwoParameters, List.of(3, "123"))
                )
        );

        assertScenariosEquals(expectedScenario, scenario);
    }

    @Test
    public void shouldBuildMinimalScenario() {
        ExecutionScenario scenario = new ScenarioBuilder(this.getClass()).build();

        assertEquals(0, scenario.initExecution.size());
        assertEquals(0, scenario.parallelExecution.size());
        assertEquals(0, scenario.postExecution.size());
    }

    @Test
    public void shouldThrowExceptionWhenMethodNotFound() {
        var exception = Assert.assertThrows(IllegalArgumentException.class, () ->
                new ScenarioBuilder(this.getClass())
                        .parallel(
                                thread(
                                        actor("unknown")
                                )
                        )
                        .build());

        assertEquals("Method with name unknown and parameterCount 0 not found", exception.getMessage());
    }

    @Test
    public void shouldThrowExceptionWhenMethodArgumentsCountDoesntMatchSuppliedArguments() {
        var exception = Assert.assertThrows(IllegalArgumentException.class, () ->
                new ScenarioBuilder(this.getClass())
                        .parallel(
                                thread(
                                        actor("intOperation", 1, 2, 3)
                                )
                        )
                        .build());
        assertEquals("Method with name intOperation and parameterCount 3 not found", exception.getMessage());
    }

    @Test
    public void shouldThrowOnInitialPartRedeclaration() {
        var exception = Assert.assertThrows(IllegalStateException.class, () ->
                new ScenarioBuilder(this.getClass())
                        .initial(actor("intOperation", 1))
                        .initial(actor("intOperation", 1))
                        .build());

        assertEquals("Redeclaration of the initial part is prohibited.", exception.getMessage());
    }

    @Test
    public void shouldThrowOnParallelPartRedeclaration() {
        var exception = Assert.assertThrows(IllegalStateException.class, () ->
                new ScenarioBuilder(this.getClass())
                        .parallel(thread(actor("intOperation", 1)))
                        .parallel(thread(actor("intOperation", 1)))
                        .build());

        assertEquals("Redeclaration of the parallel part is prohibited.", exception.getMessage());
    }

    @Test
    public void shouldThrowOnPostPartRedeclaration() {
        var exception = Assert.assertThrows(IllegalStateException.class, () ->
                new ScenarioBuilder(this.getClass())
                        .post(actor("intOperation", 1))
                        .post(actor("intOperation", 1))
                        .build());

        assertEquals("Redeclaration of the post part is prohibited.", exception.getMessage());
    }

    @Test
    public void shouldBuildScenarioWithOperationOverloads() {
        var scenario = new ScenarioBuilder(this.getClass())
                .initial(
                        actor("intOperation"),
                        actor("intOperation", 1),
                        actor("intOperation", 2, 3)
                )
                .build();

        ExecutionScenario expectedScenario = new ExecutionScenario(
                List.of(
                        new Actor(intOperation, emptyList()),
                        new Actor(intOperationOneArg, List.of(1)),
                        new Actor(intOperationTwoArgs, List.of(2, 3))
                ),
                emptyList(),
                emptyList()
        );

        assertScenariosEquals(expectedScenario, scenario);
    }

    @Test
    public void shouldInheritActorParametersFromAnnotation() {
        ExecutionScenario scenario = new ScenarioBuilder(this.getClass())
                .initial(actor("operationWithManySettings"))
                .build();

        Actor expectedActor = new Actor(
                operationWithManySettings,
                emptyList(),
                emptyList(),
                false,
                true,
                true,
                true,
                false
        );

        assertEquals(expectedActor, scenario.initExecution.get(0));
    }

    // Stub operations

    @Operation(
            cancellableOnSuspension = false
    )
    public void intOperation() {
    }

    @Operation(
            cancellableOnSuspension = false
    )
    public void intOperation(int value) {
    }

    @Operation(
            cancellableOnSuspension = false
    )
    public void intOperation(int value, int anotherValue) {
    }

    @Operation(
            cancellableOnSuspension = false
    )
    public void getOperation(int value) {
    }

    @Operation(
            cancellableOnSuspension = false
    )
    public void operationWithNoParameters() {
    }

    @Operation(
            cancellableOnSuspension = false
    )
    public void operationWithTwoParameters(int intValue, String stringValue) {
    }

    @Operation(
            cancellableOnSuspension = false,
            blocking = true,
            allowExtraSuspension = true,
            causesBlocking = true,
            promptCancellation = false
    )
    public void operationWithManySettings() {
    }

}
