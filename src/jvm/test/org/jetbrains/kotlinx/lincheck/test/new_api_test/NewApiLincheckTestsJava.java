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

package org.jetbrains.kotlinx.lincheck.test.new_api_test;

import org.jctools.queues.atomic.MpscLinkedAtomicQueue;
import org.jetbrains.kotlinx.lincheck.new_api.Lincheck;
import org.jetbrains.kotlinx.lincheck.new_api.Linearizability;
import org.jetbrains.kotlinx.lincheck.new_api.Operation;
import org.jetbrains.kotlinx.lincheck.new_api.QuiescentConsistency;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class NewApiLincheckTestsJava {

    @Test
    public void concurrentHashMapTest() {
        Lincheck.runLincheckTest(ConcurrentHashMap<Integer, String>::new, (map, cfg) -> {
            cfg.operation("put", (ConcurrentHashMap<Integer, String> m, Integer key, String element) -> m.put(key, element));
            cfg.operation("remove", (ConcurrentHashMap<Integer, String> m, Integer key) -> m.remove(key));
            cfg.operation("get", (ConcurrentHashMap<Integer, String> m, Integer key) -> m.get(key));

            cfg.operation("get", Map<Integer, String>::get);
            cfg.operation("put", Map<Integer, String>::put);
            cfg.operation1("remove", Map<Integer, String>::remove);

            cfg.setTestingTimeInSeconds(10);
            cfg.setSequentialSpecification(HashMap<Integer, String>::new);
        });
    }

    @Test
    public void multiProducerSingleConsumerQueueTest() {
        Lincheck.runLincheckTest(MpscLinkedAtomicQueue<Integer>::new, (q, cfg) -> {
            cfg.operation("offer", MpscLinkedAtomicQueue<Integer>::offer);
            Operation peek = cfg.operation("peek", MpscLinkedAtomicQueue<Integer>::peek);
            Operation poll = cfg.operation("poll", MpscLinkedAtomicQueue<Integer>::poll);
            cfg.nonParallel(peek, poll);
            cfg.setCheckObstructionFreedom(true);
            cfg.setCorrectnessProperty(QuiescentConsistency.INSTANCE);
            cfg.addValidationFunction("validationFunction", MpscLinkedAtomicQueue<Integer>::poll);
            cfg.addValidationFunction("validationFunction", (MpscLinkedAtomicQueue<Integer> q2) -> q2.poll());
        });
    }
}
