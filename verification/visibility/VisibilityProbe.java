import com.fastasyncworldedit.core.configuration.Settings;
import com.fastasyncworldedit.core.extent.processor.BatchProcessorHolder;
import com.fastasyncworldedit.core.queue.IChunk;
import com.fastasyncworldedit.core.queue.IChunkSet;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockTypes;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class VisibilityProbe extends JavaPlugin implements Listener {
    private static final int ORIGIN = Integer.getInteger("visibility.origin", 2048);
    private final CompletableFuture<Void> firstVisible = new CompletableFuture<>();
    private final CompletableFuture<Void> secondVisible = new CompletableFuture<>();
    private boolean started;

    @Override
    public void onEnable() {
        if (getServer().getPort() != 25708 || !getServer().getIp().equals("127.0.0.1")) {
            throw new IllegalStateException("Disposable loopback fixture only");
        }
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getMessenger().registerIncomingPluginChannel(this, "fawe:visibility", (channel, player, bytes) -> {
            if (!player.getName().equals("VisibilityProbe")) {
                return;
            }
            switch (new String(bytes, StandardCharsets.UTF_8)) {
                case "first" -> firstVisible.complete(null);
                case "second" -> secondVisible.complete(null);
                default -> throw new IllegalArgumentException("Unexpected client acknowledgement");
            }
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (started || !event.getPlayer().getName().equals("VisibilityProbe")) {
            return;
        }
        started = true;
        var player = event.getPlayer();
        var world = player.getWorld();
        player.setGameMode(GameMode.SPECTATOR);
        player.getScheduler().run(this, scheduled -> {
            var destination = new Location(world, ORIGIN + 0.5, 150, ORIGIN + 0.5);
            player.teleportAsync(destination).whenComplete((success, failure) -> {
                if (failure != null || !Boolean.TRUE.equals(success)) {
                    getLogger().log(java.util.logging.Level.SEVERE, "VISIBILITY_PROBE_FAIL: teleport", failure);
                    Bukkit.getGlobalRegionScheduler().execute(this, Bukkit::shutdown);
                    return;
                }
                Bukkit.getAsyncScheduler().runDelayed(this, task -> runProbe(world), 3, TimeUnit.SECONDS);
            });
        }, null);
    }

    private void runProbe(World world) {
        try {
            Settings.settings().QUEUE.TARGET_SIZE = 1;
            Settings.settings().QUEUE.PRELOAD_CHUNK_COUNT = 0;
            Settings.settings().LIGHTING.DELAY_PACKET_SENDING = true;
            var actor = com.sk89q.worldedit.bukkit.WorldEditPlugin.getInstance().wrapCommandSender(Bukkit.getConsoleSender());
            try (var edit = WorldEdit.getInstance().newEditSessionBuilder().world(BukkitAdapter.adapt(world))
                    .actor(actor).limitUnlimited().allowedRegionsEverywhere().fastMode(false).checkMemory(true)
                    .changeSet(true, java.util.UUID.randomUUID()).build()) {
                edit.addProcessor(new BatchProcessorHolder() {
                    @Override
                    public AutoCloseable prepareChunk(IChunk chunk, IChunkSet set) throws Exception {
                        if (chunk.getX() == (ORIGIN >> 4) + 1) {
                            firstVisible.get(10, TimeUnit.SECONDS);
                            getLogger().info("VISIBLE_BEFORE_EDIT_COMPLETION");
                        }
                        return () -> {};
                    }
                });
                for (int x = ORIGIN; x < ORIGIN + 32; x++) {
                    for (int y = 128; y < 144; y++) {
                        for (int z = ORIGIN; z < ORIGIN + 16; z++) {
                            edit.setBlock(BlockVector3.at(x, y, z), BlockTypes.DIAMOND_BLOCK.getDefaultState());
                        }
                    }
                }
            }
            secondVisible.get(10, TimeUnit.SECONDS);
            getLogger().info("VISIBILITY_PROBE_PASS");
        } catch (Throwable failure) {
            getLogger().log(java.util.logging.Level.SEVERE, "VISIBILITY_PROBE_FAIL", failure);
        } finally {
            Bukkit.getGlobalRegionScheduler().execute(this, Bukkit::shutdown);
        }
    }
}
