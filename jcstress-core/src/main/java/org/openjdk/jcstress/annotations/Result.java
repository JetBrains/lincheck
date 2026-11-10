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
 * {@link Result} annotation marks the result object. This annotation is seldom
 * useful for user code, because jcstress ships lots of pre-canned result classes,
 * see {@link org.openjdk.jcstress.infra.results} package.
 *
 * <p>Important invariants and properties:
 * <ol>
 *     <li>All fields in {@link Result} classes should be public.</li>
 *     <li>All fields in {@link Result} classes shoudl be either primitive, or String.</li>
 *     <li>{@link Result} classes should be serializable.</li>
 *     <li>{@link Result} classes should have proper {@link #equals(Object)} and {@link #hashCode()}
 *     methods to disambiguate one result from another.</li>
 *     <li>{@link Result} classes should have unique {@link #toString()} representation,
 *     because it is being matched with {@link Outcome#id()}, and also serves
 *     as key to separate one result from another in the output log.
 *     (This might be revisited in future releases of jcstress).</li>
 *     <li>{@link Result} classes mimic value types. They do not have identity,
 *     and jcstress may reuse the objects, auto-magically clear the result fields,
 *     etc. It is not advisable to have auxiliary fields and methods in {@link Result}
 *     class, because its state is managed by jcstress.</li>
 * </ol>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Result {
}
