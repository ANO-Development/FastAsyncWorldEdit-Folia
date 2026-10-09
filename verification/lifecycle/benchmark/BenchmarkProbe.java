import com.fastasyncworldedit.core.Fawe;
import com.fastasyncworldedit.core.configuration.Settings;
import com.fastasyncworldedit.core.extent.processor.BatchProcessorHolder;
import com.fastasyncworldedit.core.queue.IChunk;
import com.fastasyncworldedit.core.queue.IChunkSet;
import com.fastasyncworldedit.core.util.TaskManager;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.world.block.BlockTypes;
import jdk.jfr.Event;
import jdk.jfr.Label;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileInputStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class BenchmarkProbe extends JavaPlugin implements Listener {
    private static final BlockVector3 MINIMUM = BlockVector3.at(70001, 96, -70003);
    private volatile String phase = "idle";
    private volatile Player observer;
    private World world;
    private Actor actor;
    private String auditUser;
    private final AtomicLong heartbeatMaximum = new AtomicLong();
    private final AtomicLong peakHeap = new AtomicLong();
    private boolean started;

    @Label("FAWE benchmark phase")
    public static final class PhaseEvent extends Event {
        public String phase;
    }

    @Override
    public void onEnable() {
        if (getServer().getPort() != 25708 || !getServer().getIp().equals("127.0.0.1")) {
            throw new IllegalStateException("Disposable loopback fixture only");
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(this, "fawe:benchmark");
        Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> {
            if (!started) {
                getLogger().severe("BENCHMARK_FAIL: no test client joined within 60 seconds");
                Bukkit.shutdown();
            }
        }, 1200);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (started || !event.getPlayer().getName().equals("VisibilityProbe")) return;
        started = true;
        observer = event.getPlayer();
        world = observer.getWorld();
        observer.setGameMode(GameMode.SPECTATOR);
        observer.getScheduler().run(this, task -> observer.teleportAsync(
                new Location(world, MINIMUM.x() + 120.5, 170, MINIMUM.z() + 107.5)).whenComplete((success, failure) -> {
                    if (failure != null || !Boolean.TRUE.equals(success)) {
                        getLogger().log(java.util.logging.Level.SEVERE, "BENCHMARK_FAIL: teleport", failure);
                        Bukkit.getGlobalRegionScheduler().execute(this, Bukkit::shutdown);
                        return;
                    }
                    Bukkit.getAsyncScheduler().runDelayed(this, ignored -> runBenchmark(), 5, TimeUnit.SECONDS);
                }), null);
    }

    private void runBenchmark() {
        try {
            startHeartbeat();
            Bukkit.getAsyncScheduler().runAtFixedRate(this, task -> {
                long used = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
                peakHeap.accumulateAndGet(used, Math::max);
                getLogger().info("METRIC phase=" + phase + " heapMiB=" + used / 1048576
                        + " active=" + Fawe.instance().getQueueHandler().getBlockingExecutor().getActiveCount()
                        + " queued=" + Fawe.instance().getQueueHandler().getBlockingExecutor().getQueue().size()
                        + " heartbeatMaxMs=" + heartbeatMaximum.get() / 1000000);
            }, 1, 5, TimeUnit.SECONDS);
            File file = new File("../BikiniBottom.schem");
            try (var stream = new FileInputStream(file);
                    var reader = ClipboardFormats.findByFile(file).getReader(stream);
                    Clipboard clipboard = reader.read()) {
                long nonAir = 0;
                for (var point : clipboard.getRegion()) {
                    if (!clipboard.getBlock(point).getBlockType().getMaterial().isAir()) nonAir++;
                }
                getLogger().info("BENCHMARK_INPUT dimensions=" + clipboard.getDimensions() + " nonAir=" + nonAir
                        + " workers=" + Settings.settings().QUEUE.PARALLEL_THREADS
                        + " target=" + Settings.settings().QUEUE.TARGET_SIZE
                        + " cp=" + (Bukkit.getPluginManager().getPlugin("CoreProtect") != null));
                var target = new CuboidRegion(MINIMUM, MINIMUM.add(clipboard.getDimensions()).subtract(1, 1, 1));
                verify(clipboard, target, true);
                for (int cycle = 0; cycle < Math.max(2, Integer.getInteger("benchmark.cycles", 4)); cycle++) {
                    boolean pasteAir = cycle % 2 == 0;
                    actor(cycle);
                    String name = (pasteAir ? "normal" : "skip-air") + "-" + cycle;
                    EditSession paste = edit();
                    var chunks = ConcurrentHashMap.<Long>newKeySet();
                    paste.addProcessor(new BatchProcessorHolder() {
                        @Override
                        public AutoCloseable prepareChunk(IChunk chunk, IChunkSet set) {
                            long key = ((long) chunk.getX() << 32) | (chunk.getZ() & 0xffffffffL);
                            if (!chunks.add(key)) throw new AssertionError("Repeated destination chunk");
                            return () -> {};
                        }
                    });
                    long startedAt = startPhase("paste-" + name);
                    var event = new PhaseEvent();
                    event.phase = phase;
                    event.begin();
                    try (paste) {
                        clipboard.paste(paste, MINIMUM.add(clipboard.getOrigin()).subtract(clipboard.getMinimumPoint()),
                                pasteAir, true, false);
                    }
                    event.end();
                    event.commit();
                    finishPhase(startedAt, " chunks=" + chunks.size() + " history=" + paste.getChangeSet().longSize());
                    verify(clipboard, target, false);
                    awaitAudit(nonAir);
                    startedAt = startPhase("undo-" + name);
                    event = new PhaseEvent();
                    event.phase = phase;
                    event.begin();
                    try (EditSession undo = edit()) { paste.undo(undo); }
                    event.end();
                    event.commit();
                    finishPhase(startedAt, "");
                    verify(clipboard, target, true);
                    awaitAudit(nonAir * 2);
                    getLogger().info("CYCLE_PASS " + name);
                }
            }
            getLogger().info("BENCHMARK_PASS peakHeapMiB=" + peakHeap.get() / 1048576);
        } catch (Throwable failure) {
            getLogger().log(java.util.logging.Level.SEVERE, "BENCHMARK_FAIL", failure);
        } finally {
            Bukkit.getGlobalRegionScheduler().execute(this, Bukkit::shutdown);
        }
    }

    private void startHeartbeat() {
        TaskManager.taskManager().syncAt(() -> {
            world.addPluginChunkTicket(0, 0, this);
            var previous = new AtomicLong(System.nanoTime());
            Bukkit.getRegionScheduler().runAtFixedRate(this, world, 0, 0, task -> {
                long now = System.nanoTime();
                long elapsed = now - previous.getAndSet(now);
                if (phase.startsWith("paste-") || phase.startsWith("undo-")) heartbeatMaximum.accumulateAndGet(elapsed, Math::max);
            }, 1, 1);
            return null;
        }, BukkitAdapter.adapt(world), 0, 0);
    }

    private long startPhase(String name) {
        heartbeatMaximum.set(0);
        phase = name;
        notifyClient("start " + name);
        return System.nanoTime();
    }

    private void finishPhase(long startedAt, String details) {
        getLogger().info("TIMING phase=" + phase + " ms=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                + " heartbeatMaxMs=" + heartbeatMaximum.get() / 1000000 + details);
        notifyClient("end " + phase);
        phase = "verify";
    }

    private void notifyClient(String message) {
        Player player = observer;
        player.getScheduler().run(this, task -> player.sendPluginMessage(this, "fawe:benchmark",
                message.getBytes(StandardCharsets.UTF_8)), null);
    }

    private void actor(int cycle) {
        var console = com.sk89q.worldedit.bukkit.WorldEditPlugin.getInstance().wrapCommandSender(Bukkit.getConsoleSender());
        auditUser = "#benchmark-" + cycle + "-" + Long.toString(System.nanoTime(), 36);
        UUID uuid = UUID.randomUUID();
        actor = (Actor) Proxy.newProxyInstance(console.getClass().getClassLoader(), new Class<?>[] {Actor.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> auditUser;
                    case "getUniqueId" -> uuid;
                    default -> method.invoke(console, arguments);
                });
    }

    private EditSession edit() {
        return WorldEdit.getInstance().newEditSessionBuilder().world(BukkitAdapter.adapt(world)).actor(actor)
                .limitUnlimited().allowedRegionsEverywhere().fastMode(false).checkMemory(true)
                .changeSet(true, UUID.randomUUID()).build();
    }

    private void awaitAudit(long expected) throws Exception {
        if (Bukkit.getPluginManager().getPlugin("CoreProtect") == null) return;
        long start = System.nanoTime();
        long deadline = start + TimeUnit.MINUTES.toNanos(3);
        while (System.nanoTime() < deadline) {
            long count;
            try (var connection = net.coreprotect.database.Database.getConnection(true);
                    var query = connection.prepareStatement("SELECT COUNT(*) FROM co_block b JOIN co_user u ON b.user=u.rowid WHERE u.user=?")) {
                query.setString(1, auditUser);
                try (var rows = query.executeQuery()) { rows.next(); count = rows.getLong(1); }
            }
            if (count > expected) throw new AssertionError("Extra audit rows " + count);
            if (count == expected) {
                getLogger().info("AUDIT_PASS rows=" + count + " drainMs=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Audit did not drain");
    }

    private void verify(Clipboard clipboard, CuboidRegion target, boolean empty) {
        for (var chunk : target.getChunks()) {
            TaskManager.taskManager().syncAt(() -> {
                for (int x = Math.max(target.getMinimumPoint().x(), chunk.x() << 4);
                        x <= Math.min(target.getMaximumPoint().x(), (chunk.x() << 4) + 15); x++) {
                    for (int z = Math.max(target.getMinimumPoint().z(), chunk.z() << 4);
                            z <= Math.min(target.getMaximumPoint().z(), (chunk.z() << 4) + 15); z++) {
                        for (int y = target.getMinimumPoint().y(); y <= target.getMaximumPoint().y(); y++) {
                            var point = BlockVector3.at(x, y, z);
                            var expected = empty ? BlockTypes.AIR.getDefaultState()
                                    : clipboard.getBlock(point.subtract(MINIMUM).add(clipboard.getMinimumPoint()));
                            var actual = BukkitAdapter.adapt(world.getBlockAt(x, y, z).getBlockData());
                            if (!actual.equals(expected)) throw new AssertionError("Mismatch " + point + ": " + actual + " != " + expected);
                        }
                    }
                }
                return null;
            }, BukkitAdapter.adapt(world), chunk.x(), chunk.z());
        }
    }
}
