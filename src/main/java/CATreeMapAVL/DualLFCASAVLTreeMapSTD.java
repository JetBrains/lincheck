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

import java.util.Random;
import java.lang.reflect.Field;
import java.util.*;
import java.io.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.*;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.concurrent.locks.StampedLock;
import java.nio.ByteBuffer;
import sun.misc.Unsafe;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Objects of this class are used by the CA tree implementation in
 * "CATreeMapAVL.java". Please see that file for details. This class
 * implements necessary functionality for a CA tree base node with AVL
 * trees as sequential data structure.
 * 
 * @author Kjell Winblad
 */
public class DualLFCASAVLTreeMapSTD<K, V> extends AbstractMap<K,V> implements SplitableAndJoinableMap<K, V>, Invalidatable, AnyKeyProviding<K>{


    // ===== Fields =================
    
    private final SeqLock lock = new SeqLock();
    private volatile boolean valid = true;
    private int size = 0;
    //Use setRoot and getRoot to access the root
    private volatile STDAVLNodeDCAS<K,V> theRoot = null;
    private Object parent = null;
    private final Comparator<? super K> comparator;
    private static final AtomicReferenceFieldUpdater<DualLFCASAVLTreeMapSTD, STDAVLNodeDCAS> rootUpdater =
        AtomicReferenceFieldUpdater.newUpdater(DualLFCASAVLTreeMapSTD.class, STDAVLNodeDCAS.class, "theRoot");
    private volatile STDAVLNodeDCAS<K,V> lockFreeHolder = null;
    private byte[] localStatisticsArray = null;
    
    // ==============================

    // ===== Constants ==============

    private static final Unsafe unsafe;

    static {
        try {
            Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            unsafe = (Unsafe) theUnsafe.get(null);
        } catch (Exception ex) { 
            throw new Error(ex);
        }
    }    
    
    private final static boolean DEBUG = false;
    
    private static final int STAT_LOCK_HIGH_CONTENTION_LIMIT = 1000;
    private static final int STAT_LOCK_LOW_CONTENTION_LIMIT = -1000;
    private static final int STAT_LOCK_FAILURE_CONTRIB = 250;
    private static final int STAT_LOCK_SUCCESS_CONTRIB = 1;
    private static final boolean LOCK_FREE_FALSE_SHARING_SAFETY = false;

    private static final int NUMBER_OF_LOCAL_STATISTICS_SLOTS = Runtime.getRuntime().availableProcessors();
    private static final long LOCAL_STATISTICS_ARRAY_WRITE_INDICATOR_OFFSET = 0;
    private static final long LOCAL_STATISTICS_ARRAY_SIZE_OFFSET = 8;
    private static final long LOCAL_STATISTICS_ARRAY_START_OFFSET = 
        128 + unsafe.ARRAY_BYTE_BASE_OFFSET;
    private static final long LOCAL_STATISTICS_ARRAY_SLOT_SIZE = 192;

    // ==============================

    // ==== Debugging and Testing ====
    
    final private int avlValidateP(STDAVLNodeDCAS<K,V> toTest){
        
        if(toTest != null && toTest.parent != null){
            System.out.println("Parent should be null\n");
            printDot(getRoot(), "parent_should_be_null");
            throw new RuntimeException();
        }
        return avlValidate(toTest);
    }
    final private int avlValidate(STDAVLNodeDCAS<K,V> toTest){
        if(toTest == null){
            return 0;
        }else{
            int hl = avlValidate(toTest.getLeft());
            if(toTest.getLeft() != null && toTest.getLeft().parent != toTest){
                System.out.println("WRONG PARENT\n");
                printDot(getRoot(), "wrong_parent");
                throw new RuntimeException();
            }
            int hr = avlValidate(toTest.getRight());
            if(toTest.getRight() != null && toTest.getRight().parent != toTest){
                System.out.println("WRONG PARENT\n");
                printDot(getRoot(), "wrong_parent");
                throw new RuntimeException();
            }
            if(toTest.balance == 0 && hl != hr){
                System.out.println("FAIL 1 "+hl+" " +hr+"\n");
                printDot(getRoot(), "fail1");
                throw new RuntimeException();
            }else if(toTest.balance == -1 && (hr - hl) != -1){
                System.out.println("FAIL 2\n");
                printDot(getRoot(), "fail2");
                throw new RuntimeException();
            }else if(toTest.balance == 1 && (hr - hl) != 1){
                System.out.println("FAIL 3 "+(hr - hl)+"\n");
                printDot(getRoot(), "fail3");
                throw new RuntimeException();
            }else if(toTest.balance > 1 || toTest.balance < -1){
                System.out.println("FAIL 4\n");
                printDot(getRoot(), "fail4");
                throw new RuntimeException();
            }
            if(hl > hr){
                return hl + 1;
            }else{
                return hr + 1;
            }
        }
    }

    void printDotHelper(STDAVLNodeDCAS<K,V> node, PrintStream writeTo){
        Random rand = new Random();
        try{
            if(node!=null){
                if(node.getLeft() !=null){
                    writeTo.print("\"" + node.getValue() + ", " + node.balance + ", " + (node.parent != null ? node.parent.key : null) + " \"");
                    writeTo.print(" -> ");
                    writeTo.print("\"" + node.getLeft().getValue() + ", " + node.getLeft().balance + ", " + (node.getLeft().parent != null ? node.getLeft().parent.key : null) + " \"");
                    writeTo.println(";");
                }else{
                    writeTo.print("\"" + node.getValue() + ", " + node.balance + ", " + (node.parent != null ? node.parent.key : null) + " \"");
                    writeTo.print(" -> ");
                    writeTo.print("\"" + null+ ", " + rand.nextInt() + " \"");
                    writeTo.println(";");
                }
                if(node.getRight() !=null){
                    writeTo.print("\"" + node.getValue() + ", " + node.balance + ", " + (node.parent != null ? node.parent.key : null) + " \"");
                    writeTo.print(" -> ");
                    writeTo.print("\"" + node.getRight().getValue() + ", " + node.getRight().balance + ", " + (node.getRight().parent != null ? node.getRight().parent.key : null) + " \"");
                    writeTo.println(";");
                }else{
                    writeTo.print("\"" + node.getValue() + ", " + node.balance + ", " + (node.parent != null ? node.parent.key : null) + " \"");
                    writeTo.print(" -> ");
                    writeTo.print("\"" + null+ ", " + rand.nextInt() + " \"");
                    writeTo.println(";");
                }
                printDotHelper(node.getLeft(), writeTo);
                printDotHelper(node.getRight(), writeTo);
            }
        }catch(Exception e){
            e.printStackTrace();
        }
    }


