package dev.ryvrook.trackerapi.counters;

import com.google.gson.JsonObject;
import com.hypixel.hytale.logger.HytaleLogger;
import dev.ryvrook.trackerapi.api.Counters;
import dev.ryvrook.trackerapi.api.Keys;
import dev.ryvrook.trackerapi.facts.WorldKeys;
import dev.ryvrook.trackerapi.util.JsonFiles;
import dev.ryvrook.trackerapi.util.Perf;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;

/**
 * The counters of every online player plus the server totals, and their files. Loads on join, saves on
 * leave and every autosave, all file work on the IO executor. Safe from any thread.
 *
 * <p>A player who joins is given an empty object at once, so events count from the first tick, and the
 * file read merges into it when it lands. Until the merge the object is marked unmerged and the API
 * answers only what was counted this session.
 *
 * <p>Leaving keeps the object in {@code unloading} until its save finished, and a player who comes
 * straight back picks up that same object rather than a file still being written.
 */
public final class CounterStore implements Counters {
    private final Path root;
    private final Path players;
    private final HytaleLogger logger;
    private final Executor io;
    private final Perf perf;
    private final Map<UUID, PlayerCounters> loaded = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerCounters> unloading = new ConcurrentHashMap<>();
    private final ServerCounters server;
    private final NamesIndex names;
    private final Boards boards;
    private final WorldKeys worldKeys;
    /** Join and leave move an object between the two maps under this, so neither sees it in neither. */
    private final Object lifecycle = new Object();
    private volatile boolean perWorld = true;
    private volatile boolean stopped;
    /** Writes of other plugins for offline players, waiting for the IO pass. Guarded by {@code offlineLock}. */
    private final Map<UUID, List<OfflineWrite>> offline = new HashMap<>();
    private final Object offlineLock = new Object();
    private boolean offlineQueued;
    /** Past this many offline players waiting for a write, further writes are refused until IO caught up. */
    static final int OFFLINE_MAX_PLAYERS = 10_000;

    public CounterStore(Path root, HytaleLogger logger, Executor io, Perf perf) {
        this.root = root;
        this.players = root.resolve("players");
        this.logger = logger;
        this.io = io;
        this.perf = perf;
        this.server = new ServerCounters(root.resolve("server").resolve("totals.json"), logger);
        this.names = new NamesIndex(root.resolve("names.json"), logger);
        this.boards = new Boards(this, logger);
        this.worldKeys = new WorldKeys(logger);
    }

    /** The world names to counter keys rule, see {@link WorldKeys}. */
    public WorldKeys worldKeys() {
        return worldKeys;
    }

    public Path root() {
        return root;
    }

    public Path playersDir() {
        return players;
    }

    public Path fileOf(UUID uuid) {
        return players.resolve(uuid + ".json");
    }

    public void setPerWorld(boolean on) {
        perWorld = on;
    }

    public ServerCounters serverCounters() {
        return server;
    }

    public NamesIndex names() {
        return names;
    }

    public Boards boards() {
        return boards;
    }

    /** IO thread, at setup. Reads the server totals and the names index. */
    public void loadServer() {
        server.load();
        names.load();
    }

    // ---- lifecycle --------------------------------------------------------------------------------------

    /** The player joined. Any thread. Returns the live object, whose file read is queued. */
    public PlayerCounters join(UUID uuid, String name, long now) {
        PlayerCounters pc;
        synchronized (lifecycle) {
            PlayerCounters back = unloading.remove(uuid);
            pc = loaded.computeIfAbsent(uuid, id -> {
                if (back != null) {
                    back.setName(name, now);
                    return back;
                }
                PlayerCounters fresh = new PlayerCounters(id, name, now);
                fresh.setMerged(false);
                return fresh;
            });
        }
        pc.setName(name, now);
        if (name != null && !name.isEmpty()) {
            names.seen(uuid, name, now);
        }
        if (!pc.isMerged()) {
            runIo(() -> merge(pc));
        }
        return pc;
    }

    /** IO thread. Reads the file and merges it into the live object. */
    private void merge(PlayerCounters live) {
        try {
            PlayerCounters disk = readFile(live.uuid());
            if (disk != null) {
                live.mergeFromDisk(disk);
            } else {
                live.setMerged(true);
            }
        } catch (RuntimeException e) {
            logger.at(Level.WARNING).withCause(e).log("TrackerAPI could not read the counters of %s", live.uuid());
            live.setMerged(true);
        }
    }

