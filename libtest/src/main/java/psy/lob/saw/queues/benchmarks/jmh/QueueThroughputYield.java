/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package psy.lob.saw.queues.benchmarks.jmh;

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

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.GenerateMicroBenchmark;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.logic.BlackHole;

@State(Scope.Group)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 3, timeUnit = TimeUnit.SECONDS)
public class QueueThroughputYield  extends QueueBenchmark {
    private static final long DELAY_PRODUCER = Long.getLong("delay.p", 0L);
    private static final long DELAY_CONSUMER = Long.getLong("delay.c", 0L);
    private static final Integer ONE = 777;
    
    @AuxCounters
    @State(Scope.Thread)
    public static class OpCounters {
        public int pollFail, offerFail;

        @Setup(Level.Iteration)
        public void clean() {
            pollFail = offerFail = 0;
        }
    }

    private static ThreadLocal<Object> marker = new ThreadLocal<>();

    @State(Scope.Thread)
    public static class ConsumerMarker {
        public ConsumerMarker() {
            marker.set(this);
        }
    }

    @GenerateMicroBenchmark
    @Group("tpt")
    public void offer(OpCounters counters) {
        if (!q.offer(ONE)) {
            counters.offerFail++;
            Thread.yield();
        } 
        if (DELAY_PRODUCER != 0) {
            BlackHole.consumeCPU(DELAY_PRODUCER);
        }
    }

    @GenerateMicroBenchmark
    @Group("tpt")
    public void poll(OpCounters counters, ConsumerMarker cm) {
        if (q.poll() == null) {
            counters.pollFail++;
            Thread.yield();
        } 
        if (DELAY_CONSUMER != 0) {
            BlackHole.consumeCPU(DELAY_CONSUMER);
        }
    }

    @TearDown(Level.Iteration)
    public void emptyQ() {
        if (marker.get() == null)
            return;
        // sadly the iteration tear down is performed from each participating thread, so we need to guess
        // which is which (can't have concurrent access to poll).
        while (q.poll() != null)
            ;
    }
}