    void printDot(STDAVLNodeDCAS<K,V> node, String fileName){
        try{
            Process p = new ProcessBuilder("dot", "-Tsvg")
                .redirectOutput(ProcessBuilder.Redirect.to(new File(fileName + ".svg")))
                .start();
            PrintStream writeTo = new PrintStream(p.getOutputStream());
            writeTo.print("digraph G{\n");
            writeTo.print("  graph [ordering=\"out\"];\n");
            printDotHelper(node, writeTo);
            writeTo.print("}\n");
            writeTo.close();
            p.waitFor();
        }catch(Exception e){
            e.printStackTrace();
        }
    }

    // ============================
 

    // === Constructors ===========

    public DualLFCASAVLTreeMapSTD() {
        comparator = null;
    }

    public DualLFCASAVLTreeMapSTD(Comparator<? super K> comparator) {
        this.comparator = comparator;
    }

    // ============================

    // === Helper Methods =========

    private boolean lfMapForUs(STDAVLNodeDCAS<K,V> lfMap, K key){
        if(comparator != null){
            return comparator.compare(key, lfMap.getKey()) == 0;
        }else{
            @SuppressWarnings("unchecked")
                Comparable<? super K> keyComp = (Comparable<? super K>) key;
            return keyComp.compareTo((K)lfMap.getKey() ) == 0;
        }
    }

    private final int computeHeight(){
        STDAVLNodeDCAS<K,V> r = getRoot();
        if(r == null){
            return 0;
        } else {
            STDAVLNodeDCAS<K,V> currentNode = r;
            int hightSoFar = 1;
            while(currentNode.getLeft() != null || currentNode.getRight() != null){
                if(currentNode.balance == -1){
                    currentNode = currentNode.getLeft();
                }else{
                    currentNode = currentNode.getRight();
                }
                hightSoFar = hightSoFar + 1;
            }
            return hightSoFar;
        }
    }

    private final K minKey(){
        STDAVLNodeDCAS<K,V> currentNode = getRoot();
        if(currentNode == null){
            return null;
        }
        while(currentNode.getLeft() != null){
            currentNode = currentNode.getLeft();
        }
        return currentNode.key;
    }


    private final K maxKey(){
        STDAVLNodeDCAS<K,V> currentNode = getRoot();
        if(currentNode == null){
            return null;
        }
        while(currentNode.getRight() != null){
            currentNode = currentNode.getRight();
        }
        return currentNode.key;
    }

    private int countNodes(STDAVLNodeDCAS n){
        if(n == null) return 0;
        else return 1 + countNodes(n.getLeft()) + countNodes(n.getRight());
    }

    final private STDAVLNodeDCAS<K,V> getSTDAVLNodeDCASUsingComparator(Object keyParam) {
        @SuppressWarnings("unchecked")
            K key = (K) keyParam;
        STDAVLNodeDCAS<K,V> currentNode = getRoot();
        Comparator<? super K> cpr = comparator;
        while(currentNode != null){
            K nodeKey = currentNode.key;
            int compareValue = cpr.compare(key,nodeKey);
            if(compareValue < 0) {
                currentNode = currentNode.getLeft();
            } else if (compareValue > 0) {
                currentNode = currentNode.getRight();
            } else {
                return currentNode;
            }
        }
        return null;
    }

    final private STDAVLNodeDCAS<K,V> getSTDAVLNodeDCAS(Object keyParam){
        if(comparator != null){
            return getSTDAVLNodeDCASUsingComparator(keyParam);
        }else{
            @SuppressWarnings("unchecked")
                Comparable<? super K> key = (Comparable<? super K>) keyParam;
            STDAVLNodeDCAS<K,V> currentNode = getRoot();
            while(currentNode != null){
                K nodeKey = currentNode.key;
                int compareValue = key.compareTo(nodeKey);
                if(compareValue < 0) {
                    currentNode = currentNode.getLeft();
                } else if (compareValue > 0) {
                    currentNode = currentNode.getRight();
                } else {
                    return currentNode;
                }
            }
            return null;
        }
    }

    private void resetLocalStatisticsArray(){
        for(int i = 0; i < NUMBER_OF_LOCAL_STATISTICS_SLOTS; i++){
            long sizeOffset = 
                LOCAL_STATISTICS_ARRAY_START_OFFSET + 
                i * LOCAL_STATISTICS_ARRAY_SLOT_SIZE +
                LOCAL_STATISTICS_ARRAY_SIZE_OFFSET;
            
            unsafe.putLong(localStatisticsArray, sizeOffset, 0);
        }
    }

    final private void rotateLeft(STDAVLNodeDCAS<K,V> prevNode){
        STDAVLNodeDCAS<K,V> leftChild = prevNode.getLeft();
        STDAVLNodeDCAS<K,V> prevNodeParent = prevNode.parent;
        prevNode.setLeft(leftChild.getRight());
        if(prevNode.getLeft() != null){
            prevNode.getLeft().parent = prevNode;
        }
        leftChild.setRight(prevNode);
        prevNode.parent = leftChild;
        prevNode.balance = 0;
        if(prevNodeParent == null){
            setRoot(leftChild);
        }else if(prevNodeParent.getLeft() == prevNode){
            prevNodeParent.setLeft(leftChild);
        }else{
            prevNodeParent.setRight(leftChild);
        }
        leftChild.parent = prevNodeParent;
        leftChild.balance = 0;
    }

    final private void rotateRight(STDAVLNodeDCAS<K,V> prevNode){
        STDAVLNodeDCAS<K,V> rightChild = prevNode.getRight();
        STDAVLNodeDCAS<K,V> prevNodeParent = prevNode.parent;
        prevNode.setRight(rightChild.getLeft());
        if(prevNode.getRight() != null){
            prevNode.getRight().parent = prevNode;
        }
        rightChild.setLeft(prevNode);
        prevNode.parent = rightChild;
        prevNode.balance = 0;
        if(prevNodeParent == null){
            setRoot(rightChild);
        }else if(prevNodeParent.getLeft() == prevNode){
            prevNodeParent.setLeft(rightChild);
        }else{
            prevNodeParent.setRight(rightChild);
        }
        rightChild.parent = prevNodeParent;
        rightChild.balance = 0;
    }