    /** IO thread. Reads one file, moving a corrupt one aside. Null when there is none. */
    public PlayerCounters readFile(UUID uuid) {
        Path file = fileOf(uuid);
        try {
            JsonObject o = JsonFiles.read(file);
            return o == null ? null : PlayerCounters.fromJson(uuid, o);
        } catch (IOException | RuntimeException e) {
            logger.at(Level.WARNING).withCause(e).log("TrackerAPI moved the unreadable counters file of %s aside", uuid);
            JsonFiles.quarantine(file);
            return null;
        }
    }

    /**
     * A session begins now. The start goes into the player's file, so a plugin reload carries the session
     * on rather than starting a new one.
     */
    public void startSession(UUID uuid, long now) {
        PlayerCounters pc = live(uuid);
        if (pc != null) {
            pc.setSessionStart(now);
        }
    }

    /**
     * A session that was running before a plugin reload. Once the file read landed, the start kept in the
     * file is handed to {@code resumed}, or {@code now} when the file has none. On IO.
     */
    public void resumeSession(UUID uuid, long now, java.util.function.LongConsumer resumed) {
        PlayerCounters pc = live(uuid);
        if (pc == null) {
            return;
        }
        runIo(() -> {
            long start = pc.sessionStart();
            if (start <= 0 || start > now) {
                start = now;
                pc.setSessionStart(now);
            }
            resumed.accept(start);
        });
    }

    /** The session ended with a real leave, so the file no longer carries its start. */
    public void endSession(UUID uuid) {
        PlayerCounters pc = live(uuid);
        if (pc != null) {
            pc.setSessionStart(0L);
        }
    }

    /** Raises the longest session of an online player to the session running since {@code sessionStart}. */
    public void raiseSession(UUID uuid, long sessionStart, long now) {
        long seconds = sessionStart <= 0 ? 0 : (now - sessionStart) / 1000;
        if (seconds > 0) {
            raise(uuid, Keys.SESSION_LONGEST, seconds);
        }
    }

    /** The player left. Any thread. Saves on IO and forgets the object once saved. */
    public void leave(UUID uuid) {
        PlayerCounters pc;
        synchronized (lifecycle) {
            pc = loaded.get(uuid);
            if (pc == null) {
                return;
            }
            // Into unloading first, so a join racing this leave picks up the same object instead of a fresh one.
            unloading.put(uuid, pc);
            loaded.remove(uuid, pc);
        }
        runIo(() -> {
            try {
                save(pc);
            } finally {
                unloading.remove(uuid, pc);
            }
        });
    }

    /** IO thread. Writes one player when dirty. */
    public void save(PlayerCounters pc) {
        if (!pc.isDirty()) {
            return;
        }
        if (!pc.isMerged()) {
            return; // the file read has not landed, writing now would lose what is on disk
        }
        long start = System.nanoTime();
        try {
            JsonFiles.write(fileOf(pc.uuid()), pc.toJson(), false);
            pc.clearDirty();
        } catch (IOException e) {
            logger.at(Level.WARNING).withCause(e).log("TrackerAPI could not save the counters of %s", pc.uuid());
        } finally {
            perf.record(Perf.R_COUNTERS_SAVE, System.nanoTime() - start);
        }
    }

    /** IO thread. Every dirty online player, the server totals and the names index. */
    public void saveAll() {
        for (PlayerCounters pc : loaded.values()) {
            save(pc);
        }
        for (PlayerCounters pc : unloading.values()) {
            save(pc);
        }
        server.save();
        names.save();
    }

    /** Timer thread. Queues an autosave on IO. */
    public void autosave() {
        runIo(this::saveAll);
    }

    public void stop() {
        stopped = true;
    }

    /**
     * Queues file work on IO. Once the executor is gone the task is dropped with a fine log line, the
     * shutdown queues its own final save before it drains the executor.
     */
    private void runIo(Runnable task) {
        try {
            io.execute(task);
        } catch (RejectedExecutionException e) {
            logger.at(Level.FINE).log("TrackerAPI skipped counter file work, %s", stopped ? "it is stopping" : "the IO thread is gone");
        }
    }

    // ---- writes from the sink ------------------------------------------------------------------------------

    /** The live object of an online player, or null. */
    public PlayerCounters live(UUID uuid) {
        return uuid == null ? null : loaded.get(uuid);
    }

    /** Adds to a player total and its per world copy, and to the server total. */
    public void add(UUID uuid, String key, long amount, String world) {
        PlayerCounters pc = live(uuid);
        if (pc == null || amount <= 0) {
            return;
        }
        pc.add(key, amount);
        if (perWorld && world != null && Keys.PER_WORLD_TOTALS.contains(key)) {
            pc.add(Keys.world(worldKeys.keyOf(world), key), amount);
        }
        server.add(key, amount);
    }

