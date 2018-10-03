package com.devexperts.dxlab.lincheck.annotations;

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

import com.devexperts.dxlab.lincheck.paramgen.ParameterGenerator;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Use this annotation to specify parameter generators.
 * @see ParameterGenerator
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.TYPE})
@Repeatable(Param.Params.class)
@Inherited
public @interface Param {
    /**
     * If the annotation is set on a class, creates a {@link ParameterGenerator parameter generator}
     * which can be used in {@link Operation operations} by this name. If is set on an operation,
     * uses the specified named parameter generator which is created as described before.
     */
    String name() default "";

    /**
     * Specifies the {@link ParameterGenerator} class which should be used for this parameter.
     */
    Class<? extends ParameterGenerator<?>> gen() default ParameterGenerator.Dummy.class;

    /**
     * Specifies the configuration for the {@link #gen() parameter generator}.
     */
    String conf() default "";

    /**
     * Holder annotation for {@link Param}.
     * Not a public API.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @Inherited
    @interface Params {
        Param[] value();
    }
}