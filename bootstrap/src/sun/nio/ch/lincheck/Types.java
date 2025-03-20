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

import java.util.Arrays;
import java.util.Objects;

public class Types {
    private static class Utils {
        @FunctionalInterface
        private interface Consumer2<T, U> {
            void accept(T t, U u);
        }

        /**
         * Calculate the number of type descriptors encoded in the `methodDesc`. For each descriptor substring it calls the `consumer`.
         * @param methodDesc descriptor of the method.
         * @param consumer callback called for each descriptor type. Passed parameters are `begin` and `end` — indexes for the type descriptor substring in the `methodDesc`.
         * @return total number of type descriptors in the method descriptor (e.g. number of arguments and plus one: the return type).
         */
        private static int iterateThroughDescriptors(String methodDesc, Consumer2<Integer, Integer> consumer) {
            // Modified code for iterating over the type descriptors
            // in the method descriptor (which look like this: "(args...)ret")
            int argumentCount = 0;
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
                if (consumer != null) {
                    consumer.accept(currentDescriptorTypeOffset, currentOffset);
                }
                argumentCount++;
            }
            if (consumer != null) {
                consumer.accept(currentOffset + 1, methodDesc.length());
            }

            return argumentCount + 1 /* return type */;
        }

        public static String[] getAllTypeDescriptors(String methodDesc) {
            int descriptorsCount = iterateThroughDescriptors(methodDesc, null);
            String[] descriptors = new String[descriptorsCount];
            int[] currentIndex = { 0 };

            iterateThroughDescriptors(methodDesc, (begin, end) -> {
                descriptors[currentIndex[0]] = methodDesc.substring(begin, end);
                currentIndex[0]++;
            });

            return descriptors;
        }
    }

    private static ArgumentType convertAsmArgumentTypeName(String className) {
        switch (className) {
            case "I": return ArgumentType.Primitive.Int.get();
            case "J": return ArgumentType.Primitive.Long.get();
            case "D": return ArgumentType.Primitive.Double.get();
            case "F": return ArgumentType.Primitive.Float.get();
            case "Z": return ArgumentType.Primitive.Boolean.get();
            case "B": return ArgumentType.Primitive.Byte.get();
            case "S": return ArgumentType.Primitive.Short.get();
            case "C": return ArgumentType.Primitive.Char.get();
            default:
                if (className.startsWith("[")) {
                    return new ArgumentType.Array(convertAsmArgumentTypeName(className.substring(1)));
                } else {
                    if (!className.startsWith("L") || !className.endsWith(";")) {
                        throw new IllegalArgumentException("Invalid type name: " + className);
                    }
                    return new ArgumentType.Object(className.substring(1, className.length() - 1).replace('/', '.'));
                }
        }
    }

    private static Type convertAsmTypeName(String className) {
        if ("V".equals(className)) {
            return Type.Void.get();
        } else {
            return convertAsmArgumentTypeName(className);
        }
    }

    public static MethodType convertAsmMethodType(String methodDesc) {
        String[] typeStrings = Utils.getAllTypeDescriptors(methodDesc); // last one is return type

        ArgumentType[] argumentTypes = new ArgumentType[typeStrings.length - 1];
        Type returnType = Types.convertAsmTypeName(typeStrings[typeStrings.length - 1]);

        for (int i = 0; i < argumentTypes.length; ++i) {
            argumentTypes[i] = convertAsmArgumentTypeName(typeStrings[i]);
        }

        return new MethodType(argumentTypes, returnType);
    }

    // ------ Methods -------------------- //
    public static class MethodType {
        private final ArgumentType[] argumentTypes;
        private final Type returnType;

        public MethodType(ArgumentType[] argumentTypes, Type returnType) {
            this.argumentTypes = argumentTypes;
            this.returnType = returnType;
        }

        public ArgumentType[] getArgumentTypes() {
            return argumentTypes;
        }

        public Type getReturnType() {
            return returnType;
        }

        @Override
        public boolean equals(java.lang.Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof MethodType)) return false;

            MethodType other = (MethodType) obj;
            return (
                returnType.equals(other.returnType) &&
                Arrays.equals(argumentTypes, other.argumentTypes)
            );
        }

        @Override
        public int hashCode() {
            return Objects.hash(returnType, Arrays.hashCode(argumentTypes));
        }
    }

    public static class MethodSignature {
        private final String name;
        private final MethodType methodType;

        public MethodSignature(String name, MethodType methodType) {
            this.name = name;
            this.methodType = methodType;
        }

        public String getName() {
            return name;
        }

        public MethodType getMethodType() {
            return methodType;
        }

        @Override
        public boolean equals(java.lang.Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof MethodSignature)) return false;

            MethodSignature other = (MethodSignature) obj;
            return (
                name.equals(other.name) &&
                methodType.equals(other.methodType)
            );
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, methodType);
        }
    }

    // ------ Types ---------------------- //
    public abstract static class Type {
        public static final class Void extends Type {
            private Void() {}
            static class Holder {
                static final Void INSTANCE = new Void();
            }
            public static Void get() {
                return Holder.INSTANCE;
            }
        }
    }

    public abstract static class ArgumentType extends Type {
        public static final class Object extends ArgumentType {
            private final String className;

            public Object(String className) {
                this.className = className;
            }

            public String getClassName() {
                return className;
            }

            @Override
            public boolean equals(java.lang.Object obj) {
                if (this == obj) return true;
                if (!(obj instanceof ArgumentType.Object)) return false;

                ArgumentType.Object other = (ArgumentType.Object) obj;
                return className.equals(other.className);
            }

            @Override
            public int hashCode() {
                return Objects.hash(className);
            }
        }
        public static final class Array extends ArgumentType {
            private final ArgumentType type;

            public Array(ArgumentType type) {
                this.type = type;
            }

            public ArgumentType getType() {
                return type;
            }

            @Override
            public boolean equals(java.lang.Object obj) {
                if (this == obj) return true;
                if (!(obj instanceof ArgumentType.Array)) return false;

                ArgumentType.Array other = (ArgumentType.Array) obj;
                return type.equals(other.type);
            }

            @Override
            public int hashCode() {
                return Objects.hash(type);
            }
        }

        public abstract static class Primitive extends ArgumentType {
            public static final class Int extends Primitive {
                private Int() {}
                private static class Holder {
                    static final Int INSTANCE = new Int();
                }
                public static Int get() {
                    return Int.Holder.INSTANCE;
                }
            }
            public static final class Long extends Primitive {
                private Long() {}
                private static class Holder {
                    static final Long INSTANCE = new Long();
                }
                public static Long get() {
                    return Long.Holder.INSTANCE;
                }
            }
            public static final class Double extends Primitive {
                private Double() {}
                private static class Holder {
                    static final Double INSTANCE = new Double();
                }
                public static Double get() {
                    return Double.Holder.INSTANCE;
                }
            }
            public static final class Float extends Primitive {
                private Float() {}
                private static class Holder {
                    static final Float INSTANCE = new Float();
                }
                public static Float get() {
                    return Float.Holder.INSTANCE;
                }
            }
            public static final class Boolean extends Primitive {
                private Boolean() {}
                private static class Holder {
                    static final Boolean INSTANCE = new Boolean();
                }
                public static Boolean get() {
                    return Boolean.Holder.INSTANCE;
                }
            }
            public static final class Byte extends Primitive {
                private Byte() {}
                private static class Holder {
                    static final Byte INSTANCE = new Byte();
                }
                public static Byte get() {
                    return Byte.Holder.INSTANCE;
                }
            }
            public static final class Short extends Primitive {
                private Short() {}
                private static class Holder {
                    static final Short INSTANCE = new Short();
                }
                public static Short get() {
                    return Short.Holder.INSTANCE;
                }
            }
            public static final class Char extends Primitive {
                private Char() {}
                private static class Holder {
                    static final Char INSTANCE = new Char();
                }
                public static Char get() {
                    return Char.Holder.INSTANCE;
                }
            }
        }
    }
}