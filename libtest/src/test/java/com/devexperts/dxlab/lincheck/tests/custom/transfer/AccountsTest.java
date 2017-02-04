/*
 *  Lincheck - Linearizability checker
 *  Copyright (C) 2015 Devexperts LLC
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.devexperts.dxlab.lincheck.tests.custom.transfer;

/*
 * #%L
 * libtest
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

import com.devexperts.dxlab.lincheck.LinChecker;
import com.devexperts.dxlab.lincheck.annotations.CTest;
import com.devexperts.dxlab.lincheck.annotations.Operation;
import com.devexperts.dxlab.lincheck.annotations.Param;
import com.devexperts.dxlab.lincheck.annotations.ReadOnly;
import com.devexperts.dxlab.lincheck.annotations.Reset;
import com.devexperts.dxlab.lincheck.generators.IntGen;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import tests.custom.transfer.Accounts;
import tests.custom.transfer.AccountsWrong1;
import tests.custom.transfer.AccountsWrong2;
import tests.custom.transfer.AccountsWrong3;
import tests.custom.transfer.AccountsWrong4;

import java.util.Arrays;
import java.util.List;

@CTest(iterations = 500, actorsPerThread = {"2:5", "2:5"})
@Param(name = "id", gen = IntGen.class, conf = "1:2")
@Param(name = "amount", gen = IntGen.class)
@RunWith(Parameterized.class)
public class AccountsTest {
    private final Class<? extends Accounts> accountsClass;
    private Accounts acc;

    @Parameterized.Parameters
    public static List<Object[]> params() {
        return Arrays.<Object[]>asList(
            new Object[] {AccountsWrong1.class},
            new Object[] {AccountsWrong2.class},
            new Object[] {AccountsWrong3.class},
            new Object[] {AccountsWrong4.class}
        );
    }

    public AccountsTest(Class<? extends Accounts> accountsClass) {
        this.accountsClass = accountsClass;
    }

    @Reset
    public void reload() throws Exception {
        acc = accountsClass.newInstance();
    }

    @ReadOnly
    @Operation(params = {"id"})
    public int getAmount(int key) {
        return acc.getAmount(key);
    }

    @Operation(params = {"id", "amount"})
    public void setAmount(int key, int value) {
        acc.setAmount(key, value);
    }

    @Operation
    public void transfer(@Param(name = "id") int from, @Param(name = "id") int to, @Param(name = "amount") int amount) {
        acc.transfer(from, to, amount);
    }

    @Test(expected = AssertionError.class)
    public void test() throws Exception {
        LinChecker.check(this);
    }
}
