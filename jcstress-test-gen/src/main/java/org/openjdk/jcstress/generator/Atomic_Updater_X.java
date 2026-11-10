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
package org.openjdk.jcstress.generator;

public class Atomic_Updater_X implements Primitive {

    private String unitValue;
    private String defaultValue;
    private String rValue;
    private String setValue;

    private final Class<?> guardType;
    private final Class<?> primType;
    private final AcqType acqType;
    private final RelType relType;

    public Atomic_Updater_X(Class<?> guardType, Class<?> primType, AcqType acqType, RelType relType) {
        this.guardType = guardType;
        this.primType = primType;
        this.acqType = acqType;
        this.relType = relType;

        unitValue = TestGenerator.getUnitValue(primType);
        defaultValue = TestGenerator.getDefaultValue(primType);
        rValue = TestGenerator.getRValue(primType);
        setValue = TestGenerator.getSetValue(primType);
    }

    @Override
    public String printStateField(String klassName) {
        return "static final " + guardType.getSimpleName() + "<" + klassName + "> g = " + guardType.getSimpleName() + ".<" + klassName + ">newUpdater(" + klassName + ".class, \"v\");" + "\n"
               + "volatile " + primType.getSimpleName() + " v;";
    }

    @Override
    public String printAcquire(String region) {
        switch (acqType) {
            case compareAndSet:
                return String.format("r.r1 = g.compareAndSet(this, %s, %s) ? %s : %s; \n" + region,
                        setValue,
                        defaultValue,
                        setValue,
                        defaultValue
                );
            case get:
                return "r.r1 = g.get(this) == " + defaultValue + "? " + defaultValue + " : " + setValue + " ; \n" + region;
            case getAndAdd:
                return "r.r1 = g.getAndAdd(this, " + rValue + ") == " + defaultValue + "? " + defaultValue + " : " + setValue + " ; \n" + region;
            case getAndSet:
                return "r.r1 = g.getAndSet(this, " + rValue + ") == " + defaultValue + "? " + defaultValue + " : " + setValue + " ; \n" + region;
            default:
                throw new IllegalStateException("" + acqType);
        }
    }

    @Override
    public String printRelease(String region) {
        switch (relType) {
            case set:
                return region + "g.set(this, " +setValue + ");";
            case compareAndSet:
                return region + "g.compareAndSet(this, " + defaultValue + ", " +setValue + ");";
            case getAndAdd:
                return region + "g.getAndAdd(this, " + rValue + ");";
            case getAndSet:
                return region + "g.getAndSet(this, " + rValue + ");";
            default:
                throw new IllegalStateException("" + relType);
        }
    }

    @Override
    public String getClassName() {
        return guardType.getName();
    }

}
