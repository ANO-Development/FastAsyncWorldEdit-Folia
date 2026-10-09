import com.fastasyncworldedit.core.extent.processor.BatchProcessorHolder;
import com.fastasyncworldedit.core.history.DiskStorageHistory;
import com.fastasyncworldedit.core.queue.ChunkWriteScope;
import com.fastasyncworldedit.core.queue.IChunk;
import com.fastasyncworldedit.core.queue.IChunkSet;
import com.fastasyncworldedit.core.util.TaskManager;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.function.operation.ChangeSetExecutor;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.world.block.BlockTypes;
import net.coreprotect.consumer.recovery.RecoveryAdmission;
import net.coreprotect.consumer.recovery.RecoveryJournal;
import net.coreprotect.consumer.recovery.RecoveryWriter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class PersistenceProbe extends JavaPlugin {
    private static final UUID HISTORY = UUID.fromString("399cc19a-5fcb-4c5d-990b-8c33864b7ab0");
    private final int coordinate = 131072;
    private World world;
    private Actor actor;

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
                    getLogger().info("PERSISTENCE_PASS mode=" + System.getProperty("probe.mode"));
                } catch (Throwable failure) {
                    getLogger().log(java.util.logging.Level.SEVERE, "PERSISTENCE_FAIL", failure);
                } finally {
                    Bukkit.getGlobalRegionScheduler().execute(this, Bukkit::shutdown);
                }
            });
        }, 60);
    }

    private EditSession edit() {
        return WorldEdit.getInstance().newEditSessionBuilder().world(BukkitAdapter.adapt(world)).actor(actor)
                .limitUnlimited().allowedRegionsEverywhere().fastMode(false).checkMemory(true)
                .changeSet(true, UUID.randomUUID()).build();
    }

    private void verify() throws Exception {
        var console = com.sk89q.worldedit.bukkit.WorldEditPlugin.getInstance().wrapCommandSender(Bukkit.getConsoleSender());
        actor = (Actor) Proxy.newProxyInstance(Actor.class.getClassLoader(), new Class<?>[]{Actor.class},
                (proxy, method, args) -> method.getName().equals("getName") ? "#persistence" : method.invoke(console, args));
        String mode = System.getProperty("probe.mode", "slow");
        var history = new DiskStorageHistory(BukkitAdapter.adapt(world), HISTORY, 0);
        if (mode.equals("recover")) {
            if (count(Material.DIAMOND_BLOCK) != 10240) throw new AssertionError("Applied world was not saved");
            String status = RecoveryJournal.status();
            if (!status.contains("10240 uncertain bulk intents")) throw new AssertionError(status);
            audit(0);
            long count = 0;
            var changes = history.backwardIterator();
            while (changes.hasNext()) { changes.next(); count++; }
            if (count != 10240) throw new AssertionError("Restart history contains " + count + " changes");
            try (EditSession undo = edit()) { undo.setBlocks(history, ChangeSetExecutor.Type.UNDO); }
            if (count(Material.AIR) != 10240) throw new AssertionError("Restart undo incomplete");
            audit(10240);
            getLogger().info("RESTART_UNDO_PASS records=10240");
            return;
        }
        if (count(Material.AIR) != 10240) throw new AssertionError("Fixture is not empty");
        var instanceField = RecoveryJournal.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        Object journal = instanceField.get(null);
        var writerField = RecoveryJournal.class.getDeclaredField("writer");
        writerField.setAccessible(true);
        RecoveryWriter writer = (RecoveryWriter) writerField.get(journal);
        EditSession edit = WorldEdit.getInstance().newEditSessionBuilder().world(BukkitAdapter.adapt(world)).actor(actor)
                .limitUnlimited().allowedRegionsEverywhere().fastMode(false).checkMemory(true).changeSet(history).build();
        if (mode.equals("fail-after")) {
            edit.addProcessor(new BatchProcessorHolder() {
                public AutoCloseable prepareChunk(IChunk chunk, IChunkSet set) {
                    return new ChunkWriteScope() {
                        public void beforeWrite() throws Exception {
                            if (RecoveryAdmission.currentBulk() == null) throw new AssertionError("No captured bulk session");
                            try {
                                writer.submit(() -> { throw new IOException("Injected disk failure after durable native intent"); }).get(5, TimeUnit.SECONDS);
                                throw new AssertionError("Writer failure was swallowed");
                            } catch (ExecutionException expected) {
                                if (!(expected.getCause() instanceof IOException)) throw expected;
                            }
                        }
                        public void complete(Outcome outcome, Throwable failure) {}
                        public void close() {}
                    };
                }
            });
        }
        CountDownLatch writerEntered = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        var heartbeat = new AtomicInteger();
        var heartbeatTask = Bukkit.getRegionScheduler().runAtFixedRate(this, world, 0, 0, task -> heartbeat.incrementAndGet(), 1, 1);
        var region = new CuboidRegion(BlockVector3.at(coordinate, 128, coordinate), BlockVector3.at(coordinate + 15, 167, coordinate + 15));
        try (var worker = Executors.newSingleThreadExecutor()) {
            if (!mode.equals("fail-after")) {
                writer.submit(() -> {
                    writerEntered.countDown();
                    if (!releaseWriter.await(10, TimeUnit.SECONDS)) throw new IOException("Probe writer barrier timeout");
                    if (mode.equals("fail-before")) throw new IOException("Injected disk failure before checkpoint");
                    return null;
                });
                if (!writerEntered.await(5, TimeUnit.SECONDS)) throw new AssertionError("Writer not held");
            }
            var completion = worker.submit(() -> {
                try (edit) {
                    edit.setBlocks((com.sk89q.worldedit.regions.Region) region, BlockTypes.DIAMOND_BLOCK.getDefaultState());
                    return false;
                } catch (RuntimeException expected) {
                    getLogger().info("EXPECTED_EDIT_FAILURE " + expected.getClass().getName());
                    return true;
                }
            });
            if (!mode.equals("fail-after")) {
                Thread.sleep(2000);
                if (completion.isDone() || count(Material.AIR) != 10240) throw new AssertionError("Native write passed stalled audit checkpoint");
                if (heartbeat.get() < 20) throw new AssertionError("Unrelated region stopped responding");
                if (writer.pendingRecords() > 8192 || writer.pendingBytes() > 64L * 1024 * 1024) throw new AssertionError("Writer budget exceeded");
                releaseWriter.countDown();
            }
            boolean failed = completion.get(60, TimeUnit.SECONDS);
            long expected = mode.equals("fail-before") ? 0 : 10240;
            if (failed == mode.equals("slow") || count(Material.DIAMOND_BLOCK) != expected || history.longSize() != expected) {
                throw new AssertionError("Incorrect failure/world/history outcome");
            }
            getLogger().info("PERSISTENCE_OUTCOME failed=" + failed + " placed=" + expected + " history=" + history.longSize()
                    + " heartbeatTicks=" + heartbeat.get());
            if (mode.equals("slow")) {
                audit(10240);
                try (EditSession undo = edit()) { edit.undo(undo); }
                if (count(Material.AIR) != 10240) throw new AssertionError("Slow writer undo incomplete");
                audit(20480);
            } else {
                audit(0);
            }
        } finally {
            releaseWriter.countDown();
            heartbeatTask.cancel();
        }
    }

    private void audit(long expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            try (var connection = net.coreprotect.database.Database.getConnection(true);
                    var query = connection.prepareStatement("SELECT COUNT(*) FROM co_block b JOIN co_user u ON b.user=u.rowid WHERE u.user='#persistence'")) {
                try (var rows = query.executeQuery()) {
                    rows.next();
                    long count = rows.getLong(1);
                    if (count == expected) { getLogger().info("PERSISTENCE_AUDIT_PASS rows=" + count); return; }
                    if (count > expected) throw new AssertionError("Incorrect audit rows=" + count);
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Audit did not drain");
    }

    private long count(Material material) {
        return TaskManager.taskManager().syncAt(() -> {
            long count = 0;
            for (int x = coordinate; x < coordinate + 16; x++) for (int z = coordinate; z < coordinate + 16; z++) {
                for (int y = 128; y < 168; y++) if (world.getBlockAt(x, y, z).getType() == material) count++;
            }
            return count;
        }, BukkitAdapter.adapt(world), coordinate >> 4, coordinate >> 4);
    }
}
