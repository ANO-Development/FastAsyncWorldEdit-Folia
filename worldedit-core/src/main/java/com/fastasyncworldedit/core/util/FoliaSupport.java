package com.fastasyncworldedit.core.util;

public final class FoliaSupport {

    private static final Class<?> TICK_THREAD_CLASS = loadTickThreadClass();

    private FoliaSupport() {
    }

    public static boolean isTickThread() {
        return TICK_THREAD_CLASS.isInstance(Thread.currentThread());
    }

    private static Class<?> loadTickThreadClass() {
        try {
            return Class.forName("ca.spottedleaf.moonrise.common.util.TickThread");
        } catch (ClassNotFoundException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
