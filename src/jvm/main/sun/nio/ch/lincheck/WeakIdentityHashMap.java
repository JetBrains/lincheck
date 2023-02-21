/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package sun.nio.ch.lincheck;

import java.lang.ref.*;
import java.util.*;

/**
 * Like WeakHashMap, but uses identity instead of equality when comparing keys.
 */
public class WeakIdentityHashMap<K,V> {
    private final HashMap<WeakReference<K>,V> mMap = new HashMap<>();
    private final ReferenceQueue<Object> mRefQueue = new ReferenceQueue<>();
    private void cleanUp() {
        Reference<?> ref;
        while ((ref = mRefQueue.poll()) != null) {
            mMap.remove(ref);
        }
    }
    public void put(K key, V value) {
        cleanUp();
        mMap.put(new Ref<>(key, mRefQueue), value);
    }
    public V get(K key) {
        cleanUp();
        return mMap.get(new Ref<>(key));
    }
    public Collection<V> values() {
        cleanUp();
        return mMap.values();
    }
    public Set<Map.Entry<WeakReference<K>, V>> entrySet() {
        return mMap.entrySet();
    }
    public int size() {
        cleanUp();
        return mMap.size();
    }
    public boolean isEmpty() {
        cleanUp();
        return mMap.isEmpty();
    }

    private static class Ref<K> extends WeakReference<K> {
        private final int mHashCode;
        public Ref(K key) {
            super(key);
            mHashCode = System.identityHashCode(key);
        }
        public Ref(K key, ReferenceQueue<Object> refQueue) {
            super(key, refQueue);
            mHashCode = System.identityHashCode(key);
        }
        @Override
        public boolean equals(Object o) {
            if (o == this) {
                return true;
            }
            K k = get();
            if (k != null && o instanceof WeakIdentityHashMap.Ref) {
                return ((Ref) o).get() == k;
            }
            return false;
        }
        @Override
        public int hashCode() {
            return mHashCode;
        }
    }
}