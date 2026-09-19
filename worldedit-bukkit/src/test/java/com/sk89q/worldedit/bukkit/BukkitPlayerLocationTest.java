package com.sk89q.worldedit.bukkit;

import com.fastasyncworldedit.core.configuration.Settings;
import com.fastasyncworldedit.core.util.TaskManager;
import com.sk89q.worldedit.util.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BukkitPlayerLocationTest {

    @Test
    void readsCurrentLocationWithoutWaitingForAnotherRegion() {
        WorldEditPlugin plugin = mock(WorldEditPlugin.class);
        Player player = mock(Player.class);
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(player.getLocation()).thenReturn(new org.bukkit.Location(world, 1024.5, 64, -2048.5, 90, 30));
        TaskManager tasks = mock(TaskManager.class);
        when(tasks.syncWith(any(Supplier.class), any(com.sk89q.worldedit.entity.Player.class)))
                .thenThrow(new IllegalStateException("Cannot block a Folia tick thread"));
        Settings.CLIPBOARD previousClipboard = Settings.settings().CLIPBOARD;
        try (MockedStatic<WorldEditPlugin> plugins = mockStatic(WorldEditPlugin.class);
             MockedStatic<TaskManager> managers = mockStatic(TaskManager.class)) {
            plugins.when(WorldEditPlugin::getInstance).thenReturn(plugin);
            managers.when(TaskManager::taskManager).thenReturn(tasks);
            Settings.settings().CLIPBOARD = new Settings.CLIPBOARD();
            Settings.settings().CLIPBOARD.USE_DISK = false;
            BukkitPlayer actor = new BukkitPlayer(plugin, player);

            Location first = actor.getLocation();
            assertEquals(1024.5, first.x());
            assertEquals(64, first.y());
            assertEquals(-2048.5, first.z());
            assertEquals(90, first.getYaw());
            assertEquals(30, first.getPitch());
            assertEquals("world", ((com.sk89q.worldedit.world.World) first.getExtent()).getName());

            when(player.getLocation()).thenReturn(new org.bukkit.Location(world, -12, 90, 37, -45, -20));
            Location second = actor.getLocation();
            assertEquals(-12, second.x());
            assertEquals(90, second.y());
            assertEquals(37, second.z());
            assertEquals(1024.5, first.x());
            verifyNoInteractions(tasks);
        } finally {
            Settings.settings().CLIPBOARD = previousClipboard;
        }
    }
}
