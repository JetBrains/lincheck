package org.jetbrains.kotlinx.lincheck.tests.custom.transfer;

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

import org.jetbrains.annotations.*;
import org.jetbrains.kotlinx.lincheck.*;
import org.jetbrains.kotlinx.lincheck.annotations.Operation;
import org.jetbrains.kotlinx.lincheck.annotations.*;
import org.jetbrains.kotlinx.lincheck.paramgen.*;
import org.jetbrains.kotlinx.lincheck.strategy.stress.*;
import org.jetbrains.kotlinx.lincheck.verifier.*;
import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import tests.custom.transfer.*;

import java.util.*;
import java.util.function.*;

@RunWith(Parameterized.class)
public class AccountsTest {
    private static Supplier<Accounts> accountCreator;

    @Parameterized.Parameters(name = "{1}")
    public static List<Object[]> params() {
        return Arrays.<Object[]>asList(
            new Object[] {(Supplier<Accounts>) AccountsWrong1::new, "AccountsWrong1"},
            new Object[] {(Supplier<Accounts>) AccountsWrong2::new, "AccountsWrong2"},
            new Object[] {(Supplier<Accounts>) AccountsWrong3::new, "AccountsWrong3"},
            new Object[] {(Supplier<Accounts>) AccountsWrong4::new, "AccountsWrong4"}
        );
    }

    public AccountsTest(Supplier<Accounts> accountCreator, String desc) {
        AccountsTest.accountCreator = accountCreator;
    }

    @StressCTest(threads = 3, actorsPerThread = 3)
    @Param(name = "id", gen = IntGen.class, conf = "1:4")
    @Param(name = "amount", gen = IntGen.class)
    public static class AccountsLinearizabilityTest extends VerifierState {
        private final Accounts acc = accountCreator.get();

        @Operation(params = {"id"})
        public int getAmount(int key) {
            return acc.getAmount(key);
        }

        @Operation(params = {"id", "amount"})
        public void setAmount(int key, int value) {
            acc.setAmount(key, value);
        }

        @Operation
        public void transfer(@Param(name = "id") int from, @Param(name = "id") int to,
                             @Param(name = "amount") int amount)
        {
            acc.transfer(from, to, amount);
        }

        @NotNull
        @Override
        protected Object extractState() {
            List<Integer> amounts = new ArrayList<>();
            for (int id = 1; id <= 4; id++)
                amounts.add(getAmount(id));
            return amounts;
        }
    }

    @Test(expected = AssertionError.class)
    public void test() {
        LinChecker.check(AccountsLinearizabilityTest.class);
    }
}
