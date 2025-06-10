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
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class Types {
    private static Type convertAsmTypeName(String className) {
        switch (className) {
            case "V": return VOID_TYPE;
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
                    return new ArrayType(convertAsmTypeName(className.substring(1)));
                } else {
                    if (!className.startsWith("L") || !className.endsWith(";")) {
                        throw new IllegalArgumentException("Invalid type name: " + className);
                    }
                    return new ObjectType(className.substring(1, className.length() - 1).replace('/', '.'));
                }
        }
    }

    /**
     * Parses each descriptor substring of the `methodDesc`, including the return type of the method.
     *
     * @param methodDesc descriptor of the method.
     */
    public static MethodType convertAsmMethodType(String methodDesc) {
        // Modified code for parsing the type descriptors
        // in the method descriptor (which looks like this: "(args...)ret")
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

    public static boolean isPrimitive(Types.Type type) {
        return (
            type instanceof IntType     ||
            type instanceof LongType    ||
            type instanceof DoubleType  ||
            type instanceof FloatType   ||
            type instanceof BooleanType ||
            type instanceof ByteType    ||
            type instanceof ShortType   ||
            type instanceof CharType
        );
    }

    public static final    VoidType    VOID_TYPE = new VoidType();
    public static final     IntType     INT_TYPE = new IntType();
    public static final    LongType    LONG_TYPE = new LongType();
    public static final  DoubleType  DOUBLE_TYPE = new DoubleType();
    public static final   FloatType   FLOAT_TYPE = new FloatType();
    public static final BooleanType BOOLEAN_TYPE = new BooleanType();
    public static final    ByteType    BYTE_TYPE = new ByteType();
    public static final   ShortType   SHORT_TYPE = new ShortType();
    public static final    CharType    CHAR_TYPE = new CharType();

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

        @Override
        public String toString() {
            return className;
        }
    }

    public static final class ArrayType extends Type {
        private final Type elementType;

        public ArrayType(Type elementType) {
            this.elementType = elementType;
        }

        public Type getElementType() {
            return elementType;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof ArrayType)) return false;

            ArrayType other = (ArrayType) obj;
            return elementType.equals(other.elementType);
        }

        @Override
        public int hashCode() {
            return Objects.hash(elementType);
        }

        @Override
        public String toString() {
            return elementType.toString() + "[]";
        }
    }

    public static final class VoidType extends Type {
        @Override
        public String toString() {
            return "void";
        }
    }
    
    public static final class IntType extends Type {
        @Override
        public String toString() {
            return "int";
        }
    }
    
    public static final class LongType extends Type {
        @Override
        public String toString() {
            return "long";
        }
    }
    
    public static final class DoubleType extends Type {
        @Override
        public String toString() {
            return "double";
        }
    }
    
    public static final class FloatType extends Type {
        @Override
        public String toString() {
            return "float";
        }
    }
    
    public static final class BooleanType extends Type {
        @Override
        public String toString() {
            return "boolean";
        }
    }
    
    public static final class ByteType extends Type {
        @Override
        public String toString() {
            return "byte";
        }
    }
    
    public static final class ShortType extends Type {
        @Override
        public String toString() {
            return "short";
        }
    }
    
    public static final class CharType extends Type {
        @Override
        public String toString() {
            return "char";
        }
    }

    public static class MethodType {
        private final List<Type> argumentTypes;
        private final Type returnType;

        public MethodType(List<Type> argumentTypes, Type returnType) {
            this.argumentTypes = argumentTypes;
            this.returnType = returnType;
        }
        
        public MethodType(Type returnType, Type... argumentTypes) {
            this(Arrays.asList(argumentTypes), returnType);
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

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("(");
            for (int i = 0; i < argumentTypes.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(argumentTypes.get(i).toString());
            }
            sb.append("): ");
            sb.append(returnType.toString());
            return sb.toString();
        }
    }
}
