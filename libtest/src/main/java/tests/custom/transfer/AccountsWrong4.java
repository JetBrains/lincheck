package tests.custom.transfer;

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

import java.util.HashMap;
import java.util.Map;

public class AccountsWrong4 implements Accounts {

    Map<Integer, Integer> data;

    public AccountsWrong4() {
        data = new HashMap<>(1);
    }

    // This operation is not synchronized with others
    @Override
    public Integer getAmount(int id) {
        return data.getOrDefault(id, 0);
    }

    @Override
    public synchronized void setAmount(int id, int value) {
        data.put(id, value);
    }

    @Override
    public synchronized void transfer(int id1, int id2, int value) {
        if (id1 == id2) return;
        Integer v1 = data.get(id1);
        Integer v2 = data.get(id2);
        if (v1 == null) v1 = 0;
        if (v2 == null) v2 = 0;
        v1 -= value;
        v2 += value;
        data.put(id1, v1);
        data.put(id2, v2);
    }

    @Override
    public String toString() {
        return "AccountsSynchronized{" +
                "data=" + data +
                '}';
    }
}
