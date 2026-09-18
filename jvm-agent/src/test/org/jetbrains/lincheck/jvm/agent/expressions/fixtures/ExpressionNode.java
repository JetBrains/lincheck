/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.expressions.fixtures;

/** Application-class graph used to test recursive private-member discovery. */
public class ExpressionNode {
    private final String text;
    private final int value;
    private final ExpressionNode next;

    public ExpressionNode(String text, int value, ExpressionNode next) {
        this.text = text;
        this.value = value;
        this.next = next;
    }

    private String text() {
        return text;
    }

    private ExpressionNode next() {
        return next;
    }
}
