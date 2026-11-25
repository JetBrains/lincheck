/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test.transformation;

import org.jetbrains.lincheck.Lincheck;
import org.jetbrains.lincheck.LincheckSettings;
import org.jetbrains.lincheck.LincheckSettings.ScenarioGeneration;
import org.jetbrains.lincheck.LincheckSettings.TestingStrategy.ModelChecking;
import org.jetbrains.lincheck.datastructures.Operation;
import org.junit.Test;

public class FieldInheritedFromInterfaceTest implements InterfaceWithField {
    @Operation
    public int get() { return INTERFACE_CONSTANT.getValue(); }

    @Test
    public void test() {
        LincheckSettings settings = new LincheckSettings()
            .strategy(new ModelChecking())
            .scenarioGeneration(new ScenarioGeneration()
                .randomScenarios(1)
            );
        Lincheck.runConcurrentTest(settings, FieldInheritedFromInterfaceTest.class);
    }
}

interface InterfaceWithField {
    public static final ValueHolder INTERFACE_CONSTANT = new ValueHolder(6);
}
