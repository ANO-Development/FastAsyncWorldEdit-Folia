import com.fastasyncworldedit.core.extent.processor.BatchProcessorHolder;
import com.fastasyncworldedit.core.queue.ChunkWriteScope;
import com.fastasyncworldedit.core.queue.IChunk;
import com.fastasyncworldedit.core.queue.IChunkSet;
import com.fastasyncworldedit.core.util.TaskManager;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.world.block.BlockTypes;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class ConcurrentWriteProbe extends JavaPlugin {
    private World world;
    private final int coordinate = 110592;

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
                    getLogger().info("CONCURRENT_WRITE_PASS");
                } catch (Throwable failure) {
                    getLogger().log(java.util.logging.Level.SEVERE, "CONCURRENT_WRITE_FAIL", failure);
                } finally {
                    Bukkit.getGlobalRegionScheduler().execute(this, Bukkit::shutdown);
                }
            });
        }, 60);
    }

    private Actor actor(String name) {
        var console = com.sk89q.worldedit.bukkit.WorldEditPlugin.getInstance().wrapCommandSender(Bukkit.getConsoleSender());
        UUID uuid = UUID.randomUUID();
        return (Actor) Proxy.newProxyInstance(Actor.class.getClassLoader(), new Class<?>[]{Actor.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "getUniqueId" -> uuid;
                    default -> method.invoke(console, args);
                });
    }

    private EditSession edit(Actor actor) {
        return WorldEdit.getInstance().newEditSessionBuilder().world(BukkitAdapter.adapt(world)).actor(actor)
                .limitUnlimited().allowedRegionsEverywhere().fastMode(false).checkMemory(true)
                .changeSet(true, UUID.randomUUID()).build();
    }

    private void verify() throws Exception {
        if (count(Material.AIR) != 10240) throw new AssertionError("Fixture is not empty");
        var region = new CuboidRegion(BlockVector3.at(coordinate, 128, coordinate),
                BlockVector3.at(coordinate + 15, 167, coordinate + 15));
        String prefix = "#samechunk-" + Long.toString(System.nanoTime(), 36);
        Actor firstActor = actor(prefix + "-first");
        Actor secondActor = actor(prefix + "-second");
        EditSession first = edit(firstActor);
        EditSession second = edit(secondActor);
        CountDownLatch firstSettling = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondPreparing = new CountDownLatch(1);
        first.addProcessor(new BatchProcessorHolder() {
            @Override
            public AutoCloseable prepareChunk(IChunk chunk, IChunkSet set) {
                return new ChunkWriteScope() {
                    public void beforeWrite() {}
                    public void close() {}
                    public void complete(Outcome outcome, Throwable failure) throws Exception {
                        if (outcome != Outcome.APPLIED) throw new AssertionError("First write did not apply");
                        firstSettling.countDown();
                        if (!releaseFirst.await(10, TimeUnit.SECONDS)) throw new AssertionError("Settlement barrier timed out");
                    }
                };
            }
        });
        second.addProcessor(new BatchProcessorHolder() {
            @Override
            public AutoCloseable prepareChunk(IChunk chunk, IChunkSet set) {
                secondPreparing.countDown();
                return () -> {};
            }
        });
        try (var workers = Executors.newFixedThreadPool(2)) {
            var firstDone = workers.submit(() -> {
                try (first) {
                    first.setBlocks((com.sk89q.worldedit.regions.Region) region, BlockTypes.DIAMOND_BLOCK.getDefaultState());
                }
                return null;
            });
            if (!firstSettling.await(30, TimeUnit.SECONDS)) throw new AssertionError("First write never reached settlement");
            var secondDone = workers.submit(() -> {
                try (second) {
                    second.setBlocks((com.sk89q.worldedit.regions.Region) region, BlockTypes.GOLD_BLOCK.getDefaultState());
                }
                return null;
            });
            try {
                if (secondPreparing.await(500, TimeUnit.MILLISECONDS)) throw new AssertionError("Second capture overtook first settlement");
                if (count(Material.DIAMOND_BLOCK) != 10240) throw new AssertionError("First world result changed before settlement");
            } finally {
                releaseFirst.countDown();
            }
            firstDone.get(60, TimeUnit.SECONDS);
            secondDone.get(60, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
        }
        if (count(Material.GOLD_BLOCK) != 10240 || first.getChangeSet().longSize() != 10240 || second.getChangeSet().longSize() != 10240) {
            throw new AssertionError("Same-chunk world/history mismatch");
        }
        audit(firstActor.getName(), 10240);
        audit(secondActor.getName(), 20480);
        try (EditSession undo = edit(secondActor)) { second.undo(undo); }
        if (count(Material.DIAMOND_BLOCK) != 10240) throw new AssertionError("Second undo did not restore first write");
        try (EditSession undo = edit(firstActor)) { first.undo(undo); }
        if (count(Material.AIR) != 10240) throw new AssertionError("First undo did not restore empty world");
        audit(firstActor.getName(), 20480);
        audit(secondActor.getName(), 40960);
    }

    private void audit(String user, long expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        long count = -1;
        while (System.nanoTime() < deadline) {
            try (var connection = net.coreprotect.database.Database.getConnection(true);
                    var query = connection.prepareStatement("SELECT COUNT(*) FROM co_block b JOIN co_user u ON b.user=u.rowid WHERE u.user=?")) {
                query.setString(1, user);
                try (var rows = query.executeQuery()) { rows.next(); count = rows.getLong(1); }
            }
            if (count == expected) {
                getLogger().info("CONCURRENT_AUDIT_PASS user=" + user + " rows=" + count);
                return;
            }
            if (count > expected) throw new AssertionError("Duplicate audit rows");
            Thread.sleep(50);
        }
        throw new AssertionError("Audit count " + count + " != " + expected);
    }

    private long count(Material material) {
        return TaskManager.taskManager().syncAt(() -> {
            long count = 0;
            for (int x = coordinate; x < coordinate + 16; x++) {
                for (int z = coordinate; z < coordinate + 16; z++) {
                    for (int y = 128; y < 168; y++) {
                        if (world.getBlockAt(x, y, z).getType() == material) count++;
                    }
                }
            }
            return count;
        }, BukkitAdapter.adapt(world), coordinate >> 4, coordinate >> 4);
    }
}
