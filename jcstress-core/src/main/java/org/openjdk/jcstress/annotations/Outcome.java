/*
 * Copyright (c) 2014, 2014, Oracle and/or its affiliates. All rights reserved.
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
import java.lang.annotation.Inherited;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@link Outcome} describes the test outcome, and how to deal with it.
 * It is usually the case that a {@link JCStressTest} has multiple outcomes,
 * each with its distinct {@link #id()}.
 *
 * <p>{@link #id()} is cross-matched with {@link Result}-class' {@link #toString()}
 * value. {@link #id()} allows regular expressions. For example, this outcome
 * captures all results where there is a trailing "1":
 *
 * <pre>{@code
 *     \@Outcome(id = ".*, 1", ...)
 * }</pre>
 *
 * <p>When there is no ambiguity in what outcome should match the result, the
 * annotation order is irrelevant. When there is an ambiguity, the first outcome
 * in the declaration order is matched.
 *
 * <p>There can be a default outcome, which captures any non-captured result.
 * It is the one with the default {@link #id()}.
 */
@Inherited
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(Outcome.Outcomes.class)
public @interface Outcome {

    /**
     * @return Observed result. Empty string or no parameter if the case is default.
     * Supports regular expressions.
     */
    String[] id() default { "" };

    /**
     * @return Expectation for the observed result.
     * @see Expect
     */
    Expect expect();

    /**
     * @return Human-readable description for a given result.
     */
    String desc() default "";

    /**
     * Holder annotation for {@link Outcome}.
     */
    @Inherited
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @interface Outcomes {
        Outcome[] value();
    }
}
