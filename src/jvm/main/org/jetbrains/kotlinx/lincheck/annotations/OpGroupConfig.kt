/*
* #%L
* Lincheck
* %%
* Copyright (C) 2015 - 2018 Devexperts, LLC
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

import java.lang.annotation.Inherited

/**
 * Set some restrictions to the group with the specified name,
 * used during the scenario generation phase.
 */
@kotlin.annotation.Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.CLASS)
@kotlin.annotation.Repeatable
@Inherited
annotation class OpGroupConfig(
        /**
         * Name of this group used by [Operation.group].
         */
        val name: String = "",
        /**
         * Set it to `true` for executing all actors in this group
         * from one thread. This restriction allows to test single-reader
         * and/or single-writer data structures and similar solutions.
         */
        val nonParallel: Boolean = false) {
    /**
     * Holder annotation for [OpGroupConfig].
     * Not a public API.
     */
    @kotlin.annotation.Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.CLASS)
    @Inherited
    annotation class OpGroupConfigs(vararg val value: OpGroupConfig)
}