package org.jetbrains.kotlinx.lincheck.annotations;

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

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Set some restrictions to the group with the specified name,
 * used during the scenario generation phase.
 *
 * @deprecated use {@link Operation#nonParallelGroup()} instead.
 */
@SuppressWarnings("DeprecatedIsStillUsed")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(OpGroupConfig.OpGroupConfigs.class)
@Inherited
@Deprecated(forRemoval = true)
public @interface OpGroupConfig {
    /**
     * Name of this group used by {@link Operation#group()}.
     */
    String name() default "";

    /**
     * Set it to {@code true} for executing all actors in this group
     * from one thread. This restriction allows to test single-reader
     * and/or single-writer data structures and similar solutions.
     */
    boolean nonParallel() default false;

    /**
     * Holder annotation for {@link OpGroupConfig}.
     * Not a public API.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @Inherited
    @interface OpGroupConfigs {
        OpGroupConfig[] value();
    }
}