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


/**
 * Some primitives cannot be transformed due to the [sun.reflect.CallerSensitive]
 * annotation. These primitives are a subset of trusted ones.
 */
fun isImpossibleToTransformPrimitive(className: String) =
        className == "sun.misc.Unsafe" ||
        className == "java.lang.invoke.VarHandle" ||
        (className.startsWith("java.util.concurrent.atomic.Atomic") && className.endsWith("FieldUpdater"))
