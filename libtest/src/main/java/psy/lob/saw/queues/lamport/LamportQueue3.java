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
import java.util.concurrent.atomic.AtomicLong;

import psy.lob.saw.queues.common.CircularArrayQueue2;

/**
 * <ul>
 * <li>Lock free, observing single writer principal (except for buffer).
 * <li>Replacing the long fields with AtomicLong and using lazySet instead of
 * volatile assignment.
 * <li>Using the power of 2 mask, forcing the capacity to next power of 2.
 * </ul>
 */
public final class LamportQueue3<E> extends CircularArrayQueue2<E> {
	private final AtomicLong producerIndex = new AtomicLong();
	private final AtomicLong consumerIndex = new AtomicLong();
	public LamportQueue3(final int capacity) {
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

		final int offset = calcOffset(currentProducerIndex);
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

		final int offset = calcOffset(currentConsumerIndex);
		final E e = lpElement(offset);
		spElement(offset, null);
		soConsumerIndex(currentConsumerIndex + 1); // StoreStore
		return e;
	}

	@Override
	public E peek() {
		final int offset = calcOffset(lvConsumerIndex());
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