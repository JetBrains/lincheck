package psy.lob.saw.queues.common;

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

import java.util.AbstractQueue;

abstract class CircularArrayQueue4PrePad<E> extends AbstractQueue<E> {
    protected long p00, p01, p02, p03, p04, p05, p06, p07;
	protected long p10, p11, p12, p13, p14, p15, p16, p17;
}
public abstract class CircularArrayQueue4<E> extends CircularArrayQueue4PrePad<E> {
	private static final int BUFFER_PAD = 32;
	protected static final long ARRAY_BASE;
	protected static final int ELEMENT_SHIFT;
	static {
        final int scale = UnsafeAccess.UNSAFE.arrayIndexScale(Object[].class);

        if (4 == scale) {
            ELEMENT_SHIFT = 2;
        } else if (8 == scale) {
            ELEMENT_SHIFT = 3;
        } else {
            throw new IllegalStateException("Unknown pointer size");
        }
        ARRAY_BASE = UnsafeAccess.UNSAFE.arrayBaseOffset(Object[].class) + (BUFFER_PAD << ELEMENT_SHIFT);
	}
	private final int capacity;
	private final long mask;
	private final E[] buffer;

	@SuppressWarnings("unchecked")
	public CircularArrayQueue4(int capacity) {
		this.capacity = Pow2.findNextPositivePowerOfTwo(capacity);
		mask = capacity() - 1;
		// padding + size + padding
        buffer = (E[]) new Object[this.capacity + BUFFER_PAD * 2];
	}

	protected final void spElement(final long offset, final E e) {
		UnsafeAccess.UNSAFE.putObject(buffer, offset, e);
	}

	@SuppressWarnings("unchecked")
	protected final E lpElement(final long offset) {
		return (E) UnsafeAccess.UNSAFE.getObject(buffer, offset);
	}
	protected final void soElement(final long offset, final E e) {
		UnsafeAccess.UNSAFE.putOrderedObject(buffer, offset, e);
	}

	@SuppressWarnings("unchecked")
	protected final E lvElement(final long offset) {
		return (E) UnsafeAccess.UNSAFE.getObjectVolatile(buffer, offset);
	}
	protected final long calcOffset(final long index) {
		// inclusive of padding:
		// array base + (padding * slot size) + ((index % capacity) * (slot size)) =
		// ARRAY_BASE pre-calculated: ARRAY_BASE + ((index % capacity) * (slot size)) =
		// capacity is power of 2: ARRAY_BASE + ((index & mask) * (slot size)) =
		// slot size is a power of 2, replace with a shift of pre-calculated ELEMENT_SHIFT
		return ARRAY_BASE + ((index & mask) << ELEMENT_SHIFT);
	}

	protected final int capacity() {
		return capacity;
	}
}