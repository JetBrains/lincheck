package psy.lob.saw.queues.common;

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

import java.util.Iterator;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.GenerateMicroBenchmark;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode({ Mode.Throughput })
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Thread)
public class CircularArray2ReadWrite {
	public static final int CAPACITY = 1 << 15;
	public static final Integer TOKEN = 1;

	private CircularArrayQueue2<Integer> caq = new CircularArrayQueue2<Integer>(CAPACITY) {
		@Override
		public boolean offer(Integer e) {
			return false;
		}

		@Override
		public Integer poll() {
			return null;
		}

		@Override
		public Integer peek() {
			return null;
		}

		@Override
		public Iterator<Integer> iterator() {
			return null;
		}

		@Override
		public int size() {
			return 0;
		}
	};

	long index;

	@GenerateMicroBenchmark
	public void offer() {
		int offset = caq.calcOffset(index++);
		caq.spElement(offset, TOKEN);
	}

	@GenerateMicroBenchmark
	public void poll() {
		int offset = caq.calcOffset(index++);
		if (caq.lpElement(offset) != null) {
			index--;
		}
	}
}
