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

/** Member shapes shared by the agent-side expression evaluation tests. */
public class ExpressionTarget extends ExpressionBase {
    private static final String MODE = "prod";
    public static final int LIMIT = 3;

    private final String secret;
    public final int count;
    private final boolean enabled;
    private final ExpressionNode node = new ExpressionNode("root", 5, new ExpressionNode("leaf", 9, null));

    public ExpressionTarget(String secret, int count, boolean enabled) {
        this.secret = secret;
        this.count = count;
        this.enabled = enabled;
    }

    private boolean hasSecret(String expected) {
        return secret.equals(expected);
    }

    private int add(int first, int second) {
        return first + second;
    }

    private int overloaded(int value) {
        return value + count;
    }

    private String overloaded(String value) {
        return value + secret;
    }

    private int doubled() {
        return count * 2;
    }

    private boolean enabled() {
        return enabled;
    }

    private <T> boolean same(T first, T second) {
        return first.equals(second);
    }

    private boolean contains(int[] values, int expected) {
        for (int value : values) if (value == expected) return true;
        return false;
    }

    private boolean hasPrefix(String... values) {
        return values.length == 2 && values[0].equals("a") && values[1].equals("b");
    }

    public static final class Nested {
        public final int value;

        public Nested(int value) {
            this.value = value;
        }
    }

    public final class Inner {
        public final int value;

        public Inner(int value) {
            this.value = value;
        }
    }

    private ExpressionTarget self() {
        return this;
    }

    private static boolean isProduction() {
        return MODE.equals("prod");
    }
}
