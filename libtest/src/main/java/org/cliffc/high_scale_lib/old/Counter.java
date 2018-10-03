/*
 * Written by Cliff Click and released to the public domain, as explained at
 * http://creativecommons.org/licenses/publicdomain
 */

package org.cliffc.high_scale_lib.old;

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

/**
 * A simple high-performance counter.  Merely renames the extended {@link
 * ConcurrentAutoTable} class to be more obvious.
 * {@link ConcurrentAutoTable} already has a decent
 * counting API.
 *
 * @since 1.5
 * @author Cliff Click
 */

public class Counter extends ConcurrentAutoTable {

  // Add the given value to current counter value.  Concurrent updates will
  // not be lost, but addAndGet or getAndAdd are not implemented because but
  // the total counter value is not atomically updated.
  //public void add( long x );
  //public void decrement();
  //public void increment();

  // Current value of the counter.  Since other threads are updating furiously
  // the value is only approximate, but it includes all counts made by the
  // current thread.  Requires a pass over all the striped counters.
  //public long get();
  //public int  intValue();
  //public long longValue();

  // A cheaper 'get'.  Updated only once/millisecond, but fast as a simple
  // load instruction when not updating.
  //public long estimate_get( );

}

