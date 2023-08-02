/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.strategy.managed

import org.jetbrains.kotlinx.lincheck.*
import java.lang.reflect.*
import java.util.*

/**
 * This utility class stores the current strategy and its state. In order to run several tests in parallel,
 * each iteration should use its own class loader so that the state is unique for each class loader and, therefore,
 * for each iteration.
 */
internal object ManagedStrategyStateHolder {
    @JvmField
    var strategy: ManagedStrategy? = null
    @JvmField
    var objectManager: ObjectManager? = null
    @JvmField
    var random: Random? = null

    /**
     * Sets the strategy and its initial state for the specified class loader.
     */
    fun setState(loader: ClassLoader, strategy: ManagedStrategy?, testClass: Class<out Any>) {
        try {
            val clazz = loader.loadClass(ManagedStrategyStateHolder::class.java.canonicalName)
            clazz.getField(this::strategy.name)[null] = strategy
            clazz.getField(this::objectManager.name)[null] = ObjectManager(testClass)
            // load transformed java.util.Random class
            val randomClass = loader.loadClass(Random::class.java.canonicalName)
            clazz.getField(this::random.name)[null] = randomClass.getConstructor(Long::class.javaPrimitiveType).newInstance(INITIAL_SEED)
        } catch (e: Throwable) {
            throw IllegalStateException("Cannot set state to ManagedStateHolder", e)
        }
    }
}

private const val INITIAL_SEED: Long = 1337
