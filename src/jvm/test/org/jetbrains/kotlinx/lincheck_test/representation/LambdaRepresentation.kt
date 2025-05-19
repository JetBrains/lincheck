/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.representation

class LambdaRepresentation: BaseTraceRepresentationTest("java_lambda_argument_representation") {
    var a = 1
    val hm = HashMap<Int, Int>()
    override fun operation() {
        hm.computeIfAbsent(1) { a++ }
    }

}