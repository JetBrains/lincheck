/*
 * Copyright (c) 2005, 2014, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.jcstress.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@link Actor} is the central test annotation. It marks the methods that hold the
 * actions done by the threads. The invariants that are maintained by the infrastructure
 * are as follows:
 *
 * <ol>
 *     <li>Each method is called only by one particular thread.</li>
 *     <li>Each method is called exactly once per {@link State} instance.</li>
 * </ol>
 *
 * <p>Note that the invocation order against other {@link Actor} methods is deliberately
 * not specified. Therefore, two or more {@link Actor} methods may be used to model
 * the concurrent execution on data held by {@link State} instance.
 *
 * <p>Actor-annotated methods can have only the {@link State} or {@link Result}-annotated
 * classes as the parameters. Actor methods may declare to throw the exceptions, but
 * actually throwing the exception would fail the test.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Actor {
}
