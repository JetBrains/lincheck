/*
 * Copyright 2012 Real Logic Ltd.
 *
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
package psy.lob.saw.queues.thompson;

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

import psy.lob.saw.queues.lamport.VolatileLongCell;
import psy.lob.saw.queues.common.CircularArrayQueue4;

/**
 * <ul>
 * <li>Lock free, observing single writer principal (except for buffer).
 * <li>Replacing the long fields with AtomicLong and using lazySet instead of
 * volatile assignment.
 * <li>Using the power of 2 mask, forcing the capacity to next power of 2.
 * <li>Using a fully padded 'AtomicLong' like variable
 * <li>Fully padded circular array
 * <li>Use fully padded index cache fields
 * <li>Unsafe array access
 * </ul>
 */
abstract class ThompsonQueue2Fields<E> extends CircularArrayQueue4<E> {
	protected final VolatileLongCell producerIndex = new VolatileLongCell();
	protected final VolatileLongCell consumerIndex = new VolatileLongCell();
	protected final LongCell producerIndexCache = new LongCell();
	protected final LongCell consumerIndexCache = new LongCell();

	public ThompsonQueue2Fields(int capacity) {
	    super(capacity);
    }
	
}
public final class ThompsonQueue2<E>  extends ThompsonQueue2Fields<E> {
	protected long p00, p01, p02, p03, p04, p05, p06, p07;
	protected long p10, p11, p12, p13, p14, p15, p16, p17;
	public ThompsonQueue2(final int capacity) {
		super(capacity);
	}
	private long lvProducerIndex() {
		return producerIndex.get();
	}

	private void soProducerIndex(long index) {
		producerIndex.lazySet(index);
	}

	private long lvConsumerIndex() {
		return consumerIndex.get();
	}

	private void soConsumerIndex(long index) {
		consumerIndex.lazySet(index);
	}

	private long lpConsumerIndexCache() {
		return consumerIndexCache.get();
	}

	private void spConsumerIndexCache(long index) {
		consumerIndexCache.set(index);
	}

	private long lpProducerIndexCache() {
		return producerIndexCache.get();
	}

	private void spProducerIndexCache(long index) {
		producerIndexCache.set(index);
	}

	@Override
	public boolean offer(final E e) {
		if (null == e) {
			throw new NullPointerException("Null is not a valid element");
		}

		final long currentProducerIndex = lvProducerIndex();
		final long wrapPoint = currentProducerIndex - capacity();
		if (lpConsumerIndexCache() <= wrapPoint) {
			spConsumerIndexCache(lvConsumerIndex());
			if (lpConsumerIndexCache() <= wrapPoint) {
				return false;
			}
		}

		final long offset = calcOffset(currentProducerIndex);
		spElement(offset, e);
		soProducerIndex(currentProducerIndex + 1);
		return true;
	}

	@Override
	public E poll() {
		final long currentConsumerIndex = lvConsumerIndex();
		if (currentConsumerIndex >= lpProducerIndexCache()) {
			spProducerIndexCache(lvProducerIndex());
			if (currentConsumerIndex >= lpProducerIndexCache()) {
				return null;
			}
		}

		final long offset = calcOffset(currentConsumerIndex);
		final E e = lpElement(offset);
		spElement(offset, null);
		soConsumerIndex(currentConsumerIndex + 1);
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