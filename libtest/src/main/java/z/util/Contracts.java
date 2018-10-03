/**
 * Copyright 2013, Landz and its contributors. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package z.util;

/*
 * #%L
 * libtest
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

import z.exception.ContractViolatedException;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Contracts is static collection for facitiy
 *
 * @author Landz
 */
public class Contracts {

    private Contracts(){};

    /**
     * this constant - TRICK, is a trick for disabling all Contracts side
     * in the compile-time for zero overhead.
     *
     * !note: This kind of flag is not excited for
     *        some hotspot optimization bug.
     */
    private static final boolean TRICK = true;

    public static void contract(BooleanSupplier contractSupplier) {
        if (TRICK && !contractSupplier.getAsBoolean())
            throw new ContractViolatedException("[Contract Breached]: " +
                "the contract "+ contractSupplier + " fails to be kept!");
    }

    /**
     *
     * Every contract has a tag, which support the hierarchical form,
     * like Java package naming.
     * <p>
     * The convention is to use the className.methodName for the tag, like:
     * <pre>{@code
     *     contract()
     * }</pre>
     *
     * @param prefixedLabel
     * @param contractSupplier
     */
    public static final void contract(String prefixedLabel,
                                      BooleanSupplier contractSupplier) {
        if (TRICK && !contractSupplier.getAsBoolean())
            throw new ContractViolatedException(prefixedLabel + ":" +
                                              " the contract "+ contractSupplier +
                                              " fails to be kept!");
    }

    /**
     * here, you can customize the type and content of your exception.
     *
     * @param contractSupplier
     * @param exceptionSupplier
     */
    public static final void contract(BooleanSupplier contractSupplier,
                                      Supplier<RuntimeException> exceptionSupplier) {
        if (TRICK && !contractSupplier.getAsBoolean())
            throw exceptionSupplier.get();
    }





}
