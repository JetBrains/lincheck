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

import org.jctools.queues.atomic.*;
import org.jetbrains.annotations.*;
import org.jetbrains.kotlinx.lincheck.annotations.*;

import java.util.*;

public class NonParallelOpGroupTest extends AbstractLincheckTest {
    private final SpscLinkedAtomicQueue<Integer> queue = new SpscLinkedAtomicQueue<>();

    @Operation(nonParallelGroup = "producer")
    public void offer(Integer x) {
        queue.offer(x);
    }

    @Operation(nonParallelGroup = "consumer")
    public Integer poll() {
        return queue.poll();
    }

    @NotNull
    @Override
    protected Object extractState() {
        List<Integer> elements = new ArrayList<>();
        while (true) {
            Integer el = poll();
            if (el == null) break;
            elements.add(el);
        }
        return elements;
    }
}