    /** Adds to a per id key and its total. */
    public void addId(UUID uuid, String prefix, String id, String total, long amount, String world) {
        PlayerCounters pc = live(uuid);
        if (pc == null || amount <= 0) {
            return;
        }
        if (total != null) {
            pc.add(total, amount);
            if (perWorld && world != null && Keys.PER_WORLD_TOTALS.contains(total)) {
                pc.add(Keys.world(worldKeys.keyOf(world), total), amount);
            }
            server.add(total, amount);
        }
        if (id != null) {
            pc.add(Keys.of(prefix, id), amount);
            server.add(Keys.of(prefix, id), amount);
        }
    }

    public void raise(UUID uuid, String key, long value) {
        PlayerCounters pc = live(uuid);
        if (pc != null) {
            pc.raiseTo(key, value);
            server.raiseTo(key, value);
        }
    }

    public void lower(UUID uuid, String key, long value) {
        PlayerCounters pc = live(uuid);
        if (pc != null) {
            pc.lowerTo(key, value);
        }
    }

    /** The first time a player sees an id, for zones and the like. True when it was new. */
    public boolean discover(UUID uuid, String prefix, String id, String total) {
        PlayerCounters pc = live(uuid);
        if (pc == null || id == null) {
            return false;
        }
        String key = Keys.of(prefix, id);
        synchronized (pc) {
            if (pc.get(key) > 0) {
                return false;
            }
            pc.set(key, 1L);
            if (total != null) {
                pc.add(total, 1L);
            }
        }
        if (total != null) {
            server.add(total, 1L);
        }
        return true;
    }

    // ---- Counters --------------------------------------------------------------------------------------

    @Override
    public long get(UUID player, String key) {
        PlayerCounters pc = live(player);
        return pc == null || key == null ? 0L : pc.get(key);
    }

    @Override
    public Map<String, Long> all(UUID player) {
        PlayerCounters pc = live(player);
        return pc == null ? Map.of() : pc.snapshot();
    }

    @Override
    public boolean isLoaded(UUID player) {
        PlayerCounters pc = live(player);
        return pc != null && pc.isMerged();
    }

    @Override
    public CompletableFuture<Map<String, Long>> load(UUID player) {
        PlayerCounters pc = live(player);
        if (pc != null && pc.isMerged()) {
            return CompletableFuture.completedFuture(pc.snapshot());
        }
        CompletableFuture<Map<String, Long>> f = new CompletableFuture<>();
        runIo(() -> {
            try {
                PlayerCounters now = live(player);
                if (now != null && now.isMerged()) {
                    f.complete(now.snapshot());
                    return;
                }
                PlayerCounters leaving = unloading.get(player);
                if (leaving != null) {
                    f.complete(leaving.snapshot());
                    return;
                }
                PlayerCounters disk = readFile(player);
                f.complete(disk == null ? Map.of() : disk.snapshot());
            } catch (RuntimeException e) {
                f.completeExceptionally(e);
            }
        });
        return f;
    }

    @Override
    public CompletableFuture<Optional<String>> nameOf(UUID player) {
        PlayerCounters pc = live(player);
        if (pc != null && !pc.name().isEmpty()) {
            return CompletableFuture.completedFuture(Optional.of(pc.name()));
        }
        return CompletableFuture.completedFuture(names.nameOf(player));
    }

    @Override
    public CompletableFuture<Optional<UUID>> uuidOf(String name) {
        return CompletableFuture.completedFuture(names.uuidOf(name));
    }

    @Override
    public Set<UUID> known() {
        return names.known();
    }

    @Override
    public long server(String key) {
        return server.get(key);
    }

    @Override
    public Map<String, Long> serverAll() {
        return server.snapshot();
    }

    @Override
    public boolean add(UUID player, String key, long amount) {
        if (player == null || !Keys.isExternal(key) || amount == 0) {
            return false;
        }
        PlayerCounters pc = live(player);
        if (pc == null) {
            return queueOffline(player, key, amount, false);
        }
        pc.add(key, amount);
        server.add(key, amount);
        return true;
    }

    @Override
    public boolean raiseTo(UUID player, String key, long value) {
        if (player == null || !Keys.isExternal(key)) {
            return false;
        }
        PlayerCounters pc = live(player);
        if (pc == null) {
            return queueOffline(player, key, value, true);
        }
        pc.raiseTo(key, value);
        server.raiseTo(key, value);
        return true;
    }

