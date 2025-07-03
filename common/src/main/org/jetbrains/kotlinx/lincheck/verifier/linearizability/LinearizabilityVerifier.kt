/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.verifier.linearizability

import org.jetbrains.lincheck.datastructures.verifier.LinearizabilityVerifier
import org.jetbrains.lincheck.datastructures.verifier.Verifier

@Deprecated(
    message = "Use org.jetbrains.lincheck.datastructures.verifier.LinearizabilityVerifier instead.",
    level = DeprecationLevel.WARNING
)
class LinearizabilityVerifier
    private constructor(val delegate: LinearizabilityVerifier) : Verifier by delegate
{
    constructor(sequentialSpecification: Class<*>) : this(LinearizabilityVerifier(sequentialSpecification))
}