    final private void rotateDoubleRight(STDAVLNodeDCAS<K,V> prevNode){
        STDAVLNodeDCAS<K,V> prevNodeParent = prevNode.parent;
        STDAVLNodeDCAS<K,V> leftChild = prevNode.getLeft();
        STDAVLNodeDCAS<K,V> leftChildRightChild = leftChild.getRight();

        leftChild.setRight(leftChildRightChild.getLeft());
        if(leftChildRightChild.getLeft() != null){
            leftChildRightChild.getLeft().parent = leftChild;
        }

        leftChildRightChild.setLeft(leftChild);
        leftChild.parent = leftChildRightChild;

        prevNode.setLeft(leftChildRightChild.getRight());
        if(leftChildRightChild.getRight() != null){
            leftChildRightChild.getRight().parent = prevNode;
        }
        leftChildRightChild.setRight(prevNode);
        prevNode.parent = leftChildRightChild;

        prevNode.balance = (leftChildRightChild.balance == -1) ? +1 : 0;
        leftChild.balance = (leftChildRightChild.balance == 1) ? -1 : 0;
        if(prevNodeParent == null){
            setRoot(leftChildRightChild);
        }else if(prevNodeParent.getLeft() == prevNode){
            prevNodeParent.setLeft(leftChildRightChild);
        }else{
            prevNodeParent.setRight(leftChildRightChild);
        }
        leftChildRightChild.parent = prevNodeParent;
        leftChildRightChild.balance = 0;
    }

    final private void rotateDoubleLeft(STDAVLNodeDCAS<K,V> prevNode){
        STDAVLNodeDCAS<K,V> prevNodeParent = prevNode.parent;
        STDAVLNodeDCAS<K,V> rightChild = prevNode.getRight();
        STDAVLNodeDCAS<K,V> rightChildLeftChild = rightChild.getLeft();
        rightChild.setLeft(rightChildLeftChild.getRight());
        if(rightChildLeftChild.getRight() != null){
            rightChildLeftChild.getRight().parent = rightChild;
        }

        rightChildLeftChild.setRight(rightChild);
        rightChild.parent = rightChildLeftChild;

        prevNode.setRight(rightChildLeftChild.getLeft());
        if(rightChildLeftChild.getLeft() != null){
            rightChildLeftChild.getLeft().parent = prevNode;
        }

        rightChildLeftChild.setLeft(prevNode);
        prevNode.parent = rightChildLeftChild;

        prevNode.balance = (rightChildLeftChild.balance == 1) ? -1 : 0;
        rightChild.balance = (rightChildLeftChild.balance == -1) ? 1 : 0;
        if(prevNodeParent == null){
            setRoot(rightChildLeftChild);
        }else if(prevNodeParent.getLeft() == prevNode){
            prevNodeParent.setLeft(rightChildLeftChild);
        }else{
            prevNodeParent.setRight(rightChildLeftChild);
        }
        rightChildLeftChild.parent = prevNodeParent;
        rightChildLeftChild.balance = 0;
    }

    private V put(K keyParam, V value, boolean replace){
        if(DEBUG) avlValidateP(getRoot());
        STDAVLNodeDCAS<K,V> prevNode = null;
        STDAVLNodeDCAS<K,V> currentNode = getRoot();
        boolean dirLeft = true;
        if(comparator != null){
            K key = keyParam;
            Comparator<? super K> cpr = comparator;
            while(currentNode != null){
                K nodeKey = currentNode.key;
                int compareValue = cpr.compare(key, nodeKey);
                if(compareValue < 0) {
                    dirLeft = true;
                    prevNode = currentNode;
                    currentNode = currentNode.getLeft();
                } else if (compareValue > 0) {
                    dirLeft = false;
                    prevNode = currentNode;
                    currentNode = currentNode.getRight();
                } else {
                    V prevValue = currentNode.getValue();
                    if(replace){
                        currentNode.setValue(value);
                    }
                    if(DEBUG) avlValidateP(getRoot());
                    return prevValue;
                }
            }
        }else{
            @SuppressWarnings("unchecked")
                Comparable<? super K> key = (Comparable<? super K>) keyParam;
            while(currentNode != null){
                K nodeKey = currentNode.key;
                int compareValue = key.compareTo(nodeKey);
                if(compareValue < 0) {
                    dirLeft = true;
                    prevNode = currentNode;
                    currentNode = currentNode.getLeft();
                } else if (compareValue > 0) {
                    dirLeft = false;
                    prevNode = currentNode;
                    currentNode = currentNode.getRight();
                } else {
                    V prevValue = currentNode.getValue();
                    currentNode.setValue(value);
                    if(DEBUG) avlValidateP(getRoot());
                    return prevValue;
                }
            }
        }

        //Insert node
        size = size + 1;
        currentNode = new STDAVLNodeDCAS<K,V>(keyParam, value);
        if(prevNode == null){
            setRoot(currentNode);
        }else if(dirLeft){
            prevNode.setLeft(currentNode);
        }else{
            prevNode.setRight(currentNode);
        }
        currentNode.parent = prevNode;
        // Balance
        while(prevNode != null){         
            if(prevNode.getLeft() == currentNode){
                if(prevNode.balance == -1){
                    STDAVLNodeDCAS<K,V> leftChild = prevNode.getLeft();
                    // Need to rotate
                    if(leftChild.balance == -1){
                        rotateLeft(prevNode);
                    }else{
                        rotateDoubleRight(prevNode);
                    }
                    if(DEBUG) avlValidateP(getRoot());
                    return null; // Parents not affected balance restored
                }else if(prevNode.balance == 0){
                    prevNode.balance = -1;
                }else{
                    prevNode.balance = 0;
                    break; // balanced
                }
            }else{
                if(prevNode.balance == 1){
                    STDAVLNodeDCAS<K,V> rightChild = prevNode.getRight();
                    // Need to rotate
                    if(rightChild.balance == 1){
                        rotateRight(prevNode);
                    }else{
                        rotateDoubleLeft(prevNode);
                    }
                    if(DEBUG) avlValidateP(getRoot());
                    return null; // Parents not affected balance restored
                }else if (prevNode.balance == 0){
                    prevNode.balance = 1;
                }else{
                    prevNode.balance = 0;
                    break; // balanced
                }
            }
            currentNode = prevNode;
            prevNode = prevNode.parent;
        }
        if(DEBUG) avlValidateP(getRoot());
        return null;
    }

