import com.fastasyncworldedit.core.configuration.Settings;
import com.fastasyncworldedit.core.extent.processor.BatchProcessorHolder;
import com.fastasyncworldedit.core.extent.processor.ProcessorScope;
import com.fastasyncworldedit.core.queue.IChunk;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.IChunkSet;
import com.fastasyncworldedit.core.util.TaskManager;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.world.block.BlockTypes;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.File;
import java.io.FileInputStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class IncidentProbe extends JavaPlugin {
    private World world;
    private com.sk89q.worldedit.extension.platform.Actor actor;
    private String auditUser;

    @Override
    public void onEnable() {
        if (getServer().getPort() != 25708 || !getServer().getIp().equals("127.0.0.1")) {
            throw new IllegalStateException("This probe only runs on its disposable loopback fixture");
        }
        Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> {
            world = Bukkit.getWorlds().getFirst();
            Bukkit.getAsyncScheduler().runNow(this, ignored -> {
                try {
                    Settings.settings().QUEUE.PARALLEL_THREADS = 4;
                    Settings.settings().QUEUE.TARGET_SIZE = 16;
                    Settings.settings().QUEUE.PRELOAD_CHUNK_COUNT = 0;
                    if (!Boolean.getBoolean("incident.stats") && !Boolean.getBoolean("incident.auditCompare")) verifyCancellation();
                    verifySchematic();
                    getLogger().info("INCIDENT_PROBE_PASS");
                } catch (Throwable failure) {
                    getLogger().log(java.util.logging.Level.SEVERE, "INCIDENT_PROBE_FAIL", failure);
                } finally {
                    Bukkit.getGlobalRegionScheduler().execute(this, Bukkit::shutdown);
                }
            });
        }, 60);
    }

    private EditSession edit() {
        return WorldEdit.getInstance().newEditSessionBuilder().world(BukkitAdapter.adapt(world))
                .actor(actor).limitUnlimited().allowedRegionsEverywhere()
                .fastMode(false).checkMemory(true).changeSet(true, java.util.UUID.randomUUID()).build();
    }

    private void actor(String suffix) {
        var console = com.sk89q.worldedit.bukkit.WorldEditPlugin.getInstance().wrapCommandSender(Bukkit.getConsoleSender());
        auditUser = "#incident-" + suffix + "-" + Long.toString(System.nanoTime(), 36);
        var uuid = java.util.UUID.randomUUID();
        actor = (com.sk89q.worldedit.extension.platform.Actor) java.lang.reflect.Proxy.newProxyInstance(
                console.getClass().getClassLoader(), new Class<?>[] {com.sk89q.worldedit.extension.platform.Actor.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> auditUser;
                    case "getUniqueId" -> uuid;
                    default -> method.invoke(console, arguments);
                });
    }

    private void awaitAudit(long expected) throws Exception {
        if (Bukkit.getPluginManager().getPlugin("CoreProtect") == null) return;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(3);
        long nextProgress = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        long count = -1;
        while (System.nanoTime() < deadline) {
            try (var connection = net.coreprotect.database.Database.getConnection(true);
                    var query = connection.prepareStatement("SELECT COUNT(*) FROM co_block b JOIN co_user u ON b.user=u.rowid WHERE u.user=?")) {
                query.setString(1, auditUser);
                try (var rows = query.executeQuery()) { rows.next(); count = rows.getLong(1); }
            }
            if (count == expected) {
                getLogger().info("AUDIT_PASS user=" + auditUser + " rows=" + count);
                return;
            }
            if (count > expected) throw new AssertionError("Extra audit rows: " + count + " > " + expected);
            if (System.nanoTime() >= nextProgress) {
                getLogger().info("AUDIT_PROGRESS committed=" + count + " expected=" + expected
                        + " retained=" + net.coreprotect.consumer.recovery.RecoveryJournal.retainedRecords()
                        + " consumers=" + net.coreprotect.consumer.Consumer.getConsumerSize(0) + "/"
                        + net.coreprotect.consumer.Consumer.getConsumerSize(1));
                nextProgress = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Audit did not drain: " + count + " != " + expected + "; "
                + net.coreprotect.consumer.recovery.RecoveryJournal.status());
    }

    private void verifyCancellation() throws Exception {
        actor("cancel");
        int origin = Integer.getInteger("incident.cancelOrigin", -16384);
        var region = new CuboidRegion(BlockVector3.at(origin, 128, origin), BlockVector3.at(origin + 63, 135, origin + 63));
        var accepted = new AtomicLong();
        var chunks = new AtomicInteger();
        EditSession edit = edit();
        edit.addProcessor(new BatchProcessorHolder() {
            @Override
            public IChunkSet processSet(IChunk chunk, IChunkGet get, IChunkSet set) {
                for (int layer = set.getMinSectionPosition(); layer <= set.getMaxSectionPosition(); layer++) {
                    if (!set.hasSection(layer)) continue;
                    for (char block : set.load(layer)) if (block != 0) accepted.incrementAndGet();
                }
                if (chunks.incrementAndGet() == 3) edit.cancel();
                return set;
            }
            @Override
            public ProcessorScope getScope() { return ProcessorScope.READING_BLOCKS; }
        });
        boolean failed = false;
        try {
            edit.setBlocks((com.sk89q.worldedit.regions.Region) region, BlockTypes.DIAMOND_BLOCK.getDefaultState());
            edit.flushQueue();
        } catch (RuntimeException expected) {
            failed = true;
        } finally {
            try { edit.close(); } catch (RuntimeException expected) { failed = true; }
        }
        long placed = count(region, Material.DIAMOND_BLOCK);
        long history = edit.getChangeSet().longSize();
        if (!failed || placed == 0 || placed >= region.getVolume() || placed != history || placed != accepted.get()) {
            throw new AssertionError("Cancellation: failed=" + failed + " placed=" + placed + " history=" + history
                    + " audit=" + accepted.get());
        }
        awaitAudit(placed);
        try (EditSession undo = edit()) { edit.undo(undo); }
        if (count(region, Material.DIAMOND_BLOCK) != 0) throw new AssertionError("Partial undo left blocks");
        awaitAudit(placed * 2);
        getLogger().info("CANCELLATION_PASS placed=" + placed + " history=" + history + " audit=" + accepted.get());
    }

    private long count(CuboidRegion region, Material material) {
        long total = 0;
        for (var chunk : region.getChunks()) {
            total += TaskManager.taskManager().syncAt(() -> {
                long matches = 0;
                for (int x = Math.max(region.getMinimumPoint().x(), chunk.x() << 4);
                        x <= Math.min(region.getMaximumPoint().x(), (chunk.x() << 4) + 15); x++) {
                    for (int z = Math.max(region.getMinimumPoint().z(), chunk.z() << 4);
                            z <= Math.min(region.getMaximumPoint().z(), (chunk.z() << 4) + 15); z++) {
                        for (int y = region.getMinimumPoint().y(); y <= region.getMaximumPoint().y(); y++) {
                            if (world.getBlockAt(x, y, z).getType() == material) matches++;
                        }
                    }
                }
                return matches;
            }, BukkitAdapter.adapt(world), chunk.x(), chunk.z());
        }
        return total;
    }

    private void verifySchematic() throws Exception {
        actor("schematic");
        File file = new File("../500am1.schem");
        Clipboard clipboard;
        try (var stream = new FileInputStream(file); var reader = ClipboardFormats.findByFile(file).getReader(stream)) {
            clipboard = reader.read();
        }
        getLogger().info("SCHEMATIC dimensions=" + clipboard.getDimensions() + " origin=" + clipboard.getOrigin());
        long nonAir = 0;
        var chunkCounts = new java.util.HashMap<String, Integer>();
        int largestState = 0;
        int largestNbtText = 0;
        for (var position : clipboard.getRegion()) {
            var block = clipboard.getFullBlock(position);
            if (!block.getBlockType().getMaterial().isAir()) {
                nonAir++;
                var relative = position.subtract(clipboard.getMinimumPoint());
                chunkCounts.merge((relative.x() >> 4) + "," + (relative.z() >> 4), 1, Integer::sum);
                largestState = Math.max(largestState, block.toImmutableState().getAsString().length());
                if (block.getNbt() != null) largestNbtText = Math.max(largestNbtText, block.getNbt().toString().length());
            }
        }
        getLogger().info("SCHEMATIC_STATS nonAir=" + nonAir + " maximumChunk="
                + chunkCounts.values().stream().mapToInt(Integer::intValue).max().orElse(0)
                + " maximumStateChars=" + largestState + " maximumNbtTextChars=" + largestNbtText);
        if (Boolean.getBoolean("incident.stats")) { clipboard.close(); return; }
        if (Boolean.getBoolean("incident.auditCompare")) {
            compareAudit(clipboard);
            clipboard.close();
            return;
        }
        int origin = Integer.getInteger("incident.origin", 2048);
        BlockVector3 minimum = BlockVector3.at(origin, 96, origin);
        BlockVector3 maximum = minimum.add(clipboard.getDimensions()).subtract(1, 1, 1);
        if (maximum.y() >= world.getMaxHeight()) throw new AssertionError("Schematic exceeds fixture height");
        var target = new CuboidRegion(minimum, maximum);
        BlockVector3 destination = minimum.add(clipboard.getOrigin()).subtract(clipboard.getMinimumPoint());
        try (var holder = new ClipboardHolder(clipboard)) {
            for (int cycle = 0; cycle < Math.max(2, Integer.getInteger("incident.cycles", 3)); cycle++) {
                EditSession paste = edit();
                try (paste) { Operations.complete(holder.createPaste(paste).to(destination).build()); }
                verifyBlocks(clipboard, target, false);
                awaitAudit(nonAir * (cycle * 2L + 1));
                try (EditSession undo = edit()) { paste.undo(undo); }
                verifyBlocks(clipboard, target, true);
                awaitAudit(nonAir * (cycle * 2L + 2));
                getLogger().info("SCHEMATIC_CYCLE_PASS cycle=" + cycle + " history=" + paste.getChangeSet().longSize());
            }
        }
    }

    private void compareAudit(Clipboard clipboard) throws Exception {
        var logged = new java.util.HashSet<BlockVector3>();
        try (var connection = net.coreprotect.database.Database.getConnection(true);
                var query = connection.createStatement();
                var rows = query.executeQuery("SELECT x,y,z FROM co_block WHERE action=1 AND user="
                        + "(SELECT rowid FROM co_user WHERE user LIKE '#incident-schematic-%' ORDER BY rowid DESC LIMIT 1)")) {
            while (rows.next()) logged.add(BlockVector3.at(rows.getInt(1), rows.getInt(2), rows.getInt(3)));
        }
        var missing = new java.util.TreeMap<String, Integer>();
        int origin = Integer.getInteger("incident.origin", 4096);
        var target = BlockVector3.at(origin, 96, origin);
        for (var position : clipboard.getRegion()) {
            var state = clipboard.getBlock(position);
            if (state.getBlockType().getMaterial().isAir()) continue;
            var destination = position.subtract(clipboard.getMinimumPoint()).add(target);
            if (!logged.contains(destination)) missing.merge(state.getAsString(), 1, Integer::sum);
        }
        getLogger().info("AUDIT_COMPARE logged=" + logged.size() + " missing=" + missing);
    }

    private void verifyBlocks(Clipboard clipboard, CuboidRegion target, boolean empty) {
        for (var chunk : target.getChunks()) {
            TaskManager.taskManager().syncAt(() -> {
                for (int x = Math.max(target.getMinimumPoint().x(), chunk.x() << 4);
                        x <= Math.min(target.getMaximumPoint().x(), (chunk.x() << 4) + 15); x++) {
                    for (int z = Math.max(target.getMinimumPoint().z(), chunk.z() << 4);
                            z <= Math.min(target.getMaximumPoint().z(), (chunk.z() << 4) + 15); z++) {
                        for (int y = target.getMinimumPoint().y(); y <= target.getMaximumPoint().y(); y++) {
                            var position = BlockVector3.at(x, y, z);
                            var expected = empty ? BlockTypes.AIR.getDefaultState()
                                    : clipboard.getBlock(position.subtract(target.getMinimumPoint()).add(clipboard.getMinimumPoint()));
                            var actual = BukkitAdapter.adapt(world.getBlockAt(x, y, z).getBlockData());
                            if (!actual.equals(expected)) throw new AssertionError("Mismatch at " + position + ": " + actual + " != " + expected);
                        }
                    }
                }
                return null;
            }, BukkitAdapter.adapt(world), chunk.x(), chunk.z());
        }
    }
}
