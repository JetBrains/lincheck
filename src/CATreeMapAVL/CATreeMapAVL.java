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
import java.util.concurrent.locks.*;
import java.util.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.lang.reflect.Field;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * Implementation of a Contention Adapting Search Tree (CA tree). CA
 * trees are described in the following publication:
 *
 * Konstantinos Sagonas and Kjell Winblad.
 * Contention Adapting Search Trees
 * In Proceedings of the Fourteenth International Symposium on Parallel
 * and Distributed Computing, Limassol, Cyprus, July 2015.
 *
 * This implementation uses AVL trees as the sequential data structure
 * in the CA trees base nodes and has the optimizations described in
 * sections V.A. and V.C. of the above mentioned publication.
 *
 * More information about CA trees and more CA tree implementations
 * (e.g. implementations with range update and range query support)
 * can be found at
 * <a href="http://www.it.uu.se/research/group/languages/software/ca_tree">http://www.it.uu.se/research/group/languages/software/ca_tree</a>.
 * 
 * @author Kjell Winblad
 */
public class CATreeMapAVL<K, V> extends AbstractMap<K,V> implements CompositionalMap<K, V> {

    // ====== Costants =======
    private final static boolean DEBUG = false;
    private static final int LOCK_FREE_SPONTANIUS_PROBABLITY = 50000000;
    private static final int OPTIMISTIC_RETRIES = 1;
    // =======================

    // ====== Fields =========
    private volatile Object root = new DualLFCASAVLTreeMapSTD<K, V>();
    private final Comparator<? super K> comparator;
    // =======================

    // ====== Declarations ===
    
    static private final class RouteNode{

        private volatile Object left;
        private volatile Object right;
        private final Object key;
        final ReentrantLock lock = new ReentrantLock();
        boolean valid = true;
        private static final AtomicReferenceFieldUpdater<RouteNode, Object> leftUpdater =
            AtomicReferenceFieldUpdater.newUpdater(RouteNode.class, Object.class, "left");
        private static final AtomicReferenceFieldUpdater<RouteNode, Object> rightUpdater =
            AtomicReferenceFieldUpdater.newUpdater(RouteNode.class, Object.class, "right");
        public RouteNode(Object key, Object left, Object right){
            this.key = key;
            leftUpdater.lazySet(this, left);
            rightUpdater.lazySet(this, right);
        }

        public Object getKey(){
            return this.key;
        }

        public Object getLeft(){
            return this.left;
        }

        public void setLeft(Object o){
            leftUpdater.lazySet(this, o);
        }

        public Object getRight(){
            return this.right;
        }

        public void setRight(Object o){
            rightUpdater.lazySet(this, o);
        }
        public String toString(){
            return "R(" +  this.key + ")";
        }

    }
    
    // ==== Functions for debuging and testing =====

    void printDotHelper(Object n, PrintStream writeTo, int level){
        try{
            if(n instanceof RouteNode){
                RouteNode node = (RouteNode)n;
                //LEFT
                writeTo.print("\"" + node + level+" \"");
                writeTo.print(" -> ");
                writeTo.print("\"" + node.getLeft() + (level +1)+" \"");
                writeTo.println(";");
                //RIGHT
                writeTo.print("\"" + node + level+" \"");
                writeTo.print(" -> ");
                writeTo.print("\"" + node.getRight() + (level +1)+" \"");
                writeTo.println(";");

                printDotHelper(node.getLeft(), writeTo, level +1);
                printDotHelper(node.getRight(), writeTo, level +1);
            }
        }catch(Exception e){
            e.printStackTrace();
        }
    }


    void printDot(Object node, String fileName){
        try{
            lockAll();
            Process p = new ProcessBuilder("dot", "-Tsvg")
                .redirectOutput(ProcessBuilder.Redirect.to(new File(fileName + ".svg")))
                .start();
            PrintStream writeTo = new PrintStream(p.getOutputStream());
            writeTo.print("digraph G{\n");
            writeTo.print("  graph [ordering=\"out\"];\n");
            printDotHelper(node, writeTo, 0);
            writeTo.print("}\n");
            writeTo.close();
            p.waitFor();
            unlockAll();
        }catch(Exception e){
            e.printStackTrace();
        }
    }

