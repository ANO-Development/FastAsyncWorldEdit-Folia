package com.fastasyncworldedit.bukkit.util;

import com.fastasyncworldedit.core.util.FoliaSupport;
import com.fastasyncworldedit.core.util.TaskManager;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.bukkit.BukkitEntity;
import com.sk89q.worldedit.bukkit.BukkitPlayer;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.world.World;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public final class FoliaTaskManager extends TaskManager {

    private static final long OWNED_TASK_TIMEOUT_SECONDS = 30L;
    private final Plugin plugin;
    private final AtomicInteger nextTaskId = new AtomicInteger(1);
    private final Map<Integer, ScheduledTask> repeatingTasks = new ConcurrentHashMap<>();

    public FoliaTaskManager(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public int repeat(@Nonnull Runnable runnable, int delay, int interval) {
        long initialDelay = Math.max(1L, delay);
        long period = Math.max(1L, interval);
        return remember(Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                plugin,
                task -> runnable.run(),
                initialDelay,
                period
        ));
    }

    @Override
    public int repeatAt(
            @Nonnull Runnable runnable,
            @Nonnull World world,
            int chunkX,
            int chunkZ,
            int delay,
            int interval
    ) {
        return remember(Bukkit.getRegionScheduler().runAtFixedRate(
                plugin,
                BukkitAdapter.adapt(world),
                chunkX,
                chunkZ,
                task -> runnable.run(),
                Math.max(1L, delay),
                Math.max(1L, interval)
        ));
    }

    @Override
    public int repeatAsync(@Nonnull Runnable runnable, int interval) {
        long period = ticksToMilliseconds(interval);
        return remember(Bukkit.getAsyncScheduler().runAtFixedRate(
                plugin,
                task -> runnable.run(),
                period,
                period,
                TimeUnit.MILLISECONDS
        ));
    }

    @Override
    public void async(@Nonnull Runnable runnable) {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> runnable.run());
    }

    @Override
    public void task(@Nonnull Runnable runnable) {
        if (Bukkit.isGlobalTickThread()) {
            runnable.run();
            return;
        }
        Bukkit.getGlobalRegionScheduler().run(plugin, task -> runnable.run());
    }

    @Override
    public void taskAt(@Nonnull Runnable runnable, @Nonnull World world, int chunkX, int chunkZ) {
        org.bukkit.World bukkitWorld = BukkitAdapter.adapt(world);
        if (Bukkit.isOwnedByCurrentRegion(bukkitWorld, chunkX, chunkZ)) {
            runnable.run();
            return;
        }
        Bukkit.getRegionScheduler().run(plugin, bukkitWorld, chunkX, chunkZ, task -> runnable.run());
    }

    @Override
    public void taskWith(@Nonnull Runnable runnable, @Nonnull Entity entity) {
        org.bukkit.entity.Entity bukkitEntity = bukkitEntity(entity);
        if (Bukkit.isOwnedByCurrentRegion(bukkitEntity)) {
            runnable.run();
            return;
        }
        bukkitEntity.getScheduler().run(plugin, task -> runnable.run(), null);
    }

    @Override
    public void later(@Nonnull Runnable runnable, int delay) {
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> runnable.run(), Math.max(1L, delay));
    }

    @Override
    public void laterAsync(@Nonnull Runnable runnable, int delay) {
        Bukkit.getAsyncScheduler().runDelayed(
                plugin,
                task -> runnable.run(),
                ticksToMilliseconds(delay),
                TimeUnit.MILLISECONDS
        );
    }

    @Override
    public void cancel(int taskId) {
        ScheduledTask task = repeatingTasks.remove(taskId);
        if (task != null) {
            task.cancel();
        }
    }

    @Override
    public <T> T syncGlobal(@Nonnull Supplier<T> function) {
        if (Bukkit.isGlobalTickThread()) {
            return function.get();
        }
        ensureCanBlock("global region");
        CompletableFuture<T> result = new CompletableFuture<>();
        Bukkit.getGlobalRegionScheduler().run(plugin, task -> complete(result, function));
        return await(result);
    }

    @Override
    public <T> T syncAt(@Nonnull Supplier<T> function, @Nonnull World world, int chunkX, int chunkZ) {
        org.bukkit.World bukkitWorld = BukkitAdapter.adapt(world);
        if (Bukkit.isOwnedByCurrentRegion(bukkitWorld, chunkX, chunkZ)) {
            return function.get();
        }
        ensureCanBlock("region " + world.getName() + "[" + chunkX + "," + chunkZ + "]");
        CompletableFuture<T> result = new CompletableFuture<>();
        Bukkit.getRegionScheduler().run(plugin, bukkitWorld, chunkX, chunkZ, task -> complete(result, function));
        return await(result);
    }

    @Override
    public <T> T syncWith(@Nonnull Supplier<T> function, @Nonnull Entity entity) {
        org.bukkit.entity.Entity bukkitEntity = bukkitEntity(entity);
        if (Bukkit.isOwnedByCurrentRegion(bukkitEntity)) {
            return function.get();
        }
        ensureCanBlock("entity " + bukkitEntity.getUniqueId());
        CompletableFuture<T> result = new CompletableFuture<>();
        boolean scheduled = bukkitEntity.getScheduler().execute(
                plugin,
                () -> complete(result, function),
                () -> result.completeExceptionally(new IllegalStateException("Entity retired before task execution")),
                0L
        );
        if (!scheduled) {
            throw new IllegalStateException("Entity retired before task scheduling");
        }
        return await(result);
    }

    public void cancelAll() {
        repeatingTasks.values().forEach(ScheduledTask::cancel);
        repeatingTasks.clear();
        Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
        Bukkit.getAsyncScheduler().cancelTasks(plugin);
    }

    private org.bukkit.entity.Entity bukkitEntity(Entity entity) {
        if (entity instanceof BukkitPlayer player) {
            return player.getPlayer();
        }
        if (entity instanceof BukkitEntity bukkitEntity) {
            return Objects.requireNonNull(bukkitEntity.getEntity(), "The entity reference is no longer available");
        }
        throw new IllegalArgumentException("Unsupported entity implementation: " + entity.getClass().getName());
    }

    private int remember(ScheduledTask task) {
        int taskId = nextTaskId.getAndIncrement();
        repeatingTasks.put(taskId, task);
        return taskId;
    }

    private void ensureCanBlock(String target) {
        if (FoliaSupport.isTickThread()) {
            throw new IllegalStateException("Cannot block a Folia tick thread while waiting for " + target);
        }
        if (!plugin.isEnabled()) {
            throw new IllegalStateException("Cannot schedule work while FastAsyncWorldEdit is disabled");
        }
    }

    private long ticksToMilliseconds(int ticks) {
        return Math.max(1L, ticks) * 50L;
    }

    private <T> void complete(CompletableFuture<T> result, Supplier<T> function) {
        try {
            result.complete(function.get());
        } catch (Throwable throwable) {
            result.completeExceptionally(throwable);
        }
    }

    private <T> T await(CompletableFuture<T> result) {
        try {
            return result.get(OWNED_TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Folia-owned work", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Folia-owned work failed", cause);
        } catch (TimeoutException exception) {
            throw new IllegalStateException(
                    "Timed out after " + OWNED_TASK_TIMEOUT_SECONDS + " seconds waiting for Folia-owned work",
                    exception
            );
        }
    }
}
