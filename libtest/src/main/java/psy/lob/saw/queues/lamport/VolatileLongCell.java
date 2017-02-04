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

import psy.lob.saw.queues.common.UnsafeAccess;


abstract class VolatileLongCellPrePad{long p0,p1,p2,p3,p4,p5,p6;}
abstract class VolatileLongCellValue extends VolatileLongCellPrePad {
    protected volatile long value;
}
public final class VolatileLongCell extends VolatileLongCellValue {
    long p10,p11,p12,p13,p14,p15,p16;
    private final static long VALUE_OFFSET;
    static {
        try {
            VALUE_OFFSET = UnsafeAccess.UNSAFE.objectFieldOffset(VolatileLongCellValue.class.getDeclaredField("value"));
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }
    public VolatileLongCell(){
        this(0L);
    }
    public VolatileLongCell(long v){
        lazySet(v);
    }
    public void lazySet(long v) {
        UnsafeAccess.UNSAFE.putOrderedLong(this, VALUE_OFFSET, v);
    }
    public void set(long v){
        this.value = v;
    }
    public long get(){
        return this.value;
    }
}
