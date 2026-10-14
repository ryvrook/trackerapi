package dev.ryvrook.trackerapi.counters;

import com.google.gson.JsonObject;
import com.hypixel.hytale.logger.HytaleLogger;
import dev.ryvrook.trackerapi.api.Counters;
import dev.ryvrook.trackerapi.api.Keys;
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
    private volatile boolean perWorld = true;
    private volatile boolean stopped;

    public CounterStore(Path root, HytaleLogger logger, Executor io, Perf perf) {
        this.root = root;
        this.players = root.resolve("players");
        this.logger = logger;
        this.io = io;
        this.perf = perf;
        this.server = new ServerCounters(root.resolve("server").resolve("totals.json"), logger);
        this.names = new NamesIndex(root.resolve("names.json"), logger);
        this.boards = new Boards(this, logger);
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
        PlayerCounters back = unloading.remove(uuid);
        PlayerCounters pc = loaded.computeIfAbsent(uuid, id -> {
            if (back != null) {
                back.setName(name, now);
                return back;
            }
            PlayerCounters fresh = new PlayerCounters(id, name, now);
            fresh.setMerged(false);
            return fresh;
        });
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

    /** The player left. Any thread. Saves on IO and forgets the object once saved. */
    public void leave(UUID uuid) {
        PlayerCounters pc = loaded.remove(uuid);
        if (pc == null) {
            return;
        }
        unloading.put(uuid, pc);
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

    private void runIo(Runnable task) {
        try {
            io.execute(task);
        } catch (RejectedExecutionException e) {
            if (stopped) {
                task.run(); // shutdown, the executor is gone, finish inline so nothing is lost
            }
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
            pc.add(Keys.world(world, key), amount);
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
                pc.add(Keys.world(world, total), amount);
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
        if (!Keys.isExternal(key) || amount == 0) {
            return false;
        }
        PlayerCounters pc = live(player);
        if (pc == null) {
            return false;
        }
        pc.add(key, amount);
        return true;
    }

    @Override
    public boolean raiseTo(UUID player, String key, long value) {
        if (!Keys.isExternal(key)) {
            return false;
        }
        PlayerCounters pc = live(player);
        if (pc == null) {
            return false;
        }
        pc.raiseTo(key, value);
        return true;
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
