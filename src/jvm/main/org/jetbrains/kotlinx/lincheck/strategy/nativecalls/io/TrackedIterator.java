/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.nativecalls.io;

import java.util.Iterator;
import java.util.function.Consumer;

// It is excessive to track any iterator, so once we find that iterator actions need to be recorded,
// we wrap it with one, whose actions we always track.
// Also, we expose found elements after each forEachRemaining.
// Implemented in Java to overcome interoperability issues with Kotlin (Mutable)Iterator and Java one.
class TrackedIterator<T> extends ForEachHolder<T> implements Iterator<T> {
    private final Iterator<T> iterator;

    TrackedIterator(Iterator<T> iterator) {
        super();
        this.iterator = iterator;
    }

    @Override
    public boolean hasNext() {
        verifyIsNotReplaying();
        return iterator.hasNext();
    }

    @Override
    public T next() {
        verifyIsNotReplaying();
        return iterator.next();
    }

    @Override
    public void forEachRemaining(Consumer<? super T> action) {
        verifyIsNotReplaying();
        clearRemainingElements();
        iterator.forEachRemaining(element -> {
            addRemainingElement(element);
            action.accept(element);
        });
    }
}
