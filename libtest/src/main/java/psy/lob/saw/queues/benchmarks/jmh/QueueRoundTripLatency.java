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

import java.util.Queue;
import java.util.concurrent.TimeUnit;

import psy.lob.saw.queues.common.SPSCQueueFactory;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.GenerateMicroBenchmark;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.logic.Control;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
public class QueueRoundTripLatency {
    private static final Integer DUMMY_MESSAGE = 1;
	@Param(value={"11","12","21","22","23","24","25","31","32","33","41","42"})
	protected int queueType;
	@Param(value={"17"})
	protected int queueScale;
    
	private final static int BURST_SIZE = Integer.getInteger("burst.size",1);

	private static volatile Queue<Integer> ping;
	protected static volatile Queue<Integer> pong;
	
	@Setup(Level.Trial)
    public void createPueue()
    {
    	ping = SPSCQueueFactory.createQueue(queueType, queueScale);
    	pong = SPSCQueueFactory.createQueue(queueType, queueScale);
    	// This is an estimate, but for bounded queues if the burst size is more than actual ring capacity
        // the benchmark will hang
//        if (burstSize > (1 << queueScale)) {
//            throw new IllegalArgumentException("Batch size exceeds estimated capacity");
//        }
    }

    @State(Scope.Thread)
    public static class Link {
        final Queue<Integer> in;
        final Queue<Integer> out;

        public Link() {
            this.in = ping;
            this.out = pong;
        }

        public void link() {
            Integer e = in.poll();
            if (e != null) {
                out.offer(e);
            }
        }

        /**
         * We want to always start with an empty inbound. Iteration tear downs are synchronized.
         */
        @TearDown(Level.Iteration)
        public void clear() {
            // SPSC -> consumer must clear the queue
            in.clear();
            ping = in;
            pong = out;
        }
    }

    @State(Scope.Thread)
    public static class Source {
        final Queue<Integer> start;
        final Queue<Integer> end;
        public Source() {
            this.end = pong;
            this.start = ping;
        }

        public void ping(Control ctl) {
            for (int i = 0; i < BURST_SIZE; i++) {
                start.offer(DUMMY_MESSAGE);
            }
            for (int i = 0; i < BURST_SIZE; i++) {
                while (!ctl.stopMeasurement && end.poll() == null) {
                }
            }
        }

        /**
         * We want to always start with an empty inbound. Iteration tear downs are synchronized.
         */
        @TearDown(Level.Iteration)
        public void clear() {
            // SPSC -> consumer must clear the queue
            end.clear();
            ping = start;
            pong = end;
        }
    }

    @GenerateMicroBenchmark
    @Group("ring")
    @GroupThreads(1)
    public void ping(Control ctl, Source s) {
        s.ping(ctl);
    }

    @GenerateMicroBenchmark
    @Group("ring")
    @GroupThreads(1)
    public void loop(Link l) {
        l.link();
    }
}
