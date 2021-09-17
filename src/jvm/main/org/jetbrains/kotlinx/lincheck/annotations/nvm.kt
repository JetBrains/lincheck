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
package org.jetbrains.kotlinx.lincheck.annotations

/**
 * Marks a method as recoverable in NRL model.
 *
 * Links this method to [recoverMethod] that should be called when this method crashes.
 * [recoverMethod] must have the same signature as this method.
 *
 * [beforeMethod] will be called before this method invocation util it completes successfully.
 * It may be used for variable initialisation, must be idempotent.
 * Has the same parameters as this method, but returns nothing.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Recoverable(
    val recoverMethod: String = "",
    val beforeMethod: String = ""
)

/**
 * Classes or methods marked with this annotation do not crash.
 * It may be used for progressive development of crash resistant programs.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CONSTRUCTOR, AnnotationTarget.CLASS)
annotation class CrashFree

/**
 * Specifies a recover method in durable linearizability model.
 * This method will be called after a system crash by *one* of the threads.
 * This thread should perform a recovery for the whole data structure.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class DurableRecoverAll

/**
 * Specifies a recover method in durable linearizability model.
 * This method will be called after a system crash by every thread.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class DurableRecoverPerThread

/**
 * Marks method as a sync method in buffered durable linearizability model.
 * After a successful invocation of this method a data structure must be persistent.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Sync