    final private boolean replaceWithRightmost(STDAVLNodeDCAS<K,V> toReplaceInNode){
        STDAVLNodeDCAS<K,V> currentNode = toReplaceInNode.getLeft();
        int replacePos = 0;            
        while (currentNode.getRight() != null) {
            replacePos = replacePos + 1;
            currentNode = currentNode.getRight();
        }
        toReplaceInNode.key = currentNode.key;
        toReplaceInNode.setValue(currentNode.getValue());
        if(currentNode.parent.getRight() == currentNode){
            currentNode.parent.setRight(currentNode.getLeft());
        }else{
            currentNode.parent.setLeft(currentNode.getLeft());
        }
        if(currentNode.getLeft() != null){
            currentNode.getLeft().parent = currentNode.parent;
        }
        boolean continueBalance = true;
        currentNode = currentNode.parent;
        while (replacePos > 0 && continueBalance) {
            STDAVLNodeDCAS<K,V> operateOn = currentNode;
            currentNode = currentNode.parent;
            replacePos = replacePos - 1;
            continueBalance = deleteBalanceRight(operateOn);
        }
        return continueBalance;
    }

    final private boolean deleteBalanceLeft(STDAVLNodeDCAS<K,V> currentNode){
        boolean continueBalance = true;
        if(currentNode.balance == -1){
            currentNode.balance = 0;
        }else if(currentNode.balance == 0){
            currentNode.balance = 1;
            continueBalance = false;
        }else{
            STDAVLNodeDCAS<K,V> currentNodeParent = currentNode.parent;
            STDAVLNodeDCAS<K,V> rightChild = currentNode.getRight();
            int rightChildBalance = rightChild.balance; 
            if (rightChildBalance >= 0) { //Single RR rotation
                rotateRight(currentNode);
                if(rightChildBalance == 0){
                    currentNode.balance = 1;
                    rightChild.balance = -1;
                    continueBalance = false;
                }
            } else { //Double LR rotation
                STDAVLNodeDCAS<K,V> rightChildLeftChild = rightChild.getLeft();
                int rightChildLeftChildBalance = rightChildLeftChild.balance;
                rightChild.setLeft(rightChildLeftChild.getRight());
                if(rightChildLeftChild.getRight() != null){
                    rightChildLeftChild.getRight().parent = rightChild;
                }
                rightChildLeftChild.setRight(rightChild);
                rightChild.parent = rightChildLeftChild;
                currentNode.setRight(rightChildLeftChild.getLeft());
                if(rightChildLeftChild.getLeft() != null){
                    rightChildLeftChild.getLeft().parent = currentNode;
                }
                rightChildLeftChild.setLeft(currentNode);
                currentNode.parent = rightChildLeftChild;
                currentNode.balance = (rightChildLeftChildBalance == 1) ? -1 : 0;
                rightChild.balance = (rightChildLeftChildBalance == -1) ? 1 : 0;
                rightChildLeftChild.balance = 0;
                if(currentNodeParent == null){
                    setRoot(rightChildLeftChild);
                }else if(currentNodeParent.getLeft() == currentNode){
                    currentNodeParent.setLeft(rightChildLeftChild);
                }else{
                    currentNodeParent.setRight(rightChildLeftChild);
                }
                rightChildLeftChild.parent = currentNodeParent;
            }
        }
        return continueBalance;
    }

    final private boolean deleteBalanceRight(STDAVLNodeDCAS<K,V> currentNode){
        boolean continueBalance = true;
        if(currentNode.balance == 1){
            currentNode.balance = 0;
        }else if(currentNode.balance == 0){
            currentNode.balance = -1;
            continueBalance = false;
        }else{
            STDAVLNodeDCAS<K,V> currentNodeParent = currentNode.parent;
            STDAVLNodeDCAS<K,V> leftChild = currentNode.getLeft();
            int leftChildBalance = leftChild.balance; 
            if (leftChildBalance <= 0) { //Single LL rotation
                rotateLeft(currentNode);
                if(leftChildBalance == 0){
                    currentNode.balance = -1;
                    leftChild.balance = 1;
                    continueBalance = false;
                }
            } else { //Double LR rotation
                STDAVLNodeDCAS<K,V> leftChildRightChild = leftChild.getRight();
                int leftChildRightChildBalance = leftChildRightChild.balance;
                leftChild.setRight(leftChildRightChild.getLeft());
                if(leftChildRightChild.getLeft() != null){
                    leftChildRightChild.getLeft().parent = leftChild;//null pointer exeception
                }
                leftChildRightChild.setLeft(leftChild);
                leftChild.parent = leftChildRightChild;
                currentNode.setLeft(leftChildRightChild.getRight());
                if(leftChildRightChild.getRight() != null){
                    leftChildRightChild.getRight().parent = currentNode;//null pointer exception
                }
                leftChildRightChild.setRight(currentNode);
                currentNode.parent = leftChildRightChild;
                currentNode.balance = (leftChildRightChildBalance == -1) ? 1 : 0;
                leftChild.balance = (leftChildRightChildBalance == 1) ? -1 : 0;
                leftChildRightChild.balance = 0;
                if(currentNodeParent == null){
                    setRoot(leftChildRightChild);
                }else if(currentNodeParent.getLeft() == currentNode){
                    currentNodeParent.setLeft(leftChildRightChild);
                }else{
                    currentNodeParent.setRight(leftChildRightChild);
                }
                leftChildRightChild.parent = currentNodeParent;
            }
        }
        return continueBalance;
    }

