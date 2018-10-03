package com.devexperts.dxlab.lincheck.verifier.quasi;

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
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * This annotation with quasi-linearizability verifier
 * parameters should be presented on a test class
 * if the {@link QuasiLinearizabilityVerifier} is used.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface QuasiLinearizabilityVerifierConf {
    /**
     * Relaxation factor.
     */
    int factor();

    /**
     * The sequential implementation of the
     * testing data structure  with the same
     * methods as operations in the test class
     * (same name, signature), but with a
     * correct sequential implementations.
     * It also should have an empty constructor
     * which creates the initial state
     * (similar to the test class).
     */
    Class<?> sequentialImplementation();
}
