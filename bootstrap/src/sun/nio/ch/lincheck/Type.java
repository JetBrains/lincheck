/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package sun.nio.ch.lincheck;

/**
 * Enumeration representing various value types that can be tracked and analyzed in Lincheck.
 *
 * <p>
 * Note: this enumeration basically represents JVM types. 
 * It is similar to {@code asm.Type} class from the ASM library.
 * We do not use ASM class here, to avoid adding a dependency on ASM library to the bootstrap module.
 * However, we rely on the fact that {@code asm.Type.sort == Type.ordinal()}.
 */
public enum Type {
    VOID,
    BOOLEAN,
    CHAR,
    BYTE,
    SHORT,
    INT,
    FLOAT,
    LONG,
    DOUBLE,
    ARRAY,
    OBJECT;

    /**
     * Returns the {@code Type} corresponding to the specified integer value.
     *
     * @param sort the integer value representing the desired {@code Type}
     * @return the {@code Type} corresponding to the given integer value.
     * @throws IllegalArgumentException if the integer value is out of range.
     */
    static Type getType(int sort) {
        switch (sort) {
            case 0:  return VOID;
            case 1:  return BOOLEAN;
            case 2:  return CHAR;
            case 3:  return BYTE;
            case 4:  return SHORT;
            case 5:  return INT;
            case 6:  return FLOAT;
            case 7:  return LONG;
            case 8:  return DOUBLE;
            case 9:  return ARRAY;
            case 10: return OBJECT;
            default: throw new IllegalArgumentException("Invalid sort value: " + sort);
        }
    }

    /**
     * Determines whether the given {@code Type} represents a primitive type.
     */
    static boolean isPrimitiveType(Type type) {
        return type.ordinal() > VOID.ordinal() && type.ordinal() <= DOUBLE.ordinal();
    }

    /**
     * Determines whether the specified {@code Type} is a reference type.
     */
    static boolean isReferenceType(Type type) {
        return type == ARRAY || type == OBJECT;
    }
}