    final private void addAllToList(final STDAVLNodeDCAS<K,V> node, ArrayList<Map.Entry<K, V>> list){
        if(node!=null){
            addAllToList(node.getLeft(), list);
            AbstractMap.SimpleImmutableEntry<K,V> entry = new AbstractMap.SimpleImmutableEntry<K,V>(node.key, node.getValue()){
                    public int hashCode(){
                        return node.key.hashCode();
                    }
                };
            list.add(entry);
            addAllToList(node.getRight(), list);
        }
    } 

    final public void addAllToList(ArrayList<Map.Entry<K, V>> list){
        addAllToList(getRoot(), list);
    } 
    
    // ============================

    // === Public Interface =======
    
    public void setParent(Object parent){
        this.parent = parent;
    }

    public Object getParent(){
        return parent;
    }

    public STDAVLNodeDCAS<K,V> getRoot(){
        return theRoot;
    }    
    
    public void setRoot(STDAVLNodeDCAS<K,V> n){
        rootUpdater.lazySet(this, n);
    }

    public String toString(){
        return "B(" + getRoot() + ", " + isValid() + "," + getStatistics() + "," + getParent() + ","+size()+")";
    }
    
    public K anyKey(){
        if(getRoot() != null){
            return getRoot().key;
        }else{
            return null;
        }
    }

    public boolean isValid(){
        return valid;
    }

    public void invalidate(){
        valid = false;
    }

    public boolean tryLock(){
        if(lock.tryLock()){
            if(isLockFreeMode()){
                transformToLocked();
            }
            return true;
        }else{
            return false;
        }
    }
    
    public void lock(){
        if (tryLock()) {
            lock.subFromContentionStatistics();
            return;
        }
        lock.lock();
        if(isLockFreeMode()){
            transformToLocked();
        }
        lock.addToContentionStatistics();
    }

    public boolean lockIfNotLockFreeWithKey(K key){
        STDAVLNodeDCAS<K,V> lfMap = getLockFreeMap();
        if(lfMap != null && lfMapForUs(lfMap, key)){
            while(lfMap != null && lfMapForUs(lfMap, key) && getOptimisticReadToken() == 0){
                Thread.yield();
                lfMap = getLockFreeMap();
            }
            if(lfMap != null && lfMapForUs(lfMap, key)){
                return false;
            }
        }
        if (lock.tryLock()) {
            lfMap = getLockFreeMap();
            if(lfMap != null){
                if(lfMapForUs(lfMap, key)){
                    lock.unlock();
                    return false;
                }else{
                    transformToLocked();
                }
            }
            lock.subFromContentionStatistics();
            return true;
        }
        lock.lock();
        lfMap = getLockFreeMap();
        if(lfMap != null){
            if(lfMapForUs(lfMap, key)){
                lock.unlock();
                return false;
            }else{
                transformToLocked();
            }
        }
        lock.addToContentionStatistics();
        return true;
    }

    public boolean lockIfNotLockFree(){
        STDAVLNodeDCAS<K,V> lfMap = getLockFreeMap();
        if(lfMap != null){
            while(lfMap != null && getOptimisticReadToken() == 0){
                Thread.yield();
                lfMap = getLockFreeMap();
            }
            if(lfMap != null){
                return false;
            }
        }
        if (lock.tryLock()) {
            lfMap = getLockFreeMap();
            if(lfMap != null){
                lock.unlock();
                return false;
            }
            lock.subFromContentionStatistics();
            return true;
        }
        lock.lock();
        lfMap = getLockFreeMap();
        if(lfMap != null){
            lock.unlock();
            return false;
        }
        lock.addToContentionStatistics();
        return true;
    }

    public void addToContentionStatistics(){
        lock.addToContentionStatistics();
    }

    public void unlock(){
        lock.unlock();
    }

    public long getOptimisticReadToken(){
        return lock.tryOptimisticRead();
    }

    public boolean validateOptimisticReadToken(long optimisticReadToken){
        return lock.validate(optimisticReadToken);
    }

    public int getStatistics(){
        return lock.getLockStatistics();
    }
    
    public void resetStatistics(){
        lock.resetStatistics();
    }
    
    public int getHighContentionLimit(){
        return STAT_LOCK_HIGH_CONTENTION_LIMIT;
    }

    public int getLowContentionLimit(){
        return STAT_LOCK_LOW_CONTENTION_LIMIT;
    }

    public boolean isHighContentionLimitReached(){
        return lock.isHighContentionLimitReached();
    }
    
    public boolean isLowContentionLimitReached(){
        return lock.isLowContentionLimitReached();
    }

    public void indicateWriteStart(){
        long slot = ThreadLocalRandom.current().nextInt() % NUMBER_OF_LOCAL_STATISTICS_SLOTS;
        long writeIndicatorOffset = 
            LOCAL_STATISTICS_ARRAY_START_OFFSET + 
            slot * LOCAL_STATISTICS_ARRAY_SLOT_SIZE +
            LOCAL_STATISTICS_ARRAY_WRITE_INDICATOR_OFFSET;
        long prevValue;

        prevValue = unsafe.getLongVolatile(localStatisticsArray,
                                           writeIndicatorOffset);
        while(!unsafe.compareAndSwapLong(localStatisticsArray,
                                         writeIndicatorOffset,
                                         prevValue,
                                         prevValue + 1)){
            unsafe.fullFence();
            unsafe.fullFence();
            prevValue = unsafe.getLongVolatile(localStatisticsArray,
                                               writeIndicatorOffset);
        }
    }

    public void indicateWriteEnd(){
        long slot = ThreadLocalRandom.current().nextInt() % NUMBER_OF_LOCAL_STATISTICS_SLOTS;
        long writeIndicatorOffset = 
            LOCAL_STATISTICS_ARRAY_START_OFFSET + 
            slot * LOCAL_STATISTICS_ARRAY_SLOT_SIZE +
            LOCAL_STATISTICS_ARRAY_WRITE_INDICATOR_OFFSET;
        long prevValue;
        prevValue = unsafe.getLongVolatile(localStatisticsArray,
                                           writeIndicatorOffset);
        while(!unsafe.compareAndSwapLong(localStatisticsArray,
                                         writeIndicatorOffset,
                                         prevValue,
                                         prevValue - 1)){
            unsafe.fullFence();
            unsafe.fullFence();
            prevValue = unsafe.getLongVolatile(localStatisticsArray,
                                               writeIndicatorOffset);
        }
    }

