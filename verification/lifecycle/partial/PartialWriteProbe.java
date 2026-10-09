import com.fastasyncworldedit.core.extent.processor.BatchProcessorHolder;
import com.fastasyncworldedit.core.extent.processor.ProcessorScope;
import com.fastasyncworldedit.core.queue.IChunk;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.IChunkSet;
import com.fastasyncworldedit.core.util.TaskManager;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.world.block.BlockTypes;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PartialWriteProbe extends JavaPlugin {
    private World world;
    private final int coordinate = 90112;
    private com.sk89q.worldedit.extension.platform.Actor actor;

    @Override
    public void onEnable() {
        if (getServer().getPort() != 25708 || !getServer().getIp().equals("127.0.0.1")) {
            throw new IllegalStateException("Disposable loopback fixture required");
        }
        Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> {
            world = Bukkit.getWorlds().getFirst();
            Bukkit.getAsyncScheduler().runNow(this, ignored -> {
                try {
                    verify();
                    getLogger().info("PARTIAL_WRITE_PASS");
                } catch (Throwable failure) {
                    getLogger().log(java.util.logging.Level.SEVERE, "PARTIAL_WRITE_FAIL", failure);
                } finally {
                    Bukkit.getGlobalRegionScheduler().execute(this, Bukkit::shutdown);
                }
            });
        }, 60);
    }

    private EditSession edit() {
        return WorldEdit.getInstance().newEditSessionBuilder().world(BukkitAdapter.adapt(world))
                .actor(actor).limitUnlimited().allowedRegionsEverywhere().fastMode(false).checkMemory(true)
                .changeSet(true, UUID.randomUUID()).build();
    }

    private void verify() throws Exception {
        var console = com.sk89q.worldedit.bukkit.WorldEditPlugin.getInstance().wrapCommandSender(Bukkit.getConsoleSender());
        actor = (com.sk89q.worldedit.extension.platform.Actor) Proxy.newProxyInstance(
                com.sk89q.worldedit.extension.platform.Actor.class.getClassLoader(),
                new Class<?>[]{com.sk89q.worldedit.extension.platform.Actor.class},
                (proxy, method, args) -> method.getName().equals("getName") ? "#partial-write" : method.invoke(console, args));
        if (Boolean.getBoolean("probe.recovery")) {
            if (count(Material.DIAMOND_BLOCK) != 0) throw new AssertionError("Undo world was not persisted");
            verifyUncertain();
            audit(Boolean.getBoolean("probe.containers") ? 4099 : 4096);
            getLogger().info("UNCERTAIN_RESTART_PASS");
            return;
        }
        var region = new CuboidRegion(BlockVector3.at(coordinate, 128, coordinate),
                BlockVector3.at(coordinate + 15, 159, coordinate + 15));
        long initial = count(Material.DIAMOND_BLOCK);
        if (initial != 0) throw new AssertionError("Fixture is not empty");
        boolean containers = Boolean.getBoolean("probe.containers");
        if (containers) {
            TaskManager.taskManager().syncAt(() -> {
                for (int y : new int[]{128, 144}) {
                    var block = world.getBlockAt(coordinate, y, coordinate);
                    block.setType(Material.CHEST, false);
                    var chest = (org.bukkit.block.Chest) block.getState();
                    chest.getBlockInventory().setItem(0, new org.bukkit.inventory.ItemStack(Material.DIAMOND, 16));
                }
                return null;
            }, BukkitAdapter.adapt(world), coordinate >> 4, coordinate >> 4);
        }
        AtomicBoolean failedNative = new AtomicBoolean();
        EditSession edit = edit();
        edit.addProcessor(new BatchProcessorHolder() {
            @Override
            public IChunkSet processSet(IChunk chunk, IChunkGet get, IChunkSet set) {
                return (IChunkSet) Proxy.newProxyInstance(IChunkSet.class.getClassLoader(), new Class<?>[]{IChunkSet.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("load") && (int) args[0] == 9
                                    && Bukkit.isOwnedByCurrentRegion(world, chunk.getX(), chunk.getZ())
                                    && failedNative.compareAndSet(false, true)) {
                                throw new IllegalStateException("Injected failure before the second section write");
                            }
                            try {
                                return method.invoke(set, args);
                            } catch (InvocationTargetException exception) {
                                throw exception.getCause();
                            }
                        });
            }

            @Override
            public ProcessorScope getScope() {
                return ProcessorScope.READING_BLOCKS;
            }
        });
        boolean reportedFailure = false;
        try {
            edit.setBlocks((com.sk89q.worldedit.regions.Region) region, BlockTypes.DIAMOND_BLOCK.getDefaultState());
            edit.flushQueue();
        } catch (RuntimeException expected) {
            reportedFailure = true;
        } finally {
            try { edit.close(); } catch (RuntimeException expected) { reportedFailure = true; }
        }
        long placed = count(Material.DIAMOND_BLOCK);
        long history = edit.getChangeSet().longSize();
        getLogger().info("PARTIAL_OBSERVED failed=" + reportedFailure + " native=" + failedNative.get()
                + " placed=" + placed + " history=" + history);
        if (!reportedFailure || !failedNative.get() || placed != 4096 || history != (containers ? 4099 : 4096)) {
            throw new AssertionError("Partial history mismatch");
        }
        if (Bukkit.getPluginManager().getPlugin("CoreProtect") != null) {
            verifyUncertain();
            audit(0);
        }
        try (EditSession undo = edit()) { edit.undo(undo); }
        if (count(Material.DIAMOND_BLOCK) != 0) throw new AssertionError("Partial undo left blocks");
        if (containers) {
            TaskManager.taskManager().syncAt(() -> {
                for (int y : new int[]{128, 144}) {
                    var block = world.getBlockAt(coordinate, y, coordinate);
                    if (!(block.getState() instanceof org.bukkit.block.Chest chest)) throw new AssertionError("Chest missing");
                    var item = chest.getBlockInventory().getItem(0);
                    if (item == null || item.getType() != Material.DIAMOND || item.getAmount() != 16) {
                        throw new AssertionError("Chest contents were not restored at y=" + y);
                    }
                }
                return null;
            }, BukkitAdapter.adapt(world), coordinate >> 4, coordinate >> 4);
        }
        if (Bukkit.getPluginManager().getPlugin("CoreProtect") != null) audit(containers ? 4099 : 4096);
    }

    private void verifyUncertain() throws Exception {
        String status = net.coreprotect.consumer.recovery.RecoveryJournal.status();
        getLogger().info("PARTIAL_RECOVERY_STATUS " + status);
        if (!java.util.regex.Pattern.compile("[1-9][0-9]* uncertain bulk intents").matcher(status).find()) {
            throw new AssertionError("Uncertain native intent was not retained");
        }
    }

    private void audit(long expected) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            try (var connection = net.coreprotect.database.Database.getConnection(true);
                    var query = connection.prepareStatement("SELECT COUNT(*) FROM co_block b JOIN co_user u ON b.user=u.rowid WHERE u.user='#partial-write'")) {
                try (var rows = query.executeQuery()) {
                    rows.next();
                    long count = rows.getLong(1);
                    if (count == expected) {
                        getLogger().info("PARTIAL_AUDIT_PASS rows=" + count);
                        return;
                    }
                    if (count > expected) throw new AssertionError("Unapplied audit rows were published: " + count);
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Partial audit did not drain");
    }

    private long count(Material material) {
        return TaskManager.taskManager().syncAt(() -> {
            long count = 0;
            for (int x = coordinate; x < coordinate + 16; x++) {
                for (int z = coordinate; z < coordinate + 16; z++) {
                    for (int y = 128; y < 160; y++) {
                        if (world.getBlockAt(x, y, z).getType() == material) count++;
                    }
                }
            }
            return count;
        }, BukkitAdapter.adapt(world), coordinate >> 4, coordinate >> 4);
    }
}