    //=============================================


    //=== Constructors ============================

    public CATreeMapAVL() {
        comparator = null;
    }

    public CATreeMapAVL(Comparator<? super K> comparator) {
        this.comparator = comparator;
    }

    //=== Helper Methods =======================


    private int sizeHelper(Object currentNode){
        if(currentNode == null){
            return 0;
        }else{
            if(currentNode instanceof RouteNode){
                RouteNode r = (RouteNode)currentNode;
                int sizeSoFar = sizeHelper(r.getLeft());
                return sizeSoFar + sizeHelper(r.getRight());
            }else {
                @SuppressWarnings("unchecked")
                    DualLFCASAVLTreeMapSTD<K,V> b = (DualLFCASAVLTreeMapSTD<K,V>)currentNode;
                return b.size();
            }
        }
    }

    final private Object getBaseNodeUsingComparator(Object keyParam){
        Object currNode = root;
        @SuppressWarnings("unchecked")
            K key = (K)keyParam;
        while (currNode instanceof RouteNode) {
            RouteNode currNodeR = (RouteNode)currNode;
            @SuppressWarnings("unchecked")
                K routeKey = (K)(currNodeR.getKey());
            if (comparator.compare(key, routeKey) < 0) {
                currNode = currNodeR.getLeft();
            }else {
                currNode = currNodeR.getRight();
            }
        }
        return currNode;
    }

    final private Object getBaseNode(Object keyParam){
        Object currNode = root;
        if(comparator != null){
            return getBaseNodeUsingComparator(keyParam);
        }else{
            @SuppressWarnings("unchecked")
                Comparable<? super K> key = (Comparable<? super K>) keyParam;
            while (currNode instanceof RouteNode) {
                RouteNode currNodeR = (RouteNode)currNode;
                @SuppressWarnings("unchecked")
                    K routeKey = (K)(currNodeR.getKey());
                if (key.compareTo(routeKey) < 0) {
                    currNode = currNodeR.getLeft();
                } else {
                    currNode = currNodeR.getRight();
                }
            }
            return currNode;
        }
    }

    final private void highContentionSplit(DualLFCASAVLTreeMapSTD<K, V> baseNode){
        if(baseNode.getRoot() == null || 
           (baseNode.getRoot().getLeft() == null &&
            baseNode.getRoot().getRight() == null)){
            baseNode.resetStatistics();//Fast path out if nrOfElem <= 1
            return;
        }

        RouteNode parent = (RouteNode)baseNode.getParent();
        Object[] writeBackSplitKey = new Object[1];
        @SuppressWarnings("unchecked")
            SplitableAndJoinableMap<K,V>[] writeBackRightTree = new SplitableAndJoinableMap[1];
        @SuppressWarnings("unchecked")
            DualLFCASAVLTreeMapSTD<K,V> leftTree = (DualLFCASAVLTreeMapSTD<K,V>)baseNode.split(writeBackSplitKey, writeBackRightTree);
        if(leftTree == null){
            baseNode.resetStatistics();
            return;
        }
        @SuppressWarnings("unchecked")
            K splitKey = (K)writeBackSplitKey[0];
        DualLFCASAVLTreeMapSTD<K,V> rightTree = (DualLFCASAVLTreeMapSTD<K,V>)writeBackRightTree[0];
        RouteNode newRoute = new RouteNode(splitKey, leftTree, rightTree);
        leftTree.setParent(newRoute);
        rightTree.setParent(newRoute);
        if (parent == null) {
            root = newRoute;
        }else {
            if (parent.getLeft() == baseNode){
                parent.setLeft(newRoute);
            }else{
                parent.setRight(newRoute);
            }
        }
        baseNode.invalidate();
    }

    final private DualLFCASAVLTreeMapSTD<K,V> leftmostBaseNode(Object node){
        Object currentNode = node;
        while(currentNode instanceof RouteNode){
            RouteNode r = (RouteNode)currentNode;
            currentNode = r.getLeft();
        }
        @SuppressWarnings("unchecked")
            DualLFCASAVLTreeMapSTD<K,V> toReturn = (DualLFCASAVLTreeMapSTD<K,V>)currentNode;
        return toReturn;
    }