    public long increaseLocalSize(){
        long slot = ThreadLocalRandom.current().nextInt() % NUMBER_OF_LOCAL_STATISTICS_SLOTS;
        long sizeOffset = 
            LOCAL_STATISTICS_ARRAY_START_OFFSET + 
            slot * LOCAL_STATISTICS_ARRAY_SLOT_SIZE +
            LOCAL_STATISTICS_ARRAY_SIZE_OFFSET;
        long prevValue;
        do{
            prevValue = unsafe.getLongVolatile(localStatisticsArray,
                                               sizeOffset);
        }while(!unsafe.compareAndSwapLong(localStatisticsArray,
                                          sizeOffset,
                                          prevValue,
                                          prevValue + 1));
        return prevValue + 1;
    }

    public long decreaseLocalSize(){
        long slot = ThreadLocalRandom.current().nextInt() % NUMBER_OF_LOCAL_STATISTICS_SLOTS;
        long sizeOffset = 
            LOCAL_STATISTICS_ARRAY_START_OFFSET + 
            slot * LOCAL_STATISTICS_ARRAY_SLOT_SIZE +
            LOCAL_STATISTICS_ARRAY_SIZE_OFFSET;
        long prevValue;
        do{
            prevValue = unsafe.getLongVolatile(localStatisticsArray,
                                               sizeOffset);
        }while(!unsafe.compareAndSwapLong(localStatisticsArray,
                                          sizeOffset,
                                          prevValue,
                                          prevValue - 1));
        return prevValue -1;
    }

    public long readLocalSizeSum(){
        long sum = 0;
        for(int i = 0; i < NUMBER_OF_LOCAL_STATISTICS_SLOTS; i++){
            long sizeOffset = 
                LOCAL_STATISTICS_ARRAY_START_OFFSET + 
                i * LOCAL_STATISTICS_ARRAY_SLOT_SIZE +
                LOCAL_STATISTICS_ARRAY_SIZE_OFFSET;
            
            sum = sum + unsafe.getLongVolatile(localStatisticsArray, sizeOffset);
        }
        return sum;
    }

    public void waitNoOngoingWrite(){
        long currentOffset = 
            LOCAL_STATISTICS_ARRAY_START_OFFSET + 
            LOCAL_STATISTICS_ARRAY_WRITE_INDICATOR_OFFSET;
        for(int i = 0; i < NUMBER_OF_LOCAL_STATISTICS_SLOTS; i++){
            while(0 != unsafe.getLongVolatile(localStatisticsArray,
                                              currentOffset)){
                unsafe.fullFence();
                unsafe.fullFence();
            }
            currentOffset = currentOffset + LOCAL_STATISTICS_ARRAY_SLOT_SIZE;
        }
    }

    public SplitableAndJoinableMap<K, V> join(SplitableAndJoinableMap<K, V> right){
        STDAVLNodeDCAS<K,V> prevNode = null;//f
        STDAVLNodeDCAS<K,V> currentNode = null;//f
        DualLFCASAVLTreeMapSTD<K, V> newTree = null;
        if(comparator == null){
            newTree = new DualLFCASAVLTreeMapSTD<K, V>();
        }else{
            newTree = new DualLFCASAVLTreeMapSTD<K, V>(comparator);
        }
        DualLFCASAVLTreeMapSTD<K,V> leftTree = this;
        DualLFCASAVLTreeMapSTD<K,V> rightTree = (DualLFCASAVLTreeMapSTD<K,V>)right;
        if(leftTree.getRoot() == null){
            newTree.setRoot(rightTree.getRoot());
            newTree.size = rightTree.size + leftTree.size;
            return newTree;
        }else if(rightTree.getRoot() == null){
            newTree.setRoot(leftTree.getRoot());
            newTree.size = leftTree.size + rightTree.size;
            return newTree;
        }
        int leftHeight = leftTree.computeHeight();
        int rightHeight = rightTree.computeHeight();
        if(leftHeight >= rightHeight){
            K minKey = rightTree.minKey();
            V minValue = rightTree.remove(minKey);
            rightTree.size = rightTree.size + 1;
            STDAVLNodeDCAS<K,V> newRoot = new STDAVLNodeDCAS<K,V>(minKey, minValue);
            int newRightHeight = rightTree.computeHeight();
            // Find a node v on the rightmost path from the root of T1 , whose height is either h or h + 1, as follows:
            // From: http://www.cs.toronto.edu/~avner/teaching/263/A/2sol.pdf
            // v <- root(T1 )
            // h' <- h1
            // while h > h + 1 do
            //    if balance factor (v) = -1
            //    then h' <- h' - 2
            //    else h' <- h- - 1
            //    v <- rightchild(v)
            prevNode = null;
            currentNode = leftTree.getRoot();
            int currentHeight = leftHeight;
            while(currentHeight > newRightHeight + 1){
                if(currentNode.balance == -1){
                    currentHeight = currentHeight - 2;
                }else{
                    currentHeight = currentHeight - 1;
                }
                prevNode = currentNode;
                currentNode = currentNode.getRight();
            }
            STDAVLNodeDCAS<K,V> oldCurrentNodeParent = prevNode;
            newRoot.setLeft(currentNode);
            if(currentNode != null){
                currentNode.parent = newRoot;
            }
            newRoot.setRight(rightTree.getRoot());
            if(rightTree.getRoot() != null){
                rightTree.getRoot().parent = newRoot;
            }
            newRoot.balance = newRightHeight - currentHeight;

            if(oldCurrentNodeParent == null){//Check if this can happen at all
                newTree.setRoot(newRoot);
            }else if(oldCurrentNodeParent.getLeft() == currentNode){
                oldCurrentNodeParent.setLeft(newRoot);
                newRoot.parent = oldCurrentNodeParent;
                newTree.setRoot(leftTree.getRoot());
            }else{
                oldCurrentNodeParent.setRight(newRoot);
                newRoot.parent = oldCurrentNodeParent;
                newTree.setRoot(leftTree.getRoot());
            }
            currentNode = newRoot;
        }else{
            //This case is symetric to the previous case
            K maxKey = leftTree.maxKey();//f
            V maxValue = leftTree.remove(maxKey);//f
            leftTree.size = leftTree.size + 1;
            STDAVLNodeDCAS<K,V> newRoot = new STDAVLNodeDCAS<K,V>(maxKey, maxValue);//f
            int newLeftHeight = leftTree.computeHeight();//f
            prevNode = null;//f
            currentNode = rightTree.getRoot();//f
            int currentHeight = rightHeight;//f
            while(currentHeight > newLeftHeight + 1){//f
                if(currentNode.balance == 1){//f
                    currentHeight = currentHeight - 2;//f
                }else{
                    currentHeight = currentHeight - 1;//f
                }
                prevNode = currentNode;//f
                currentNode = currentNode.getLeft();//f
            }
            STDAVLNodeDCAS<K,V> oldCurrentNodeParent = prevNode;//f
            newRoot.setRight(currentNode);//f
            if(currentNode != null){
                currentNode.parent = newRoot;//f
            }
            newRoot.setLeft(leftTree.getRoot());//f
            if(leftTree.getRoot() != null){
                leftTree.getRoot().parent = newRoot;//f
            }
            newRoot.balance = currentHeight - newLeftHeight;//f
            if(oldCurrentNodeParent == null){//Check if this can happen at all
                newTree.setRoot(newRoot);
            }else if(oldCurrentNodeParent.getLeft() == currentNode){
                oldCurrentNodeParent.setLeft(newRoot);
                newRoot.parent = oldCurrentNodeParent;
                newTree.setRoot(rightTree.getRoot());
            }else{
                oldCurrentNodeParent.setRight(newRoot);
                newRoot.parent = oldCurrentNodeParent;
                newTree.setRoot(rightTree.getRoot());
            }
            currentNode = newRoot;
        }
        //Now we need to continue as if this was during the insert 
        while(prevNode != null){
            if(prevNode.getLeft() == currentNode){
                if(prevNode.balance == -1){
                    STDAVLNodeDCAS<K,V> leftChild = prevNode.getLeft();
                    //Need to rotate
                    if(leftChild.balance == -1){
                        newTree.rotateLeft(prevNode);
                    }else{
                        newTree.rotateDoubleRight(prevNode);
                    }
                    newTree.size = leftTree.size + rightTree.size;
                    if(DEBUG) avlValidateP(newTree.getRoot());
                    return newTree; //Parents not affected balance restored
                }else if(prevNode.balance == 0){
                    prevNode.balance = -1;
                }else{
                    prevNode.balance = 0;
                    break;//balanced
                }
            }else{
                if(prevNode.balance == 1){
                    STDAVLNodeDCAS<K,V> rightChild = prevNode.getRight();
                    //Need to rotate
                    if(rightChild.balance == 1){
                        newTree.rotateRight(prevNode);
                    }else{
                        newTree.rotateDoubleLeft(prevNode);
                    }
                    newTree.size = leftTree.size + rightTree.size;
                    if(DEBUG) avlValidateP(newTree.getRoot());
                    return newTree;
                }else if (prevNode.balance == 0){
                    prevNode.balance = 1;
                }else{
                    prevNode.balance = 0;
                    break;//Balanced
                }
            }
            currentNode = prevNode;
            prevNode = prevNode.parent;
        }
        newTree.size = leftTree.size + rightTree.size;
        if(DEBUG) avlValidateP(newTree.getRoot());
        return newTree;
    }

