/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck

import org.jetbrains.lincheck.scenario.ConcurrentTestStructure

/**
 * Placeholder keeping the binary name of the class renamed to [ConcurrentTestStructure].
 *
 * Custom execution generators compiled against the old API declare constructors taking this class;
 * keeping the name resolvable lets the settings validation recognize such constructors
 * and report the required migration instead of a generic error.
 */
internal class CTestStructure private constructor()
