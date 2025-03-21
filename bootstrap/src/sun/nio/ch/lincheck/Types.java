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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class Types {
    private static Type convertAsmArgumentTypeName(String className) {
        switch (className) {
            case "I": return INT_TYPE;
            case "J": return LONG_TYPE;
            case "D": return DOUBLE_TYPE;
            case "F": return FLOAT_TYPE;
            case "Z": return BOOLEAN_TYPE;
            case "B": return BYTE_TYPE;
            case "S": return SHORT_TYPE;
            case "C": return CHAR_TYPE;
            default:
                if (className.startsWith("[")) {
                    return new ArrayType(convertAsmArgumentTypeName(className.substring(1)));
                } else {
                    if (!className.startsWith("L") || !className.endsWith(";")) {
                        throw new IllegalArgumentException("Invalid type name: " + className);
                    }
                    return new ObjectType(className.substring(1, className.length() - 1).replace('/', '.'));
                }
        }
    }

    private static Type convertAsmTypeName(String className) {
        if ("V".equals(className)) {
            return VOID_TYPE;
        } else {
            return convertAsmArgumentTypeName(className);
        }
    }

    /**
     * Parses each descriptor substring of the `methodDesc`, including the return type of the method.
     *
     * @param methodDesc descriptor of the method.
     */
    public static MethodType convertAsmMethodType(String methodDesc) {
        // Modified code for parsing the type descriptors
        // in the method descriptor (which look like this: "(args...)ret")
        List<Type> argumentTypes = new ArrayList<>();
        int currentOffset = 1;

        while (methodDesc.charAt(currentOffset) != ')') {
            int currentDescriptorTypeOffset = currentOffset;
            while (methodDesc.charAt(currentOffset) == '[') {
                currentOffset++;
            }
            if (methodDesc.charAt(currentOffset++) == 'L') {
                int semiColumnOffset = methodDesc.indexOf(';', currentOffset);
                currentOffset = Math.max(currentOffset, semiColumnOffset + 1);
            }
            argumentTypes.add(
                    convertAsmTypeName(methodDesc.substring(currentDescriptorTypeOffset, currentOffset))
            );
        }
        Type returnType = convertAsmTypeName(methodDesc.substring(currentOffset + 1));

        return new MethodType(argumentTypes, returnType);
    }

    public static final VoidType VOID_TYPE = new VoidType();
    public static final IntType INT_TYPE = new IntType();
    public static final LongType LONG_TYPE = new LongType();
    public static final DoubleType DOUBLE_TYPE = new DoubleType();
    public static final FloatType FLOAT_TYPE = new FloatType();
    public static final BooleanType BOOLEAN_TYPE = new BooleanType();
    public static final ByteType BYTE_TYPE = new ByteType();
    public static final ShortType SHORT_TYPE = new ShortType();
    public static final CharType CHAR_TYPE = new CharType();

    public abstract static class Type {}

    public static final class ObjectType extends Type {
        private final String className;

        public ObjectType(String className) {
            this.className = className;
        }

        public String getClassName() {
            return className;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof ObjectType)) return false;

            ObjectType other = (ObjectType) obj;
            return className.equals(other.className);
        }

        @Override
        public int hashCode() {
            return Objects.hash(className);
        }
    }

    public static final class ArrayType extends Type {
        private final Type type;

        public ArrayType(Type type) {
            this.type = type;
        }

        public Type getType() {
            return type;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof ArrayType)) return false;

            ArrayType other = (ArrayType) obj;
            return type.equals(other.type);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type);
        }
    }

    public static class MethodType {
        private final List<Type> argumentTypes;
        private final Type returnType;

        public MethodType(List<Type> argumentTypes, Type returnType) {
            this.argumentTypes = argumentTypes;
            this.returnType = returnType;
        }

        public List<Type> getArgumentTypes() {
            return argumentTypes;
        }

        public Type getReturnType() {
            return returnType;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof MethodType)) return false;

            MethodType other = (MethodType) obj;
            return (
                returnType.equals(other.returnType) &&
                argumentTypes.equals(other.argumentTypes)
            );
        }

        @Override
        public int hashCode() {
            return Objects.hash(returnType, argumentTypes);
        }
    }

    public static final class VoidType extends Type {}
    public static final class IntType extends Type {}
    public static final class LongType extends Type {}
    public static final class DoubleType extends Type {}
    public static final class FloatType extends Type {}
    public static final class BooleanType extends Type {}
    public static final class ByteType extends Type {}
    public static final class ShortType extends Type {}
    public static final class CharType extends Type {}
}