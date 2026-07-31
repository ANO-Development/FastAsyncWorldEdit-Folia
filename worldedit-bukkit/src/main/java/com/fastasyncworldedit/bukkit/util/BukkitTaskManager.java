package com.fastasyncworldedit.bukkit.util;

import com.fastasyncworldedit.core.util.TaskManager;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.world.World;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import javax.annotation.Nonnull;
import java.util.function.Supplier;

public class BukkitTaskManager extends TaskManager {

    private final Plugin plugin;

    public BukkitTaskManager(final Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public int repeat(@Nonnull final Runnable runnable, final int delay, final int interval) {
        return this.plugin.getServer().getScheduler().scheduleSyncRepeatingTask(this.plugin, runnable, delay, interval);
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
        return repeat(runnable, delay, interval);
    }

    @Override
    public int repeatAsync(@Nonnull final Runnable runnable, final int interval) {
        return this.plugin.getServer().getScheduler().scheduleAsyncRepeatingTask(this.plugin, runnable, interval, interval);
    }

    @Override
    public void async(@Nonnull final Runnable runnable) {
        this.plugin.getServer().getScheduler().runTaskAsynchronously(this.plugin, runnable).getTaskId();
    }

    @Override
    public void task(@Nonnull final Runnable runnable) {
        this.plugin.getServer().getScheduler().runTask(this.plugin, runnable).getTaskId();
    }

    @Override
    public void taskAt(@Nonnull Runnable runnable, @Nonnull World world, int chunkX, int chunkZ) {
        task(runnable);
    }

    @Override
    public void taskWith(@Nonnull Runnable runnable, @Nonnull Entity entity) {
        task(runnable);
    }

    @Override
    public void later(@Nonnull final Runnable runnable, final int delay) {
        this.plugin.getServer().getScheduler().runTaskLater(this.plugin, runnable, delay).getTaskId();
    }

    @Override
    public void laterAsync(@Nonnull final Runnable runnable, final int delay) {
        this.plugin.getServer().getScheduler().runTaskLaterAsynchronously(this.plugin, runnable, delay);
    }

    @Override
    public void cancel(final int task) {
        if (task != -1) {
            Bukkit.getScheduler().cancelTask(task);
        }
    }

    @Override
    public <T> T syncAt(@Nonnull Supplier<T> function, @Nonnull World world, int chunkX, int chunkZ) {
        return syncGlobal(function);
    }

    @Override
    public <T> T syncWith(@Nonnull Supplier<T> function, @Nonnull Entity entity) {
        return syncGlobal(function);
    }

}
