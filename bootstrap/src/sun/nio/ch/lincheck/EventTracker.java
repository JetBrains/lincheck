/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package sun.nio.ch.lincheck;

import java.util.*;

/**
 * Methods of this interface are called from the instrumented tested code during model-checking.
 * See {@link Injections} for the documentation.
 */
public interface EventTracker {

    void beforeLock(int codeLocation);
    void lock(Object monitor);
    void unlock(Object monitor, int codeLocation);

    void park(int codeLocation);
    void unpark(Thread thread, int codeLocation);

    void beforeWait(int codeLocation);
    void wait(Object monitor, boolean withTimeout);
    void notify(Object monitor, int codeLocation, boolean notifyAll);

    void beforeNewObjectCreation(String className);
    void afterNewObjectCreation(Object obj);

    void updateSnapshotBeforeConstructorCall(Object[] objs);

    boolean beforeReadField(Object obj, String className, String fieldName, int codeLocation,
                            boolean isStatic, boolean isFinal);
    boolean beforeReadArrayElement(Object array, int index, int codeLocation);
    void afterRead(Object value);

    boolean beforeWriteField(Object obj, String className, String fieldName, Object value, int codeLocation,
                             boolean isStatic, boolean isFinal);
    boolean beforeWriteArrayElement(Object array, int index, Object value, int codeLocation);
    void afterWrite();

    void beforeMethodCall(Object owner, String className, String methodName, int codeLocation, int methodId, Object[] params);
    void onMethodCallReturn(Object result);
    void onMethodCallException(Throwable t);

    Random getThreadLocalRandom();
    int randomNextInt();

    // Methods required for the plugin integration

    boolean shouldInvokeBeforeEvent();
    void beforeEvent(int eventId, String type);
    int getEventId();
    void setLastMethodCallEventId();
}