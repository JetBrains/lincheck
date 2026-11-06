package org.jetbrains.lincheck.util;

public class ReflectionFieldFixture {
    public interface Constants {
        int inherited = 0;
    }

    public static class Grandparent {
        public final int inherited = 1;
    }

    public static class Parent extends Grandparent {
        protected final int inherited = 2;
        public final int value = 3;
    }

    public static class Child extends Parent implements Constants {
        public final int value = 4;
    }
}
