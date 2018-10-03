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

import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.GenerateMicroBenchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@OutputTimeUnit(TimeUnit.NANOSECONDS)
@BenchmarkMode({Mode.AverageTime})
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Thread)
public class SingleThreadedPoll extends QueueBenchmark
{
    public static final int CAPACITY = 1 << 14;
    public static final Integer TOKEN = 1;
   
    @Setup(Level.Invocation)
    public void fill()
    {
        for( int i=0; i<CAPACITY; i++)
        {
            q.offer(TOKEN);
        }
    }

    @GenerateMicroBenchmark
    @OperationsPerInvocation(CAPACITY)
    public void poll()
    {
        final Queue<Integer> lq = q;
        for (int i = 0; i < CAPACITY; i++)
        {
            lq.poll();
        }
    }
}
