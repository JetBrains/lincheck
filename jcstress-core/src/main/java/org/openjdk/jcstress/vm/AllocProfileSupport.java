/*
 * Copyright (c) 2014, 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.jcstress.vm;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * This class encapsulates any platform-specific functionality. It is supposed
 * to gracefully fail if some functionality is not available. This class
 * resolves most special classes via Reflection to enable building against a
 * standard JDK.
 */
public class AllocProfileSupport {
    private static final boolean ALLOC_AVAILABLE;
    private static ThreadMXBean ALLOC_MX_BEAN;
    private static Method ALLOC_MX_BEAN_GETTER;
    private static Method THREAD_ID_GETTER;


    static {
        ALLOC_AVAILABLE = tryInitAlloc();
    }

    private static boolean tryInitAlloc() {
        try {
            Class<?> internalIntf = Class.forName("com.sun.management.ThreadMXBean");
            ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            if (!internalIntf.isAssignableFrom(bean.getClass())) {
                Class<?> pmo = Class.forName("java.lang.management.PlatformManagedObject");
                Method m = ManagementFactory.class.getMethod("getPlatformMXBean", Class.class, pmo);
                bean = (ThreadMXBean) m.invoke(null, internalIntf);
                if (bean == null) {
                    throw new UnsupportedOperationException("No way to access private ThreadMXBean");
                }
            }

            ALLOC_MX_BEAN = bean;
            ALLOC_MX_BEAN_GETTER = internalIntf.getMethod("getThreadAllocatedBytes", long.class);

            try {
                THREAD_ID_GETTER = Thread.class.getMethod("threadId");
            } catch (NoSuchMethodException nsme) {
                THREAD_ID_GETTER = Thread.class.getMethod("getId");
            }

            // Warm up until the difference drops to zero
            long last = -1;
            for (int t = 0; t < 10; t++) {
                if (last == -1) {
                    last = getAllocatedBytes();
                }
                long cur = getAllocatedBytes();
                if ((cur - last) == 0) break;
            }

            return true;
        } catch (Throwable e) {
            System.out.println("WARNING: Allocation profiling is not available: " + e.getMessage());
        }
        return false;
    }

    public static long getAllocatedBytes() {
        if (ALLOC_AVAILABLE) {
            try {
                long id = (long) THREAD_ID_GETTER.invoke(Thread.currentThread());
                return (long) ALLOC_MX_BEAN_GETTER.invoke(ALLOC_MX_BEAN, id);
            } catch (InvocationTargetException | IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        } else {
            return 0;
        }
    }

    public static boolean isAvailable() {
        return ALLOC_AVAILABLE;
    }

}
