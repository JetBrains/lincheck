/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sun.nio.ch.lincheck;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.*;

/**
 * Like WeakHashMap, but uses identity instead of equality when comparing keys.
 *
 * <p>
 * Copied and modified from:
 * https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/src/com/android/systemui/util/leak/WeakIdentityHashMap.java
 */
public class WeakIdentityHashMap<K,V> extends AbstractMap<K, V> {

    private final HashMap<WeakReference<K>,V> mMap = new HashMap<>();
    private final ReferenceQueue<Object> mRefQueue = new ReferenceQueue<>();

    private void cleanUp() {
        Reference<?> ref;
        while ((ref = mRefQueue.poll()) != null) {
            mMap.remove(ref);
        }
    }

    @Override
    public V put(K key, V value) {
        cleanUp();
        return mMap.put(new WeakIdentityReference<>(key, mRefQueue), value);
    }

    @Override
    public V get(Object key) {
        cleanUp();
        return mMap.get(new WeakIdentityReference<>(key));
    }

    @Override
    public V remove(Object key) {
        cleanUp();
        return mMap.remove(new WeakIdentityReference<>(key));
    }

    @Override
    public boolean containsKey(Object key) {
        cleanUp();
        return mMap.containsKey(new WeakIdentityReference<>(key));
    }

    @Override
    public boolean containsValue(Object value) {
        cleanUp();
        return mMap.containsValue(value);
    }

    @Override
    public Collection<V> values() {
        cleanUp();
        return mMap.values();
    }

    @Override
    public Set<Map.Entry<K, V>> entrySet() {
        cleanUp();
        return new WeakReferenceEntrySet();
    }

    @Override
    public int size() {
        cleanUp();
        return mMap.size();
    }

    @Override
    public boolean isEmpty() {
        cleanUp();
        return mMap.isEmpty();
    }

    private class WeakReferenceEntrySet extends AbstractSet<Map.Entry<K, V>> {

        @Override
        public int size() {
            return mMap.size();
        }

        @Override
        public void clear() {
            mMap.clear();
        }

        @Override
        public Iterator<Entry<K, V>> iterator() {
            return new Iterator<Entry<K, V>>() {

                private final Iterator<Entry<WeakReference<K>, V>> iterator =
                    mMap.entrySet().iterator();

                private Entry<K, V> next;

                @Override
                public boolean hasNext() {
                    if (next == null) prepareNext();
                    return (next != null);
                }

                @Override
                public Entry<K, V> next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    Entry<K, V> entry = next;
                    next = null;
                    return entry;
                }

                private void prepareNext() {
                    while (iterator.hasNext()) {
                        Entry<WeakReference<K>, V> entry = iterator.next();
                        K key = entry.getKey().get();
                        if (key != null) {
                            next = new AbstractMap.SimpleEntry<>(key, entry.getValue());
                            return;
                        }
                    }
                }
            };
        }
    }
}