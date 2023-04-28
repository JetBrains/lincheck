/*
 * Copyright 2017 Kjell Winblad (kjellwinblad@gmail.com, http://winsh.me).
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package CATreeMapAVL;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

import sun.misc.Unsafe;

/**
 * Objects of this class are used by the CA tree base node
 * implementation in "DualLFCASAVLTreeMapSTD.java". Please see that
 * file for details. This class implements a sequence lock. Refer to
 * <a href="https://www.kernel.org/pub/linux/kernel/people/christoph/gelato/gelato2005-paper.pdf">https://www.kernel.org/pub/linux/kernel/people/christoph/gelato/gelato2005-paper.pdf</a>
 * for more information about sequence locks.
 * 
 * @author Kjell Winblad
 */
public class SeqLock {
	
    volatile long seqNumber = 2L;
    private int statLockStatistics = 0;
    
    private static final AtomicLongFieldUpdater<SeqLock> seqNumberUpdater =
        AtomicLongFieldUpdater.newUpdater(SeqLock.class, "seqNumber");
    
    private static final Unsafe unsafe;

    private static final int STAT_LOCK_HIGH_CONTENTION_LIMIT = 1000;
    private static final int STAT_LOCK_LOW_CONTENTION_LIMIT = -1000;
    private static final int STAT_LOCK_FAILURE_CONTRIB = 250;
    private static final int STAT_LOCK_SUCCESS_CONTRIB = 1;
    
    static {
        try {
            Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            unsafe = (Unsafe) theUnsafe.get(null);
        } catch (Exception ex) { 
            throw new Error(ex);
        }
    }
    
    public boolean tryLock() {
    	long readSeqNumber = seqNumber;
        if((readSeqNumber % 2) != 0){
            return false;
        }else{
            boolean success = seqNumberUpdater.compareAndSet(this, readSeqNumber, readSeqNumber + 1);
            if(success){
                return true;
            }else{
                return false;
            }
        }
    }
    
    public void lock(){
    	while(true){
            long readSeqNumber = seqNumber;	
            while((readSeqNumber % 2) != 0){
                unsafe.fullFence();
                unsafe.fullFence();
                readSeqNumber = seqNumber;
            }
            if(seqNumberUpdater.compareAndSet(this, readSeqNumber, readSeqNumber + 1)){
                break;
            }
    	}
    }

    public void unlock(){
    	seqNumber = seqNumber + 1;
    }
    
    public boolean isWriteLocked(){
    	return (seqNumber % 2) != 0;
    }
    

    public long tryOptimisticRead() {
        long readSeqNumber = seqNumber;
        if((readSeqNumber % 2) != 0){
            return 0;
        }else{
            return readSeqNumber;
        }
    }

    public boolean validate(long optimisticReadToken) {
        unsafe.loadFence();
        long readSeqNumber = seqNumber;
        return readSeqNumber == optimisticReadToken;
    }
	
	
    public void lockUpdateStatistics(){
        if (tryLock()) {
            statLockStatistics -= STAT_LOCK_SUCCESS_CONTRIB;
            return;
        }
        lock();
        statLockStatistics += STAT_LOCK_FAILURE_CONTRIB;
    }

    public void addToContentionStatistics(){
        statLockStatistics += STAT_LOCK_FAILURE_CONTRIB;
    }

    public void subFromContentionStatistics(){
        statLockStatistics -= STAT_LOCK_SUCCESS_CONTRIB;
    }

    
    public int getLockStatistics(){
    	return statLockStatistics;
    }
    
    public void resetStatistics(){
        statLockStatistics = 0;
    }

    public boolean isHighContentionLimitReached(){
        return statLockStatistics > STAT_LOCK_HIGH_CONTENTION_LIMIT;
    }
    
    public boolean isLowContentionLimitReached(){
        return statLockStatistics < STAT_LOCK_LOW_CONTENTION_LIMIT;
    }
    
}
