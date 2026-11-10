/*
 * Copyright (c) 2005, 2014, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.jcstress.generator.seqcst;

import java.util.Objects;

public abstract class Op {

    private final Type type;
    protected final int varId;

    private Op(Type type, int varId) {
        this.type = type;
        this.varId = varId;
    }

    public Type getType() {
        return type;
    }

    public boolean isLoad() {
        return type == Type.LOAD;
    }

    public boolean isStore() {
        return type == Type.STORE;
    }

    public int getVarId() {
        return varId;
    }

    public abstract Result getResult();
    public abstract Value getValue();
    public abstract Op renumber(int newVarId);

    public static class LoadOp extends Op {
        private final Result res;

        public LoadOp(int varId, Result res) {
            super(Type.LOAD, varId);
            this.res = res;
        }

        @Override
        public Result getResult() {
            return Objects.requireNonNull(res);
        }

        @Override
        public Value getValue() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Op renumber(int newVarId) {
            return new LoadOp(newVarId, res);
        }

        @Override
        public String toString() {
            return "r" + res + " = x" + varId;
        }

    }

    public static class StoreOp extends Op {
        private final Value value;

        public StoreOp(int varId, Value value) {
            super(Type.STORE, varId);
            this.value = value;
        }

        @Override
        public Result getResult() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Value getValue() {
            if (value == null) {
                throw new IllegalStateException("valueId unset");
            }
            return value;
        }

        @Override
        public Op renumber(int newVarId) {
            return new StoreOp(newVarId, value);
        }

        @Override
        public String toString() {
            return "x" + varId + " = C" + value;
        }

    }

    public enum Type {
        LOAD,
        STORE,
    }

}
