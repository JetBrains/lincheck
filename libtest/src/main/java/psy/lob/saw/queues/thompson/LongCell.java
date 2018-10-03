package psy.lob.saw.queues.thompson;

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


abstract class LongCellP0{long p0,p1,p2,p3,p4,p5,p6;}
abstract class LongCellValue extends LongCellP0 {
    protected long value;
}
public final class LongCell extends LongCellValue {
    long p10,p11,p12,p13,p14,p15,p16;
    public void set(long v){ this.value = v; }
    public long get(){ return this.value; }
}