    final private DualLFCASAVLTreeMapSTD<K,V> rightmostBaseNode(Object node){
        Object currentNode = node;
        while(currentNode instanceof RouteNode){
            RouteNode r = (RouteNode)currentNode;
            currentNode = r.getRight();
        }
        @SuppressWarnings("unchecked")
            DualLFCASAVLTreeMapSTD<K,V> toReturn = (DualLFCASAVLTreeMapSTD<K,V>)currentNode;
        return toReturn;
    }

    final private RouteNode parentOfUsingComparator(RouteNode node){
        @SuppressWarnings("unchecked")
            K key = (K)node.getKey();
        Object prevNode = null;
        Object currNode = root;

        while (currNode != node) {
            @SuppressWarnings("unchecked")
                RouteNode currNodeR = (RouteNode)currNode;
            @SuppressWarnings("unchecked")
                K routeKey = (K)(currNodeR.getKey());
            prevNode = currNode;
            if (comparator.compare(key, routeKey) < 0) {
                currNode = currNodeR.getLeft();
            } else {
                currNode = currNodeR.getRight();
            }
        }
        return (RouteNode)prevNode;

    }

    final private RouteNode parentOf(RouteNode node){
        if(comparator != null){
            return parentOfUsingComparator(node);
        }else{
            @SuppressWarnings("unchecked")
                Comparable<? super K> key = (Comparable<? super K>) node.getKey();
            Object prevNode = null;
            Object currNode = root;
            while (currNode != node) {
                RouteNode currNodeR = (RouteNode)currNode;
                @SuppressWarnings("unchecked")
                    K routeKey = (K)(currNodeR.getKey());
                prevNode = currNode;
                if (key.compareTo(routeKey) < 0) {
                    currNode = currNodeR.getLeft();
                } else {
                    currNode = currNodeR.getRight();
                }
            }
            return (RouteNode)prevNode;
        }
    }

