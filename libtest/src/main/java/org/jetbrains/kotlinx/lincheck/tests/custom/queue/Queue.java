package org.jetbrains.kotlinx.lincheck.tests.custom.queue;

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

import org.jetbrains.kotlinx.lincheck.verifier.VerifierState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public abstract class Queue extends VerifierState {
    public abstract void put(int x) throws QueueFullException;
    public abstract int get() throws QueueEmptyException;

    @Override
    public Object extractState() {
        List<Integer> elements = new ArrayList<>();
        try {
            while (true) {
                elements.add(get());
            }
        } catch (QueueEmptyException e) {
            // queue is empry
        }
        return elements;
    }
}

class QueueWrong1 extends Queue {
    private int indGet;
    private int indPut;
    private int countElements;

    private int[] items;

    private int inc(int i) {
        return (++i == items.length ? 0 : i);
    }

    public QueueWrong1(int capacity) {
        items = new int[capacity];

        indPut = 0;
        indGet = 0;
        countElements = 0;

    }

    @Override
    public void put(int x) throws QueueFullException {
        if (countElements == items.length) {
            throw new QueueFullException();
        }
        items[indPut] = x;
        indPut = inc(indPut);
        countElements++;
    }

    @Override
    public int get() throws QueueEmptyException {
        if (countElements == 0) {
            throw new QueueEmptyException();
        }
        int ret = items[indGet];
        indGet = inc(indGet);
        countElements--;
        return ret;
    }
}

class QueueWrong2 extends Queue {
    private int indGet;
    private int indPut;
    private int countElements;

    private int[] items;

    private int inc(int i) {
        return (++i == items.length ? 0 : i);
    }

    public QueueWrong2(int capacity) {
        items = new int[capacity];

        indPut = 0;
        indGet = 0;
        countElements = 0;

    }

    @Override
    public synchronized void put(int x) throws QueueFullException {
        if (countElements == items.length) {
            throw new QueueFullException();
        }
        items[indPut] = x;
        indPut = inc(indPut);
        countElements++;
    }

    @Override
    public int get() throws QueueEmptyException {
        if (countElements == 0) {
            throw new QueueEmptyException();
        }
        int ret = items[indGet];
        indGet = inc(indGet);
        countElements--;
        return ret;
    }
}

class QueueWrong3 extends Queue {
    private int indGet;
    private int indPut;
    private int countElements;

    private int[] items;

    private int inc(int i) {
        return (++i == items.length ? 0 : i);
    }

    public QueueWrong3(int capacity) {
        items = new int[capacity];

        indPut = 0;
        indGet = 0;
        countElements = 0;

    }

    @Override
    public void put(int x) throws QueueFullException {
        if (countElements == items.length) {
            throw new QueueFullException();
        }
        items[indPut] = x;
        indPut = inc(indPut);
        countElements++;
    }

    @Override
    public synchronized int get() throws QueueEmptyException {
        if (countElements == 0) {
            throw new QueueEmptyException();
        }
        int ret = items[indGet];
        indGet = inc(indGet);
        countElements--;
        return ret;
    }
}

class QueueSynchronized extends Queue {
    private int indGet;
    private int indPut;
    private int countElements;

    private int[] items;

    private int inc(int i) {
        return (++i == items.length ? 0 : i);
    }

    public QueueSynchronized(int capacity) {
        items = new int[capacity];

        indPut = 0;
        indGet = 0;
        countElements = 0;

    }

    @Override
    public synchronized void put(int x) throws QueueFullException {
        if (countElements == items.length) {
            throw new QueueFullException();
        }
        items[indPut] = x;
        indPut = inc(indPut);
        countElements++;
    }

    @Override
    public synchronized int get() throws QueueEmptyException {
        if (countElements == 0) {
            throw new QueueEmptyException();
        }
        int ret = items[indGet];
        indGet = inc(indGet);
        countElements--;
        return ret;
    }
}
