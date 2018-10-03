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

import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;

import psy.lob.saw.queues.lamport.LamportQueue1;
import psy.lob.saw.queues.ff.FastFlowQueue1;
import psy.lob.saw.queues.ff.FastFlowQueue2;
import psy.lob.saw.queues.lamport.LamportQueue2;
import psy.lob.saw.queues.lamport.LamportQueue3;
import psy.lob.saw.queues.lamport.LamportQueue4;
import psy.lob.saw.queues.thompson.ThompsonQueue1;
import psy.lob.saw.queues.thompson.ThompsonQueue2;
import psy.lob.saw.queues.thompson.ThompsonQueue3;
import psy.lob.saw.queues.lamport.LamportQueue5;

public final class SPSCQueueFactory {

    public static Queue<Integer> createQueue(int qId, int qScale) {
        int qCapacity = 1 << qScale;
        switch (qId) {
        case 11:
            return new ArrayBlockingQueue<Integer>(qCapacity);
        case 12:
            return new ConcurrentLinkedQueue<Integer>();
        case 21:
            return new LamportQueue1<Integer>(qCapacity);
        case 22:
            return new LamportQueue2<Integer>(qCapacity);
        case 23:
            return new LamportQueue3<Integer>(qCapacity);
        case 24:
            return new LamportQueue4<Integer>(qCapacity);
        case 25:
            return new LamportQueue5<Integer>(qCapacity);
        case 31:
            return new ThompsonQueue1<Integer>(qCapacity);
        case 32:
            return new ThompsonQueue2<Integer>(qCapacity);
        case 33:
            return new ThompsonQueue3<Integer>(qCapacity);
        case 41:
            return new FastFlowQueue1<Integer>(qCapacity);
        case 42:
            return new FastFlowQueue2<Integer>(qCapacity);
        default:
            throw new IllegalArgumentException("Invalid option: " + qId);
        }
    }

}
