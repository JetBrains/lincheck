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

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Objects of this class represents nodes in an AVL tree
 * implementation. Please see "DualLFCASAVLTreeMapSTD.java" for
 * details.
 * 
 * @author Kjell Winblad
 */
public class STDAVLNodeDCAS<K,V>{

    private static final AtomicReferenceFieldUpdater<STDAVLNodeDCAS, STDAVLNodeDCAS> leftUpdater =
        AtomicReferenceFieldUpdater.newUpdater(STDAVLNodeDCAS.class, STDAVLNodeDCAS.class, "left");
    private static final AtomicReferenceFieldUpdater<STDAVLNodeDCAS, STDAVLNodeDCAS> rightUpdater =
        AtomicReferenceFieldUpdater.newUpdater(STDAVLNodeDCAS.class, STDAVLNodeDCAS.class, "right");
    private static final AtomicReferenceFieldUpdater<STDAVLNodeDCAS, Object> valueUpdater =
        AtomicReferenceFieldUpdater.newUpdater(STDAVLNodeDCAS.class, Object.class, "value");

    K key;
    private volatile STDAVLNodeDCAS<K,V> left;
    private volatile STDAVLNodeDCAS<K,V> right;
    private volatile Object value;
    int balance = 0;
    STDAVLNodeDCAS<K,V> parent = null;
    public STDAVLNodeDCAS(K key, V value){
        this.key = key;
        this.value = (Object)value;
    }

    public STDAVLNodeDCAS<K,V> getLeft(){
        return left;
    }

    public STDAVLNodeDCAS<K,V> getRight(){
        return right;
    }


    public void setLeft(STDAVLNodeDCAS<K,V> n){
        leftUpdater.lazySet(this, n);
    }

    public void setRight(STDAVLNodeDCAS<K,V> n){
        rightUpdater.lazySet(this, n);
    }

    @SuppressWarnings("unchecked")
    public V getValue(){
        return (V)value;
    }

    public K getKey(){
        return key;
    }

    public void setValue(V n){
        valueUpdater.lazySet(this, (Object)n);
    }

    @SuppressWarnings("unchecked")
    public V getAndSetValue(V n){
        return (V)valueUpdater.getAndSet(this, (Object)n);
    }

    public boolean compareAndSet(V expect, V update){
        return valueUpdater.compareAndSet(this, (Object)expect, (Object)update);
    }


    public String toString(){
        return "NODE(" + key + ", " + balance + ")";
    }
}



final class STDAVLNodeDCASNOFALSE<K,V> extends STDAVLNodeDCAS<K,V>{

    AtomicReferenceArray<V> valueArray = new AtomicReferenceArray<V>(32);

    public STDAVLNodeDCASNOFALSE(K key, V value){
	super(key, value);
    }

    public V getValue(){
        return (V)valueArray.get(16);
    }

    public void setValue(V n){
        valueArray.lazySet(16, n);
    }

    public V getAndSetValue(V n){
        return (V)valueArray.getAndSet(16, n);
    }

    public boolean compareAndSet(V expect, V update){
        return valueArray.compareAndSet(16, expect, update);
    }


    public String toString(){
        return "NODE(" + key + ", " + balance + ")";
    }
}
