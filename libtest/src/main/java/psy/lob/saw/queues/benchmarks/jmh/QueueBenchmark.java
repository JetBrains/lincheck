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

import psy.lob.saw.queues.common.SPSCQueueFactory;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

@State(Scope.Benchmark)
public abstract class QueueBenchmark {
	@Param(value={"11","12","21","22","23","24","25","31","32","33","41","42"})
	protected int queueType;
	@Param(value={"17"})
	protected int queueScale;
    protected static Queue<Integer> q;
    
    @Setup(Level.Trial)
    public void createQueue()
    {
    	q = SPSCQueueFactory.createQueue(queueType, queueScale);
    }
}
