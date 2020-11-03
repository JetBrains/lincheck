/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
 * %%
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
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
            clazz.getField("strategy")[null] = strategy
            clazz.getField("objectManager")[null] = ObjectManager(testClass)
            // load transformed java.util.Random class
            val randomClass = loader.loadClass(Random::class.java.canonicalName)
            clazz.getField("random")[null] = randomClass.getConstructor().newInstance()
        } catch (e: Throwable) {
            throw IllegalStateException("Cannot set state to ManagedStateHolder", e)
        }
    }

    /**
     * Prepare the state for the specified class loader for the next invocation.
     */
    fun resetState(loader: ClassLoader, testClass: Class<out Any>) {
        try {
            val clazz = loader.loadClass(ManagedStrategyStateHolder::class.java.canonicalName)
            clazz.getMethod("resetStateImpl", Class::class.java).invoke(null, testClass)
        } catch (e: Exception) {
            throw IllegalStateException("Cannot set state to ManagedStateHolder", e)
        }
    }

    @JvmStatic
    fun resetStateImpl(testClass: Class<out Any>) {
        random!!.setSeed(INITIAL_SEED)
        objectManager = ObjectManager(testClass)
    }
}

private const val INITIAL_SEED: Long = 1337
