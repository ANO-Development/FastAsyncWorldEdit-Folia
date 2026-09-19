package com.fastasyncworldedit.core.util;

import com.fastasyncworldedit.core.configuration.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.management.MemoryUsage;
import java.lang.reflect.Field;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Execution(ExecutionMode.SAME_THREAD)
class MemUtilTest {

    private int previousLimit;
    private int previousSlowdown;

    @BeforeEach
    void setUp() {
        previousLimit = Settings.settings().MAX_MEMORY_PERCENT;
        previousSlowdown = Settings.settings().SLOWER_MEMORY_PERCENT;
        Settings.settings().MAX_MEMORY_PERCENT = 95;
        Settings.settings().SLOWER_MEMORY_PERCENT = 80;
        MemUtil.memoryPlentifulTask();
    }

    @AfterEach
    void tearDown() {
        Settings.settings().MAX_MEMORY_PERCENT = previousLimit;
        Settings.settings().SLOWER_MEMORY_PERCENT = previousSlowdown;
        MemUtil.memoryPlentifulTask();
        MemUtil.checkAndSetApproachingLimit();
    }

    @Test
    void periodicCheckClearsRecoveredMemoryPressure() {
        MemUtil.memoryLimitedTask();
        assertTrue(MemUtil.isMemoryLimited());
        assertTrue(MemUtil.getUsedBytes() < Runtime.getRuntime().maxMemory() / 2);

        MemUtil.checkAndSetApproachingLimit();

        assertFalse(MemUtil.isMemoryLimited());
        assertTrue(MemUtil.isMemoryFree());
    }

    @Test
    void memoryCheckClearsPressureAfterHeapContracts() {
        MemUtil.memoryLimitedTask();

        MemUtil.calculateMemory(new MemoryUsage(100, 200, 400, 1000));

        assertFalse(MemUtil.isMemoryLimited());
    }

    @Test
    void disablingLimitClearsPreviousPressure() {
        MemUtil.memoryLimitedTask();
        Settings.settings().MAX_MEMORY_PERCENT = -1;

        assertFalse(MemUtil.isMemoryLimitedSlow());
    }

    @ParameterizedTest
    @CsvSource({
            "200, 400, 95, 80, false, false",
            "799, 1000, 95, 80, false, false",
            "800, 1000, 95, 80, false, true",
            "949, 1000, 95, 80, false, true",
            "950, 1000, 95, 80, true, true",
            "990, 1000, 100, 80, false, true",
            "990, 1000, -1, 80, false, true",
            "990, 1000, 95, -1, true, false"
    })
    void usesWholeHeapOccupancy(long used, long committed, int limit, int slowdown, boolean limited, boolean slow) {
        Settings.settings().MAX_MEMORY_PERCENT = limit;
        Settings.settings().SLOWER_MEMORY_PERCENT = slowdown;

        int available = MemUtil.calculateMemory(new MemoryUsage(100, used, committed, 1000));

        assertEquals(limited, MemUtil.isMemoryLimited());
        assertEquals(slow, MemUtil.shouldBeginSlow());
        assertEquals(limited ? (1000 - used) / 10 : Integer.MAX_VALUE, available);
    }

    @Test
    void recoversWithoutRequiringAnotherEdit() {
        MemUtil.calculateMemory(new MemoryUsage(100, 960, 1000, 1000));
        assertTrue(MemUtil.isMemoryLimited());

        MemUtil.calculateMemory(new MemoryUsage(100, 300, 1000, 1000));

        assertFalse(MemUtil.isMemoryLimited());
        assertFalse(MemUtil.shouldBeginSlow());
    }

    @Test
    void fallsBackToRuntimeMaximumWhenManagementMaximumIsUndefined() {
        MemUtil.memoryLimitedTask();

        MemUtil.calculateMemory(new MemoryUsage(100, 200, 400, -1));

        assertFalse(MemUtil.isMemoryLimited());
    }

    @Test
    void callbacksObserveTransitionsAndCannotLeavePressureLatched() throws ReflectiveOperationException {
        AtomicInteger limitedCalls = new AtomicInteger();
        AtomicInteger plentifulCalls = new AtomicInteger();
        AtomicBoolean sawLimited = new AtomicBoolean();
        AtomicBoolean sawRecovery = new AtomicBoolean();
        Runnable limited = () -> {
            limitedCalls.incrementAndGet();
            sawLimited.set(MemUtil.isMemoryLimited());
        };
        Runnable failing = () -> {
            throw new IllegalStateException("Test callback failure");
        };
        Runnable plentiful = () -> {
            plentifulCalls.incrementAndGet();
            sawRecovery.set(MemUtil.isMemoryFree());
        };
        MemUtil.addMemoryLimitedTask(limited);
        MemUtil.addMemoryPlentifulTask(failing);
        MemUtil.addMemoryPlentifulTask(plentiful);
        try {
            MemUtil.calculateMemory(new MemoryUsage(100, 960, 1000, 1000));
            MemUtil.calculateMemory(new MemoryUsage(100, 970, 1000, 1000));
            MemUtil.calculateMemory(new MemoryUsage(100, 200, 400, 1000));
            MemUtil.calculateMemory(new MemoryUsage(100, 200, 400, 1000));

            assertEquals(1, limitedCalls.get());
            assertEquals(1, plentifulCalls.get());
            assertTrue(sawLimited.get());
            assertTrue(sawRecovery.get());
            assertFalse(MemUtil.isMemoryLimited());
        } finally {
            removeCallback("memoryLimitedTasks", limited);
            removeCallback("memoryPlentifulTasks", failing);
            removeCallback("memoryPlentifulTasks", plentiful);
        }
    }

    private static void removeCallback(String queueName, Runnable callback) throws ReflectiveOperationException {
        Field field = MemUtil.class.getDeclaredField(queueName);
        field.setAccessible(true);
        ((Queue<?>) field.get(null)).remove(callback);
    }
}