    public SplitableAndJoinableMap<K, V> split(Object[] splitKeyWriteBack,
                                               SplitableAndJoinableMap<K, V>[] rightTreeWriteBack){
        STDAVLNodeDCAS<K,V> leftRoot = null;
        STDAVLNodeDCAS<K,V> rightRoot = null;
        if(getRoot() == null){
            return null;
        }else if(getRoot().getLeft() == null && getRoot().getRight() == null){
            return null;
        }else if(getRoot().getLeft() == null){
            splitKeyWriteBack[0] = getRoot().getRight().key;
            rightRoot = getRoot().getRight();
            rightRoot.parent = null;
            rightRoot.balance = 0;
            getRoot().setRight(null);
            leftRoot = getRoot();
            leftRoot.balance = 0;
        }else{
            splitKeyWriteBack[0] = getRoot().key;
            leftRoot = getRoot().getLeft();
            leftRoot.parent = null;
            getRoot().setLeft(null);
            if (getRoot().getRight() == null){
                rightRoot = getRoot();
                rightRoot.balance = 0;
            }else{
                K insertKey = getRoot().key;
                V insertValue = getRoot().getValue();
                setRoot(getRoot().getRight());
                getRoot().parent = null;
                put(insertKey, insertValue);
                size = size - 1;
                rightRoot = getRoot();
            }
        }
        DualLFCASAVLTreeMapSTD<K,V> leftTree = null;
        if(comparator == null){
            leftTree = new DualLFCASAVLTreeMapSTD<K, V>();
        }else{
            leftTree = new DualLFCASAVLTreeMapSTD<K, V>(comparator);
        }
        leftTree.setRoot(leftRoot);
        DualLFCASAVLTreeMapSTD<K,V> rightTree = null;
        if(comparator == null){
            rightTree = new DualLFCASAVLTreeMapSTD<K, V>();
        }else{
            rightTree = new DualLFCASAVLTreeMapSTD<K, V>(comparator);
        }
        rightTree.setRoot(rightRoot);
        int remainder = size % 2;
        int aproxSizes = size / 2;
        leftTree.size = aproxSizes;
        rightTree.size = aproxSizes + remainder;
        rightTreeWriteBack[0] = rightTree;
        if(DEBUG) {
            avlValidateP(leftTree.getRoot());
            avlValidateP(rightTree.getRoot());
        }
        return leftTree;
    }

    public void transformToLockFree(){
        if(LOCK_FREE_FALSE_SHARING_SAFETY){
            lockFreeHolder = new STDAVLNodeDCASNOFALSE<K,V>(getRoot().getKey(), getRoot().getValue());
        }else{
            lockFreeHolder = getRoot();
        }
        setRoot(null);
        if(localStatisticsArray == null){
            localStatisticsArray = 
                new byte[128*2 + (int)LOCAL_STATISTICS_ARRAY_SLOT_SIZE*NUMBER_OF_LOCAL_STATISTICS_SLOTS];
        }
    }

