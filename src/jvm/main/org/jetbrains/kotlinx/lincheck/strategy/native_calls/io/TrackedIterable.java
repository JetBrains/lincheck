/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.native_calls.io;

import java.lang.Iterable;
import java.util.Iterator;
import java.util.Spliterator;
import java.util.function.Consumer;

// It is excessive to track any iterable, so once we find that iterable actions need to be recorded,
// we wrap it with one, whose actions we always track.
// Also, we expose found elements after each forEach.
// Implemented in Java to overcome interoperability issues with Kotlin (Mutable)Iterable and Java one.
class TrackedIterable<T> extends ForEachHolder<T> implements Iterable<T> {
    private final Iterable<T> iterable;
    
    public TrackedIterable(Iterable<T> iterable) {
        super();
        this.iterable = iterable;
    }

    @Override
    public Iterator<T> iterator() {
        verifyIsNotReplaying();
        return new TrackedIterator<>(iterable.iterator());
    }

    @Override
    public Spliterator<T> spliterator() {
        verifyIsNotReplaying();
        return new TrackedSpliterator<>(Iterable.super.spliterator());
    }

    @Override
    public void forEach(Consumer<? super T> action) {
        verifyIsNotReplaying();
        clearRemainingElements();
        Iterable.super.forEach(element -> {
            addRemainingElement(element);
            action.accept(element);
        });
    }
}
