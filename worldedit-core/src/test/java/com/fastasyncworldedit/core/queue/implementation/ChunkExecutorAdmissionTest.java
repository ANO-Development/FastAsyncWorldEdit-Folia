package com.fastasyncworldedit.core.queue.implementation;

import com.fastasyncworldedit.core.Fawe;
import com.fastasyncworldedit.core.FaweCache;
import com.fastasyncworldedit.core.configuration.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;

@Isolated
class ChunkExecutorAdmissionTest {

    @Test
    void workerAdmissionWaitsForCapacityAndResumes() throws Exception {
        Settings.QUEUE previous = Settings.settings().QUEUE;
        Settings.settings().QUEUE = new Settings.QUEUE();
        Settings.settings().QUEUE.PARALLEL_THREADS = 1;
        Settings.settings().QUEUE.TARGET_SIZE = 1;
        var executor = FaweCache.INSTANCE.newBlockingExecutor("admission-test-%d");
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var rejected = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var admissionStarted = new CountDownLatch(1);
        var admitted = new CountDownLatch(1);
        var writes = new AtomicInteger();
        Thread producer = new Thread(() -> {
            try (var fawe = mockStatic(Fawe.class)) {
                fawe.when(Fawe::isMainThread).thenReturn(false);
                admissionStarted.countDown();
                executor.submit(writes::incrementAndGet);
                admitted.countDown();
            } catch (Throwable failure) {
                rejected.set(failure);
            }
        });
        try {
            executor.submit(() -> {
                started.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return null;
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            executor.submit(writes::incrementAndGet);
            producer.start();
            assertTrue(admissionStarted.await(5, TimeUnit.SECONDS));
            assertFalse(admitted.await(100, TimeUnit.MILLISECONDS));
            assertEquals(1, executor.getQueue().size());
            release.countDown();
            assertTrue(admitted.await(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            producer.join(5000);
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            Settings.settings().QUEUE = previous;
        }
        assertNull(rejected.get());
        assertEquals(2, writes.get());
    }

    @Test
    void saturatedWorkerTimesOutWithoutAcceptingMoreWork() throws Exception {
        Settings.QUEUE previous = Settings.settings().QUEUE;
        Settings.settings().QUEUE = new Settings.QUEUE();
        Settings.settings().QUEUE.PARALLEL_THREADS = 1;
        Settings.settings().QUEUE.TARGET_SIZE = 1;
        Settings.settings().QUEUE.ADMISSION_TIMEOUT_MS = 30;
        var executor = FaweCache.INSTANCE.newBlockingExecutor("admission-test-%d");
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var fawe = mockStatic(Fawe.class)) {
            fawe.when(Fawe::isMainThread).thenReturn(false);
            executor.submit(() -> {
                started.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return null;
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            executor.submit(() -> {});
            assertThrows(RejectedExecutionException.class, () -> executor.submit(() -> fail("Rejected work ran")));
            assertEquals(1, executor.getQueue().size());
        } finally {
            release.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            Settings.settings().QUEUE = previous;
        }
    }

    @Test
    void saturatedExecutorRejectsTickThreadWithoutRunningWorkInline() throws Exception {
        Settings.QUEUE previous = Settings.settings().QUEUE;
        Settings.settings().QUEUE = new Settings.QUEUE();
        Settings.settings().QUEUE.PARALLEL_THREADS = 1;
        Settings.settings().QUEUE.TARGET_SIZE = 2;
        var executor = FaweCache.INSTANCE.newBlockingExecutor("admission-test-%d");
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var writes = new AtomicInteger();
        try (var fawe = mockStatic(Fawe.class)) {
            fawe.when(Fawe::isMainThread).thenReturn(true);
            executor.submit(() -> {
                started.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return null;
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            executor.submit(writes::incrementAndGet);
            executor.submit(writes::incrementAndGet);
            assertThrows(RejectedExecutionException.class, () -> executor.submit(writes::incrementAndGet));
            assertEquals(2, executor.getQueue().size());
            assertEquals(0, writes.get());
        } finally {
            release.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            Settings.settings().QUEUE = previous;
        }
        assertEquals(2, writes.get());
    }
}