    public void transformToLocked(){
	STDAVLNodeDCAS<K,V> lfMap = lockFreeHolder;
        lockFreeHolder  = null;
        waitNoOngoingWrite();
        if(lfMap.getValue() == null){
            size = size - 1;
            setRoot(null);
        }else{
            setRoot(lfMap);
        }
        resetStatistics(); // Reset statistics so it will not be converted too quickly
    }

    public boolean isLockFreeMode(){
        return lockFreeHolder != null;
    }

    public boolean oneElement(){
        return theRoot != null && theRoot.getLeft() == null && theRoot.getRight() == null;
    }

    public STDAVLNodeDCAS<K, V> getLockFreeMap(){
        return lockFreeHolder;
    }

    public int size(){
        return size;
    }

    public boolean isEmpty(){
        return getRoot() == null;
    }

    public boolean containsKey(Object key){
        return getSTDAVLNodeDCAS(key) != null;
    }

    public V get(Object key){
        STDAVLNodeDCAS<K,V> node = getSTDAVLNodeDCAS(key);
        if(node != null){
            return node.getValue();
        }else{
            return null;
        }
    }

    public V put(K key, V value){
        return put(key, value, true);
    }

    public V putIfAbsent(K key, V value) {
        return put(key, value, false);
    }

    public V remove(Object keyParam){
        boolean dirLeft = true;
        if(DEBUG) avlValidateP(getRoot());
        STDAVLNodeDCAS<K,V> currentNode = getRoot();
        if(comparator != null){
            @SuppressWarnings("unchecked")
                K key = (K)keyParam;
            Comparator<? super K> cpr = comparator;
            while(currentNode != null){
                K nodeKey = currentNode.key;
                int compareValue = cpr.compare(key, nodeKey);
                if(compareValue < 0) {
                    dirLeft = true;
                    currentNode = currentNode.getLeft();
                } else if (compareValue > 0) {
                    dirLeft = false;
                    currentNode = currentNode.getRight();
                } else {
                    size = size - 1;
                    break;
                }
            }
        }else{
            @SuppressWarnings("unchecked")
                Comparable<? super K> key = (Comparable<? super K>) keyParam;
            while(currentNode != null){
                K nodeKey = currentNode.key;
                int compareValue = key.compareTo(nodeKey);
                if(compareValue < 0) {
                    dirLeft = true;
                    currentNode = currentNode.getLeft();
                } else if (compareValue > 0) {
                    dirLeft = false;
                    currentNode = currentNode.getRight();
                } else {
                    size = size - 1;
                    break;
                }
            }
        }
        V toReturn = null;
        if(currentNode == null){
            if(DEBUG) avlValidateP(getRoot());
            return null;
        }else{
            toReturn = currentNode.getValue();
        }
        //Fix balance
        STDAVLNodeDCAS<K,V> prevNode = currentNode.parent;
        boolean continueFix = true;
        if(currentNode.getLeft() == null){
            if(prevNode == null){
                setRoot(currentNode.getRight());
            }else if(dirLeft){
                prevNode.setLeft(currentNode.getRight());
            }else{
                prevNode.setRight(currentNode.getRight());

            }
            if(currentNode.getRight() != null){
                currentNode.getRight().parent = prevNode;
            }
            currentNode = currentNode.getRight();
        }else if(currentNode.getRight() == null){
            if(prevNode == null){
                setRoot(currentNode.getLeft());
            }else if(dirLeft){
                prevNode.setLeft(currentNode.getLeft());
            }else{
                prevNode.setRight(currentNode.getLeft());
            }
            if(currentNode.getLeft() != null){
                currentNode.getLeft().parent = prevNode;
            }
            currentNode = currentNode.getLeft();
        }else{
            if(prevNode == null){
                continueFix = replaceWithRightmost(currentNode);
                STDAVLNodeDCAS<K,V> r = getRoot();
                currentNode = r.getLeft();
                prevNode = r;
            }else if(prevNode.getLeft() == currentNode){
                continueFix = replaceWithRightmost(currentNode);
                prevNode = prevNode.getLeft();
                currentNode = prevNode.getLeft();
                dirLeft = true;
            }else{
                continueFix = replaceWithRightmost(currentNode);
                prevNode = prevNode.getRight();
                currentNode = prevNode.getLeft();
                dirLeft = true;
            }
        }
        // current node is the node we are coming from
        // prev node is the node that needs re-balancing
        while (continueFix && prevNode != null) {
            STDAVLNodeDCAS<K,V> nextPrevNode = prevNode.parent;
            if(nextPrevNode != null){
                boolean findCurrentLeftDir = true;
                if(nextPrevNode.getLeft() == prevNode){
                    findCurrentLeftDir = true;
                }else{
                    findCurrentLeftDir = false;
                }
                if(currentNode == null){
                    if (dirLeft) {
                        continueFix = deleteBalanceLeft(prevNode);
                    } else {
                        continueFix = deleteBalanceRight(prevNode);
                    }
                }else{
                    if (prevNode.getLeft() == currentNode) {
                        continueFix = deleteBalanceLeft(prevNode);
                    } else {
                        continueFix = deleteBalanceRight(prevNode);
                    }
                }
                if(findCurrentLeftDir){
                    currentNode = nextPrevNode.getLeft();
                }else{
                    currentNode = nextPrevNode.getRight();
                }
                prevNode = nextPrevNode;
            }else{
                if(currentNode == null){
                    if (dirLeft) {
                        continueFix = deleteBalanceLeft(prevNode);
                    } else {
                        continueFix = deleteBalanceRight(prevNode);
                    }
                }else{
                    if (prevNode.getLeft() == currentNode) {
                        continueFix = deleteBalanceLeft(prevNode);
                    } else {
                        continueFix = deleteBalanceRight(prevNode);
                    }
                }
                prevNode = null;
            }
        }
        if(DEBUG) avlValidateP(getRoot());
        return toReturn;
    }

    public void clear(){
        size = 0;
        setRoot(null);
    }

    public Set<Map.Entry<K, V>> entrySet(){
        ArrayList<Map.Entry<K, V>> list = new ArrayList<Map.Entry<K, V>>();
        addAllToList(getRoot(), list);
        return new HashSet<Map.Entry<K, V>>(list);
    }

}
