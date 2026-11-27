/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.datastructures

/**
 * Legacy configuration type retained as the bound of the unused type parameter on [Options].
 */
@Suppress("DEPRECATION")
abstract class CTestConfiguration

abstract class ManagedCTestConfiguration : CTestConfiguration()

class ModelCheckingCTestConfiguration private constructor() : ManagedCTestConfiguration()

class StressCTestConfiguration private constructor() : CTestConfiguration()
