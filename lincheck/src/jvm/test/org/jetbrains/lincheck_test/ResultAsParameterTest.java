/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test;

import org.jetbrains.lincheck.Lincheck;
import org.jetbrains.lincheck.LincheckSettings;
import org.jetbrains.lincheck.LincheckSettings.ScenarioGeneration;
import org.jetbrains.lincheck.LincheckSettings.TestingStrategy.Stress;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.lincheck.datastructures.Param;
import org.jetbrains.lincheck.datastructures.IntGen;
import org.junit.Test;

public class ResultAsParameterTest {
    private final Stack stack = new Stack();
    private Node lastPushNode = null;

    @Operation(runOnce = true, nonParallelGroup = "push_remove")
    public synchronized void push(@Param(gen = IntGen.class, conf = "1:1") int x) {
        lastPushNode = stack.push(x);
    }

    @Operation
    public synchronized int pop() {
        return stack.pop();
    }

    @Operation(runOnce = true, nonParallelGroup = "push_remove")
    public synchronized boolean remove() {
        Node node = lastPushNode; // read under potential race, unsafe
        if (node == null)
            return false;
        return stack.remove(node);
    }

    @Test
    public void test() {
        LincheckSettings settings = new LincheckSettings()
            .strategy(new Stress())
            .scenarioGeneration(new ScenarioGeneration()
                .randomScenarios(5)
                .operationsBefore(0)
                .operationsPerThread(2)
                .operationsAfter(0)
            );
        Lincheck.runConcurrentTest(settings, ResultAsParameterTest.class);
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