    final private void lowContentionJoin(DualLFCASAVLTreeMapSTD<K, V> baseNode){
        RouteNode parent = (RouteNode)baseNode.getParent();
        if(parent == null){
            baseNode.resetStatistics();
        }else if (parent.getLeft() == baseNode) {
            DualLFCASAVLTreeMapSTD<K,V> neighborBase = leftmostBaseNode(parent.getRight());
            if (!neighborBase.tryLock()) {
                baseNode.resetStatistics();
                return;
            } else if (!neighborBase.isValid()) {
                neighborBase.unlock();
                baseNode.resetStatistics();
                return;
            } else {
                @SuppressWarnings("unchecked")
                    DualLFCASAVLTreeMapSTD<K,V> newNeighborBase = (DualLFCASAVLTreeMapSTD<K,V>)baseNode.join(neighborBase);
                parent.lock.lock();
                RouteNode gparent = null; // gparent = grandparent
                do {
                    if (gparent != null){
                        gparent.lock.unlock();
                    }
                    gparent = parentOf(parent);
                    if (gparent != null){
                        gparent.lock.lock();
                    }
                } while (gparent != null && !gparent.valid);
                if (gparent == null) {
                    root = parent.getRight();
                } else if(gparent.getLeft() == parent){
                    gparent.setLeft(parent.getRight());
                } else {
                    gparent.setRight(parent.getRight());
                }
                parent.valid = false;
                parent.lock.unlock();
                if (gparent != null){
                    gparent.lock.unlock();
                }
                //Unlink is done!
                //Put in joined base node
                RouteNode neighborBaseParent = null;
                if(parent.getRight() == neighborBase){
                    neighborBaseParent = gparent;
                }else{
                    neighborBaseParent = (RouteNode)neighborBase.getParent();
                }
                newNeighborBase.setParent(neighborBaseParent);
                if(neighborBaseParent == null){
                    root = newNeighborBase;
                } else if (neighborBaseParent.getLeft() == neighborBase) {
                    neighborBaseParent.setLeft(newNeighborBase);
                } else {
                    neighborBaseParent.setRight(newNeighborBase);
                }
                neighborBase.invalidate();
                neighborBase.unlock();
                baseNode.invalidate();
            }
        } else { /* This case is symmetric to the previous one */
            DualLFCASAVLTreeMapSTD<K,V> neighborBase = rightmostBaseNode(parent.getLeft());//ff
            if (!neighborBase.tryLock()) {//ff
                baseNode.resetStatistics();//ff
            } else if (!neighborBase.isValid()) {//ff
                neighborBase.unlock();//ff
                baseNode.resetStatistics();//ff
            } else {
                @SuppressWarnings("unchecked")
                    DualLFCASAVLTreeMapSTD<K,V> newNeighborBase = (DualLFCASAVLTreeMapSTD<K,V>)neighborBase.join(baseNode);//ff
                parent.lock.lock();//ff
                RouteNode gparent = null; // gparent = grandparent //ff
                do {//ff
                    if (gparent != null){//ff
                        gparent.lock.unlock();//ff
                    }//ff
                    gparent = parentOf(parent);//ff
                    if (gparent != null){//ff
                        gparent.lock.lock();//ff
                    }//ff
                } while (gparent != null && !gparent.valid);//ff
                if (gparent == null) {//ff
                    root = parent.getLeft();//ff
                } else if(gparent.getLeft() == parent){//ff
                    gparent.setLeft(parent.getLeft());//ff
                } else {//ff
                    gparent.setRight(parent.getLeft());//ff
                }//ff
                parent.valid = false;
                parent.lock.unlock();//ff
                if (gparent != null){//ff
                    gparent.lock.unlock();//ff
                }//ff
                RouteNode neighborBaseParent = null;
                if(parent.getLeft() == neighborBase){
                    neighborBaseParent = gparent;
                }else{
                    neighborBaseParent = (RouteNode)neighborBase.getParent();
                }
                newNeighborBase.setParent(neighborBaseParent);//ff
                if(neighborBaseParent == null){//ff
                    root = newNeighborBase;//ff
                } else if (neighborBaseParent.getLeft() == neighborBase) {//ff
                    neighborBaseParent.setLeft(newNeighborBase);//ff
                } else {//ff
                    neighborBaseParent.setRight(newNeighborBase);//ff
                }//ff
                neighborBase.invalidate();//ff
                neighborBase.unlock();//ff
                baseNode.invalidate();//ff
            }
        }
    }

    private final void adaptIfNeeded(DualLFCASAVLTreeMapSTD<K,V> baseNode){
        if (baseNode.isHighContentionLimitReached()){
            if(baseNode.oneElement()){
                baseNode.transformToLockFree();
            }else {
                highContentionSplit(baseNode);
            }
        } else if (baseNode.isLowContentionLimitReached()) {
            lowContentionJoin(baseNode);
        }
    }

    private final boolean lfMapForUs(STDAVLNodeDCAS<K,V> lfMap, K key){
        if(comparator != null){
            return comparator.compare(key, lfMap.getKey()) == 0;
        }else{
            @SuppressWarnings("unchecked")
                Comparable<? super K> keyComp = (Comparable<? super K>) key;
            return keyComp.compareTo((K)lfMap.getKey() ) == 0;
        }
    }

    final private void lockAllHelper(Object currentNode, ArrayList<DualLFCASAVLTreeMapSTD> lockedSoFar){
        try {
            if(currentNode != null){
                if(currentNode instanceof RouteNode){
                    RouteNode r = (RouteNode)currentNode;
                    lockAllHelper(r.getLeft(), lockedSoFar);
                    lockAllHelper(r.getRight(), lockedSoFar);
                }else {
                    DualLFCASAVLTreeMapSTD b = (DualLFCASAVLTreeMapSTD)currentNode;
                    b.lock();
                    if(b.isValid()){
                        lockedSoFar.add(b);
                    }else{
                        //Retry
                        b.unlock();
                        for(DualLFCASAVLTreeMapSTD m : lockedSoFar){
                            m.unlock();
                        }
                        throw new RuntimeException();
                    }
                }
            }
        } catch (RuntimeException e){
            //Retry
            lockAllHelper(root, new ArrayList<DualLFCASAVLTreeMapSTD>());
        }
    }


