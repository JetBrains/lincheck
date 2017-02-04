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
package psy.lob.saw.queues.ff;

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

/**
 * <ul>
 * <li>Inlined counters
 * <li>Counters are padded
 * <li>Data is padded
 * <li>Class is pre-padded
 * <li>Use Unsafe for array access
 * </ul>
 */
abstract class FastFlowQueue2L1Pad<E> extends CircularArrayQueue4<E> {
	protected long p00, p01, p02, p03, p04, p05, p06, p07;
	protected long p10, p11, p12, p13, p14, p15, p16, p17;

	public FastFlowQueue2L1Pad(int capacity) {
		super(capacity);
	}
}

abstract class FastFlowQueue2TailField<E> extends FastFlowQueue2L1Pad<E> {
	protected long consumerIndex;

	public FastFlowQueue2TailField(int capacity) {
		super(capacity);
	}
}

abstract class FastFlowQueue2L3Pad<E> extends FastFlowQueue2TailField<E> {
	protected long p00, p01, p02, p03, p04, p05, p06, p07;
	protected long p10, p11, p12, p13, p14, p15, p16, p17;

	public FastFlowQueue2L3Pad(int capacity) {
		super(capacity);
	}
}

abstract class FastFlowQueue2HeadField<E> extends FastFlowQueue2L3Pad<E> {
	protected long producerIndex;
	protected long lookAheadCache;

	public FastFlowQueue2HeadField(int capacity) {
		super(capacity);
	}

}

public final class FastFlowQueue2<E> extends FastFlowQueue2HeadField<E> {
	protected long p00, p01, p02, p03, p04, p05, p06, p07;
	protected long p10, p11, p12, p13, p14, p15, p16, p17;
	protected static final int OFFER_LOOK_AHEAD = Integer.getInteger("offer.batch.size", 4096);

	public FastFlowQueue2(int capacity) {
		super(Math.min(capacity, OFFER_LOOK_AHEAD * 2));
	}

	private void incConsumerIndex() {
		consumerIndex++;
	}

	@Override
	public boolean offer(final E e) {
		if (null == e) {
			throw new NullPointerException("Null is not a valid element");
		}

		if (lookAheadCache < producerIndex) {
			long lookAheadOffset = calcOffset(producerIndex + OFFER_LOOK_AHEAD);
			if (null != lvElement(lookAheadOffset)) { // LoadLoad
				return false;
			} else {
				lookAheadCache = producerIndex + OFFER_LOOK_AHEAD;
			}
		}
		final long offset = calcOffset(producerIndex);
		soElement(offset, e); // StoreStore
		producerIndex++;
		return true;
	}

	@Override
	public E poll() {
		final long offset = calcOffset(consumerIndex);
		final E e = lvElement(offset); // LoadLoad
		if (null == e) {
			return null;
		}
		soElement(offset, null); // StoreStore
		incConsumerIndex();
		return e;
	}

	@Override
	public E peek() {
		final long offset = calcOffset(consumerIndex);
		return lvElement(offset);
	}

	@Override
	public int size() {
		// This won't work very well :(
		return (int) (producerIndex - consumerIndex);
	}

	@Override
	public Iterator<E> iterator() {
		throw new UnsupportedOperationException();
	}
}
