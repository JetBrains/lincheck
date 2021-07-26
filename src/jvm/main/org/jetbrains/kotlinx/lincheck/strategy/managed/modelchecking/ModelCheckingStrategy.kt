/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2021 JetBrains s.r.o.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking

import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.nvm.RecoverabilityModel
import org.jetbrains.kotlinx.lincheck.verifier.Verifier
import java.lang.reflect.Method

internal class ModelCheckingStrategy(
    testCfg: ModelCheckingCTestConfiguration,
    testClass: Class<*>,
    scenario: ExecutionScenario,
    validationFunctions: List<Method>,
    stateRepresentation: Method?,
    verifier: Verifier,
    recoverModel: RecoverabilityModel
) : AbstractModelCheckingStrategy<ModelCheckingStrategy.InnerSwitchesInterleaving, ModelCheckingStrategy.InnerSwitchesInterleavingBuilder>(
    testCfg, testClass, scenario, validationFunctions, stateRepresentation, verifier, recoverModel
) {
    override fun createBuilder() = InnerSwitchesInterleavingBuilder()

    internal inner class InnerSwitchesInterleavingBuilder : SwitchesInterleavingBuilder<InnerSwitchesInterleaving>() {
        override fun build() = InnerSwitchesInterleaving(switchPositions, threadSwitchChoices, lastNoninitializedNode)
    }

    internal inner class InnerSwitchesInterleaving(
        switchPositions: List<Int>,
        threadSwitchChoices: List<Int>,
        lastNotInitializedNode: InterleavingTreeNode?
    ) : SwitchesInterleaving(switchPositions, threadSwitchChoices, lastNotInitializedNode)
}