    final private void unlockAllHelper(Object currentNode) {
        if(currentNode != null){
            if(currentNode instanceof RouteNode) {
                RouteNode b = (RouteNode)currentNode;
                unlockAllHelper(b.getLeft());
                unlockAllHelper(b.getRight());
            } else {
                DualLFCASAVLTreeMapSTD b = (DualLFCASAVLTreeMapSTD)currentNode;
                b.unlock();
            }
        }
    }

    final private void lockAll(){
        lockAllHelper(root, new ArrayList<DualLFCASAVLTreeMapSTD>());
    }

    final private void unlockAll(){
        unlockAllHelper(root);
    }

    final private void addAllToList(Object currentNode, ArrayList<Map.Entry<K, V>> list){
        if(currentNode == null){
            return;
        }else{
            if(currentNode instanceof RouteNode){
                @SuppressWarnings("unchecked")
                    RouteNode r = (RouteNode)currentNode;
                addAllToList(r.getLeft(), list);
                addAllToList(r.getRight(), list);
            }else {
                @SuppressWarnings("unchecked")
                    DualLFCASAVLTreeMapSTD<K, V> b = (DualLFCASAVLTreeMapSTD<K, V>)currentNode;
                b.addAllToList(list);
                return;
            }
        }
    }
    
    // ==== Public Interface =====
    
    public int size(){
        lockAll();
        int size = sizeHelper(root);
        unlockAll();
        return size;
    }

    public boolean isEmpty(){
        return size() == 0;
    }

    public boolean containsKey(Object key){
        return get(key) != null;
    }
    
    public V get(Object key){
	outerloop:
        while(true){
            @SuppressWarnings("unchecked")
                DualLFCASAVLTreeMapSTD<K,V> baseNode = (DualLFCASAVLTreeMapSTD<K,V>)getBaseNode(key);
	    for(int i = 0; i < OPTIMISTIC_RETRIES; i++){
		long optimisticReadToken = baseNode.getOptimisticReadToken();
		V result = null;
		if(optimisticReadToken != 0L){
	     	    if (baseNode.isValid() == false) {
	     		continue outerloop; // retry
	     	    }          
		    STDAVLNodeDCAS<K,V> lfMap =  baseNode.getLockFreeMap();
                    if(lfMap!=null){
                        @SuppressWarnings("unchecked")
                            K k = (K)key;
                        if(lfMapForUs(lfMap, k)){
                            result = lfMap.getValue();
                        }
                    }else{
                        result = baseNode.get(key);
                    }
                }
		if(baseNode.validateOptimisticReadToken(optimisticReadToken)){
		    return result;
		}
	    }
	    if(baseNode.lockIfNotLockFree()){
		if (baseNode.isValid()) {
		    V result = baseNode.get(key);
		    baseNode.addToContentionStatistics();
		    adaptIfNeeded(baseNode);
		    baseNode.unlock();
		    return result;
		}else{
		    baseNode.unlock();
		}
	    }
	}
    }

    public V put(K key, V value){
        while(true){
            @SuppressWarnings("unchecked")
                DualLFCASAVLTreeMapSTD<K, V> baseNode = (DualLFCASAVLTreeMapSTD<K, V>)getBaseNode(key);
            //First check if we can do it in a lock free way
            long optimisticReadToken = 0;
            STDAVLNodeDCAS<K,V> lfMap = null;
            if((lfMap = baseNode.getLockFreeMap()) != null &&
               0L != (optimisticReadToken = baseNode.getOptimisticReadToken()) &&
               (lfMap = baseNode.getLockFreeMap()) != null &&
               lfMapForUs(lfMap, key)){
                if(!baseNode.isValid()){
                    continue;
                }
                baseNode.indicateWriteStart();
                if(baseNode.validateOptimisticReadToken(optimisticReadToken)){
                    V result = lfMap.getAndSetValue(value);
                    baseNode.indicateWriteEnd();
                    return result;
                }else{
                    baseNode.indicateWriteEnd();
                }
            }
            // We could not do it without taking a lock
            if(baseNode.lockIfNotLockFreeWithKey(key)){
                // Check if valid
                if (!baseNode.isValid()) {
                    baseNode.unlock();
                    continue; // retry
                }
                // Do the operation
                V result = baseNode.put(key, value);
                adaptIfNeeded(baseNode);
                baseNode.unlock();
                return result;
            }
        }
    }


