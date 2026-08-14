/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test;

import org.jctools.queues.atomic.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.lincheck.datastructures.Options;


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

    @Override
    public <O extends Options<@NotNull O, ?>> void customize(@NotNull O $this$customize) {
        // We ignore this as there is a spin loop. Unignore this once loop detector is up and running
        ignoreExperimentalModelChecking($this$customize);
    }
}
