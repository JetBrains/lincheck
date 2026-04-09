/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.paramgen

import org.jetbrains.lincheck.datastructures.*

/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

@Deprecated("Use org.jetbrains.lincheck.datastructures.EnumGen instead.")
class EnumGen<T : Enum<T>>(enumClass: Class<out T>, randomProvider: RandomProvider, configuration: String) :
    ParameterGenerator<T> by org.jetbrains.lincheck.datastructures.EnumGen(enumClass, randomProvider, configuration)

@Deprecated("Use org.jetbrains.lincheck.datastructures.IntGen instead.")
class IntGen(randomProvider: RandomProvider, configuration: String) :
    ParameterGenerator<Int> by org.jetbrains.lincheck.datastructures.IntGen(randomProvider, configuration)

@Deprecated("Use org.jetbrains.lincheck.datastructures.BooleanGen instead.")
class BooleanGen(randomProvider: RandomProvider, @Suppress("UNUSED_PARAMETER") configuration: String) :
    ParameterGenerator<Boolean> by org.jetbrains.lincheck.datastructures.BooleanGen(randomProvider, configuration)

@Deprecated("Use org.jetbrains.lincheck.datastructures.ByteGen instead.")
class ByteGen(randomProvider: RandomProvider, configuration: String) :
    ParameterGenerator<Byte> by org.jetbrains.lincheck.datastructures.ByteGen(randomProvider, configuration)

@Deprecated("Use org.jetbrains.lincheck.datastructures.DoubleGen instead.")
class DoubleGen(randomProvider: RandomProvider, configuration: String) :
    ParameterGenerator<Double> by org.jetbrains.lincheck.datastructures.DoubleGen(randomProvider, configuration)

@Deprecated("Use org.jetbrains.lincheck.datastructures.FloatGen instead.")
class FloatGen(randomProvider: RandomProvider, configuration: String) :
    ParameterGenerator<Float> by org.jetbrains.lincheck.datastructures.FloatGen(randomProvider, configuration)

@Deprecated("Use org.jetbrains.lincheck.datastructures.LongGen instead.")
class LongGen(randomProvider: RandomProvider, configuration: String) :
    ParameterGenerator<Long> by org.jetbrains.lincheck.datastructures.LongGen(randomProvider, configuration)

@Deprecated("Use org.jetbrains.lincheck.datastructures.ShortGen instead.")
class ShortGen(randomProvider: RandomProvider, configuration: String) :
    ParameterGenerator<Short> by org.jetbrains.lincheck.datastructures.ShortGen(randomProvider, configuration)

@Deprecated("Use org.jetbrains.lincheck.datastructures.StringGen instead.")
class StringGen(randomProvider: RandomProvider, configuration: String) :
    ParameterGenerator<String> by org.jetbrains.lincheck.datastructures.StringGen(randomProvider, configuration)

@Deprecated("Use org.jetbrains.lincheck.datastructures.ThreadIdGen instead.")
class ThreadIdGen(randomProvider: RandomProvider, configuration: String) :
    ParameterGenerator<Any> by org.jetbrains.lincheck.datastructures.ThreadIdGen(randomProvider, configuration)