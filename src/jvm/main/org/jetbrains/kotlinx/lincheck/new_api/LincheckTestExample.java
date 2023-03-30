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

package org.jetbrains.kotlinx.lincheck.new_api;

import java.util.concurrent.ConcurrentLinkedDeque;

public class LincheckTestExample {
    public void concurrentLinkedQueueTest() {
//        Lincheck.runLincheckTest(() -> new ConcurrentLinkedDeque<Integer>(), cfg -> {
//            var poll = cfg.operation(ConcurrentLinkedDeque::poll);
//            var peek = cfg.operation("peek", ConcurrentLinkedDeque::peek);
//            var offer = cfg.operation(ConcurrentLinkedDeque::offer); // WTF
//            offer.setBlocking(true);
//            offer.setCausesBlocking(true);
//            offer.setCancelOnSuspension(true);
//            cfg.nonParallel(poll, peek, offer); // WTF???
//            cfg.setTestingTimeInSeconds(30);
//            cfg.setCorrectnessProperty(Linearizability.INSTANCE);
//            cfg.setCheckObstructionFreedom(true);
//            cfg.setMaxThreads(5);
//            cfg.setMaxOperationsInThread(10);
//            cfg.setVerboseInterleavingTrace(true);
//            cfg.addCustomScenario(s -> {
//                s.thread(t -> {
//                    // TODO()
//                });
//                s.thread(t -> {
//                    // TODO()
//                });
//            });
//        });
    }
}
