package com.devexperts.dxlab.lincheck.annotations;

/*
 * #%L
 * core
 * %%
 * Copyright (C) 2015 - 2017 Devexperts, LLC
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
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Set some restrictions to the group with specified name.
 * These restrictions are used during execution scenario generation.
 *
 * Now the following restrictions are available:
 * <ul>
 *     <li>{@code nonParallel} - set it to {@code true} for executing
 *     all actors in this group from one thread. This restriction allows
 *     to test single-reader and/or single-writer data structures and
 *     similar solutions.
 *     </li>
 * </ul>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(OpGroupConfigs.class)
public @interface OpGroupConfig {
    String name() default "";
    boolean nonParallel() default false;
}

