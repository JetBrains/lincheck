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
package org.jetbrains.kotlinx.lincheck.test;

import kotlin.jvm.JvmClassMappingKt;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlinx.lincheck.annotations.Operation;
import org.jetbrains.kotlinx.lincheck.strategy.UnexpectedExceptionFailure;

public class UnexpectedExceptionTest extends AbstractLincheckTest {
    public UnexpectedExceptionTest() {
        super(JvmClassMappingKt.getKotlinClass(UnexpectedExceptionFailure.class));
    }

    private boolean canEnterForbiddenSection = false;

    @Operation
    public void operation1() {
        canEnterForbiddenSection = true;
        canEnterForbiddenSection = false;
    }

    @Operation(handleExceptionsAsResult = AssertionError.class)
    public void operation2() {
        if (canEnterForbiddenSection) {
            throw new IllegalStateException();
        }
    }

    @NotNull
    @Override
    protected Object extractState() {
        return canEnterForbiddenSection;
    }
}

