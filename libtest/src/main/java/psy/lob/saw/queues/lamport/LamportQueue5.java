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
package psy.lob.saw.queues.lamport;

/*
 * #%L
 * libtest
 * %%
 * Copyright (C) 2015 - 2017 Devexperts, LLC
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

import psy.lob.saw.queues.common.CircularArrayQueue4;
import psy.lob.saw.queues.common.UnsafeAccess;

/**
 * <ul>
 * <li>Inlined counters
 * <li>Counters are padded
 * <li>Data is padded
 * <li>Class is pre-padded
 * <li>Use Unsafe for array access
 * </ul>
 */
abstract class LamportQueue5L1Pad<E> extends CircularArrayQueue4<E> {
	protected long p00, p01, p02, p03, p04, p05, p06, p07;
	protected long p10, p11, p12, p13, p14, p15, p16, p17;

	public LamportQueue5L1Pad(int capacity) {
		super(capacity);
	}
}

abstract class LamportQueue5ConsumerIndex<E> extends LamportQueue5L1Pad<E> {
	protected volatile long consumerIndex;

	public LamportQueue5ConsumerIndex(int capacity) {
		super(capacity);
	}
}

abstract class LamportQueue5L3Pad<E> extends LamportQueue5ConsumerIndex<E> {
	protected long p00, p01, p02, p03, p04, p05, p06, p07;
	protected long p10, p11, p12, p13, p14, p15, p16, p17;

	public LamportQueue5L3Pad(int capacity) {
		super(capacity);
	}
}

abstract class LamportQueue5ProducerIndex<E> extends LamportQueue5L3Pad<E> {
	protected volatile long producerIndex;

	public LamportQueue5ProducerIndex(int capacity) {
		super(capacity);
	}
}

public final class LamportQueue5<E> extends LamportQueue5ProducerIndex<E> {
	protected long p00, p01, p02, p03, p04, p05, p06, p07;
	protected long p10, p11, p12, p13, p14, p15, p16, p17;
	private final static long CONSUMER_INDEX_OFFSET;
	private final static long PRODUCER_INDEX_OFFSET;
	static {
		try {
			CONSUMER_INDEX_OFFSET = UnsafeAccess.UNSAFE.objectFieldOffset(LamportQueue5ConsumerIndex.class.getDeclaredField("consumerIndex"));
			PRODUCER_INDEX_OFFSET = UnsafeAccess.UNSAFE.objectFieldOffset(LamportQueue5ProducerIndex.class.getDeclaredField("producerIndex"));
		} catch (NoSuchFieldException e) {
			throw new RuntimeException(e);
		}
	}
	public LamportQueue5(int capacity) {
	    super(capacity);
    }

	private long lvProducerIndex() {
		return producerIndex;
	}

	private void soProducerIndex(long index) {
		UnsafeAccess.UNSAFE.putOrderedLong(this, PRODUCER_INDEX_OFFSET, index);
	}

	private long lvConsumerIndex() {
		return consumerIndex;
	}

	private void soConsumerIndex(long index) {
		UnsafeAccess.UNSAFE.putOrderedLong(this, CONSUMER_INDEX_OFFSET, index);
	}

	@Override
	public boolean offer(final E e) {
		if (null == e) {
			throw new NullPointerException("Null is not a valid element");
		}

		final long currentProducerIndex = lvProducerIndex(); // LoadLoad
		final long wrapPoint = currentProducerIndex - capacity();
		if (lvConsumerIndex() <= wrapPoint) { // LoadLoad
			return false;
		}

		final long offset = calcOffset(currentProducerIndex);
		spElement(offset, e);
		soProducerIndex(currentProducerIndex + 1); // StoreStore
		return true;
	}

	@Override
	public E poll() {
		final long currentConsumerIndex = lvConsumerIndex(); // LoadLoad
		if (currentConsumerIndex >= lvProducerIndex()) { // LoadLoad
			return null;
		}

		final long offset = calcOffset(currentConsumerIndex);
		final E e = lpElement(offset);
		spElement(offset, null);
		soConsumerIndex(currentConsumerIndex + 1); // StoreStore
		return e;
	}

	@Override
	public E peek() {
		final long offset = calcOffset(lvConsumerIndex());
		return lpElement(offset);
	}

	@Override
	public int size() {
		return (int) (lvProducerIndex() - lvConsumerIndex());
	}

	@Override
	public Iterator<E> iterator() {
		throw new UnsupportedOperationException();
	}
}