    // ---- writes of other plugins for players who are offline ----------------------------------------------

    /** One write another plugin made for an offline player, a sum or a raise. */
    private record OfflineWrite(String key, long value, boolean raise) {
    }

    /**
     * Queues a write for a player who is not online. The writes gather here and one task on IO applies all
     * of them, so a siege that credits fifty members costs one pass over fifty files, not fifty tasks.
     */
    private boolean queueOffline(UUID player, String key, long value, boolean raise) {
        if (stopped) {
            return false;
        }
        boolean schedule;
        synchronized (offlineLock) {
            List<OfflineWrite> writes = offline.get(player);
            if (writes == null) {
                if (offline.size() >= OFFLINE_MAX_PLAYERS) {
                    return false;
                }
                writes = new ArrayList<>(2);
                offline.put(player, writes);
            }
            writes.add(new OfflineWrite(key, value, raise));
            schedule = !offlineQueued;
            offlineQueued = true;
        }
        if (schedule) {
            runIo(this::flushOffline);
        }
        return true;
    }

    /**
     * IO thread. Applies the queued writes. A player who came online meanwhile gets them in memory, a player
     * whose leave is still saving gets them on that object before its save runs, which is queued behind this
     * task on the same thread, and anyone else gets them in their file. A join that races this pass reads
     * the file after it, since its read is queued on IO too. A player without a file is skipped.
     */
    void flushOffline() {
        Map<UUID, List<OfflineWrite>> batch;
        synchronized (offlineLock) {
            batch = new HashMap<>(offline);
            offline.clear();
            offlineQueued = false;
        }
        int skipped = 0;
        for (Map.Entry<UUID, List<OfflineWrite>> e : batch.entrySet()) {
            UUID uuid = e.getKey();
            PlayerCounters target = live(uuid);
            if (target == null) {
                target = unloading.get(uuid);
            }
            if (target != null) {
                apply(target, e.getValue());
                continue;
            }
            PlayerCounters disk = readFile(uuid);
            if (disk == null) {
                skipped++;
                continue;
            }
            apply(disk, e.getValue());
            save(disk);
        }
        if (skipped > 0) {
            logger.at(Level.FINE).log("TrackerAPI dropped counters of other plugins for %d offline players it has no file for", skipped);
        }
    }

    private void apply(PlayerCounters pc, List<OfflineWrite> writes) {
        for (OfflineWrite w : writes) {
            if (w.raise()) {
                pc.raiseTo(w.key(), w.value());
                server.raiseTo(w.key(), w.value());
            } else {
                pc.add(w.key(), w.value());
                server.add(w.key(), w.value());
            }
        }
    }

    @Override
    public List<Map.Entry<UUID, Long>> top(String key, int n) {
        return boards.top(key, n);
    }

    @Override
    public Set<String> boardKeys() {
        return boards.keys();
    }

    @Override
    public void watchBoard(String key) {
        boards.watch(key);
    }

    // ---- scans ----------------------------------------------------------------------------------------

    /** Every online player's live object. */
    public List<PlayerCounters> loadedPlayers() {
        return new ArrayList<>(loaded.values());
    }

    public int loadedCount() {
        return loaded.size();
    }

    /** IO thread. Every uuid with a file on disk. */
    public List<UUID> filesOnDisk() {
        List<UUID> out = new ArrayList<>();
        if (!Files.isDirectory(players)) {
            return out;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(players, "*.json")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (name.contains(".corrupt-")) {
                    continue;
                }
                try {
                    out.add(UUID.fromString(name.substring(0, name.length() - 5).toLowerCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                    // not a player file
                }
            }
        } catch (IOException e) {
            logger.at(Level.WARNING).withCause(e).log("TrackerAPI could not list its player files");
        }
        Collections.sort(out);
        return out;
    }

    /**
     * IO thread. The counters of a player wherever they are, the live object for an online player, the
     * file otherwise, as a snapshot. Used by the board builder and the import.
     */
    public Map<String, Long> snapshotAny(UUID uuid) {
        PlayerCounters pc = live(uuid);
        if (pc != null && pc.isMerged()) {
            return pc.snapshot();
        }
        PlayerCounters leaving = unloading.get(uuid);
        if (leaving != null) {
            return leaving.snapshot();
        }
        PlayerCounters disk = readFile(uuid);
        return disk == null ? new HashMap<>() : disk.snapshot();
    }
}
