package com.minsbot.agent.tools;

import sun.misc.Unsafe;

import java.lang.reflect.Field;

/**
 * Utility to modify static final fields in tests using sun.misc.Unsafe.
 * This is needed because Java 17 blocks Field.set() on static final fields.
 */
public final class TestReflectionUtil {

    private static final Unsafe UNSAFE;

    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (Unsafe) f.get(null);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private TestReflectionUtil() {}

    /**
     * Set the value of a static field (even if final) using Unsafe.
     */
    public static void setStaticField(Class<?> clazz, String fieldName, Object newValue) throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        Object base = UNSAFE.staticFieldBase(field);
        long offset = UNSAFE.staticFieldOffset(field);
        UNSAFE.putObject(base, offset, newValue);
    }
}
