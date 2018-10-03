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

import java.util.AbstractQueue;
abstract class CircularArrayQueue3PrePad<E> extends AbstractQueue<E> {
    protected long p00, p01, p02, p03, p04, p05, p06, p07;
	protected long p10, p11, p12, p13, p14, p15, p16, p17;
}

public abstract class CircularArrayQueue3<E> extends CircularArrayQueue3PrePad<E> {
	private final int capacity;
	private final int mask;
	private final E[] buffer;

	@SuppressWarnings("unchecked")
	public CircularArrayQueue3(int capacity) {
		this.capacity = Pow2.findNextPositivePowerOfTwo(capacity);
		mask = capacity() - 1;
		buffer = (E[]) new Object[capacity];
	}

	protected final void spElement(int offset, final E e) {
		buffer[offset] = e;
	}

	protected final E lpElement(final int offset) {
		return buffer[offset];
	}

	protected final int calcOffset(final long index) {
		return ((int) index) & mask;
	}

	protected final int capacity() {
		return capacity;
	}

}