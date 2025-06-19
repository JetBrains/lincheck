/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package sun.nio.ch.lincheck;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;

public class WeakIdentityReference<K> extends WeakReference<K> {
        private final int identityHashCode;

        public WeakIdentityReference(K key) {
            super(key);
            identityHashCode = System.identityHashCode(key);
        }

        public WeakIdentityReference(K key, ReferenceQueue<Object> refQueue) {
            super(key, refQueue);
            identityHashCode = System.identityHashCode(key);
        }

        @Override
        public boolean equals(Object o) {
            if (o == this) {
                return true;
            }
            K k = get();
            if (k != null && o instanceof WeakIdentityReference) {
                return ((WeakIdentityReference) o).get() == k;
            }
            return false;
        }

        @Override
        public int hashCode() {
            return identityHashCode;
        }
    }
