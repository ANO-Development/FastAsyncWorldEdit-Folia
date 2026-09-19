package com.fastasyncworldedit.core.util;

import com.fastasyncworldedit.core.configuration.Settings;
import com.sk89q.worldedit.internal.util.LogManagerCompat;
import org.apache.logging.log4j.Logger;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class MemUtil {

    private static final Logger LOGGER = LogManagerCompat.getLogger();
    private static final AtomicBoolean memory = new AtomicBoolean(false);
    private static final AtomicBoolean slower = new AtomicBoolean(false);
    private static final long WARNING_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final AtomicLong lastWarning = new AtomicLong(System.nanoTime() - WARNING_INTERVAL_NANOS);

    public static boolean isMemoryFree() {
        return !memory.get();
    }

    public static boolean isMemoryLimited() {
        return memory.get();
    }

    public static boolean isMemoryLimitedSlow() {
        if (memory.get()) {
            calculateMemory();
            return memory.get();
        }
        return false;
    }

    public static boolean shouldBeginSlow() {
        return slower.get();
    }

    public static long getUsedBytes() {
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }

    public static long getFreeBytes() {
        return Runtime.getRuntime().maxMemory() - getUsedBytes();
    }

    public static int calculateMemory() {
        return calculateMemory(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage());
    }

    static int calculateMemory(MemoryUsage heap) {
        long maximum = heap.getMax() > 0 ? heap.getMax() : Runtime.getRuntime().maxMemory();
        double usedPercent = 100.0 * heap.getUsed() / maximum;
        int limit = Settings.settings().MAX_MEMORY_PERCENT;
        int slowdown = Settings.settings().SLOWER_MEMORY_PERCENT;
        slower.set(slowdown > 0 && slowdown <= 100 && usedPercent >= slowdown);
        if (limit > 0 && limit < 100 && usedPercent >= limit) {
            memoryLimitedTask();
            return (int) Math.max(0, 100 - usedPercent);
        }
        memoryPlentifulTask();
        return Integer.MAX_VALUE;
    }

    public static void checkAndSetApproachingLimit() {
        calculateMemory();
    }

    private static final Queue<Runnable> memoryLimitedTasks = new ConcurrentLinkedQueue<>();
    private static final Queue<Runnable> memoryPlentifulTasks = new ConcurrentLinkedQueue<>();

    public static void addMemoryLimitedTask(Runnable run) {
        if (run != null) {
            memoryLimitedTasks.add(run);
        }
    }

    public static void addMemoryPlentifulTask(Runnable run) {
        if (run != null) {
            memoryPlentifulTasks.add(run);
        }
    }

    public static void memoryLimitedTask() {
        if (memory.compareAndSet(false, true)) {
            long now = System.nanoTime();
            long previous = lastWarning.get();
            if (now - previous >= WARNING_INTERVAL_NANOS && lastWarning.compareAndSet(previous, now)) {
                LOGGER.warn("High heap usage detected; FAWE will limit edits until memory usage recovers.");
            }
            runMemoryTasks(memoryLimitedTasks);
        }
    }

    public static void memoryPlentifulTask() {
        if (memory.compareAndSet(true, false)) {
            runMemoryTasks(memoryPlentifulTasks);
        }
    }

    private static void runMemoryTasks(Queue<Runnable> tasks) {
        for (Runnable task : tasks) {
            try {
                task.run();
            } catch (RuntimeException exception) {
                LOGGER.error("Memory pressure callback failed", exception);
            }
        }
    }

}
