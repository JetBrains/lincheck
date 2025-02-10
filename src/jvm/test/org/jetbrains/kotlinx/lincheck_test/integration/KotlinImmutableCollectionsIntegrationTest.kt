/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.integration

import org.junit.Test

class KotlinImmutableCollectionsIntegrationTest: AbstractIntegrationTest() {
    override val projectPath: String = "build/integrationTestProjects/kotlinx.collections.immutable"

    @Test
    fun `tests contract list GuavaImmutableListTest_list`() {
//        runGradleTest(
//            "tests.contract.list.GuavaImmutableListTest",
//            "list",
//            ":kotlinx-collections-immutable:cleanJvmTest",
//            ":kotlinx-collections-immutable:jvmTest",
//        )
    }
}
