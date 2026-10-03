package com.fastasyncworldedit.core.queue.implementation;

import com.fastasyncworldedit.core.configuration.Settings;
import com.fastasyncworldedit.core.queue.IBatchProcessor;
import com.fastasyncworldedit.core.queue.IChunkCache;
import com.fastasyncworldedit.core.util.TaskManager;
import com.sk89q.worldedit.world.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;

@Isolated
class QueueOwnershipTest {
    @Test
    void newEditDoesNotReinitializeAnotherEditsQueueOnTheSameWorker() {
        Settings.QUEUE previous = Settings.settings().QUEUE;
        Settings.settings().QUEUE = new Settings.QUEUE();
        Settings.settings().QUEUE.PARALLEL_THREADS = 1;
        try (var tasks = mockStatic(TaskManager.class)) {
            tasks.when(TaskManager::taskManager).thenReturn(mock(TaskManager.class));
            var handler = mock(QueueHandler.class, withSettings().useConstructor().defaultAnswer(CALLS_REAL_METHODS));
            try {
                World world = mock(World.class);
                doReturn(mock(IChunkCache.class)).when(handler).getOrCreateWorldCache(world);
                var first = handler.getQueue(world);
                IBatchProcessor processor = mock(IBatchProcessor.class);
                first.setProcessor(processor);
                var second = handler.getQueue(world);
                assertNotSame(first, second);
                assertSame(processor, first.getProcessor());
            } finally {
                handler.getBlockingExecutor().shutdown();
                handler.getForkJoinPoolPrimary().shutdown();
                handler.getForkJoinPoolSecondary().shutdown();
            }
        } finally {
            Settings.settings().QUEUE = previous;
        }
    }
}
