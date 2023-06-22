/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.test;

import org.jetbrains.annotations.*;
import org.jetbrains.kotlinx.lincheck.*;
import org.jetbrains.kotlinx.lincheck.annotations.OpGroupConfig;
import org.jetbrains.kotlinx.lincheck.annotations.Operation;
import org.jetbrains.kotlinx.lincheck.annotations.Param;
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen;
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest;
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("removal")
@OpGroupConfig(name = "push_remove", nonParallel = true)
@StressCTest(iterations = 5, actorsBefore = 0, actorsPerThread = 2, actorsAfter = 0)
public class ResultAsParameterTest extends VerifierState {
    private final Stack stack = new Stack();
    private Node lastPushNode = null;

    @Operation(runOnce = true, group = "push_remove")
    public synchronized void push(@Param(gen = IntGen.class, conf = "1:1") int x) {
        lastPushNode = stack.push(x);
    }

    @Operation
    public synchronized int pop() {
        return stack.pop();
    }

    @Operation(runOnce = true, group = "push_remove")
    public synchronized boolean remove() {
        Node node = lastPushNode; // read under potential race, unsafe
        if (node == null)
            return false;
        return stack.remove(node);
    }

    @Test
    public void test() {
        LinChecker.check(ResultAsParameterTest.class);
    }

    @NotNull
    @Override
    protected Object extractState() {
        List<Integer> elements = new ArrayList<>();
        int e;
        while ((e = stack.pop()) != -1) elements.add(e);
        return elements;
    }

    private static class Stack {
        private final Node NIL = new Node(null, 0);
        private Node head = NIL;

        synchronized Node push(int x) { // x >= 0
            Node node = new Node(head, x);
            head.prev = node;
            head = node;
            return node;
        }

        synchronized int pop() {
            if (head == NIL) {
                return -1;
            }
            int res = head.val;
            head = head.next;
            return res;
        }

        synchronized boolean remove(Node node) {
            if (node == head) {
                head = head.next;
                return true;
            } else if (node.prev != null && node.prev.next == node) {
                node.prev.next = node.next;
                return true;
            }
            return false;
        }
    }

    private static class Node {
        Node next;
        Node prev;
        int val;

        public Node(Node next, int val) {
            this.next = next;
            this.val = val;
        }
    }
}