    public V putIfAbsent(K key, V value){
        while(true){
            @SuppressWarnings("unchecked")
                DualLFCASAVLTreeMapSTD<K, V> baseNode = (DualLFCASAVLTreeMapSTD<K, V>)getBaseNode(key);
            // First check if we can do it without taking a lock
            long optimisticReadToken = 0;
            STDAVLNodeDCAS<K,V> lfMap = null;
            if((lfMap = baseNode.getLockFreeMap()) != null &&
               0L != (optimisticReadToken = baseNode.getOptimisticReadToken()) &&
               (lfMap = baseNode.getLockFreeMap()) != null &&
               lfMapForUs(lfMap, key)){
                if(!baseNode.isValid()){
                    continue;
                }
                baseNode.indicateWriteStart();
                if(baseNode.validateOptimisticReadToken(optimisticReadToken)){
                    V result = lfMap.getValue();
                    while(result == null && !lfMap.compareAndSet(null, value)){
                        result = lfMap.getValue();
                    }
                    baseNode.indicateWriteEnd();
                    return result;
                }else{
                    baseNode.indicateWriteEnd();
                }
            }
            if(baseNode.lockIfNotLockFreeWithKey(key)){
                // Check if valid
                if (!baseNode.isValid()) {
                    baseNode.unlock();
                    continue; // retry
                }
                // Do the operation
                V result = baseNode.putIfAbsent(key, value);
                adaptIfNeeded(baseNode);
                baseNode.unlock();
                return result;
            }
        }
    }


    public V remove(Object key){
        while(true){
            @SuppressWarnings("unchecked")
		DualLFCASAVLTreeMapSTD<K,V> baseNode = (DualLFCASAVLTreeMapSTD<K,V>)getBaseNode(key);
            // First check if we can do it without taking a lock
            long optimisticReadToken = 0;
            STDAVLNodeDCAS<K,V>  lfMap = null;
            if((lfMap = baseNode.getLockFreeMap()) != null &&
               0L != (optimisticReadToken = baseNode.getOptimisticReadToken()) &&
               (lfMap = baseNode.getLockFreeMap()) != null){
                if(!baseNode.isValid()){
                    continue;
                }
                baseNode.indicateWriteStart();
                if(baseNode.validateOptimisticReadToken(optimisticReadToken)){
                    @SuppressWarnings("unchecked")
                        K k = (K)key;
                    if(lfMapForUs(lfMap, k)){
                        V result = lfMap.getAndSetValue(null);
                        baseNode.indicateWriteEnd();
                        return result;
                    }else{
                        baseNode.indicateWriteEnd();
                        return null;
                    }
                }else{
                    baseNode.indicateWriteEnd();
                }
            }
            if(baseNode.lockIfNotLockFree()){
                // Check if valid
                if (baseNode.isValid() == false) {
                    baseNode.unlock();
                    continue; // retry
                }
                // Do the operation
                V result = baseNode.remove(key);
                adaptIfNeeded(baseNode);
                baseNode.unlock();
                return result;
            }
        }
    }

    public void clear(){
        lockAll();
        Object oldRoot = root;
        root = new DualLFCASAVLTreeMapSTD<K, V>();
        unlockAllHelper(oldRoot);
    }

    public Set<Map.Entry<K, V>> entrySet(){
        ArrayList<Map.Entry<K, V>> list = new ArrayList<Map.Entry<K, V>>();
        lockAll();
        addAllToList(root, list);
        unlockAll();
        return new HashSet<Map.Entry<K, V>>(list);
    }

    // ===========================
    
}
