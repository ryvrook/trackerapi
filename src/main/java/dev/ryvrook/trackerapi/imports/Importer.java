package dev.ryvrook.trackerapi.imports;

import com.google.gson.JsonObject;
import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.counters.CounterRules;
import dev.ryvrook.trackerapi.counters.CounterStore;
import dev.ryvrook.trackerapi.counters.PlayerCounters;
import dev.ryvrook.trackerapi.util.JsonFiles;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * Seeds the counters from the Feats and Chronicle Stats player files, so a server that adds TrackerAPI
 * keeps the numbers its players already have. Runs once on the first boot and again on
 * {@code /trackerapi import}. Every key takes the larger of the TrackerAPI value and the imported one
 * (the smaller for {@code lowest_y}), so running it twice changes nothing and a value TrackerAPI counted
 * itself is never lowered.
 *
 * <p>Everything runs on the {@code TrackerAPI-io} thread. An online player's live counters take the
 * import directly, everyone else's file is read, raised and written back. Each player file records what
 * was imported under {@code imported}, and {@code server/import-state.json} records each run.
 */
public final class Importer {
    private final Services services;

    /** One source of player files. */
    private record Source(String id, String label, Function<Path, Path> folder, BiFunction<UUID, JsonObject, ImportedPlayer> reader) {
    }

    static final Source FEATS = new Source(FeatsImport.SOURCE, "Feats", FeatsImport::folder, FeatsImport::read);
    static final Source CHRONICLE = new Source(ChronicleImport.SOURCE, "Chronicle Stats", ChronicleImport::folder,
            ChronicleImport::read);

    public Importer(Services services) {
        this.services = services;
    }

    /** IO thread. Runs the Feats and Chronicle import once, when no import state file exists yet. */
    public void runFirstBootIfNeeded() {
        if (ImportState.exists(services.dataDir)) {
            return;
        }
        run(FEATS, false);
        run(CHRONICLE, false);
    }

    /** Queues the Feats import on IO. Completes with the one line summary the command replies with. */
    public CompletableFuture<String> runFeats(boolean force) {
        return queue(() -> run(FEATS, force));
    }

    /** Queues the Chronicle Stats import on IO. */
    public CompletableFuture<String> runChronicle(boolean force) {
        return queue(() -> run(CHRONICLE, force));
    }

    /** Queues both, Feats first. The two summaries are joined into one line. */
    public CompletableFuture<String> runAll(boolean force) {
        return queue(() -> run(FEATS, force) + " " + run(CHRONICLE, force));
    }

    private CompletableFuture<String> queue(java.util.function.Supplier<String> task) {
        CompletableFuture<String> f = new CompletableFuture<>();
        try {
            services.io.execute(() -> {
                try {
                    f.complete(task.get());
                } catch (RuntimeException e) {
                    services.logger.at(Level.WARNING).withCause(e).log("TrackerAPI import failed");
                    f.complete("The import failed, see the server log.");
                }
            });
        } catch (RejectedExecutionException e) {
            f.complete("TrackerAPI is stopping, the import did not run.");
        }
        return f;
    }

    /** IO thread. One source, start to end. Returns the summary, which is also logged. */
    String run(Source source, boolean force) {
        ImportState state = ImportState.load(services.dataDir, services.logger);
        if (!force && state.run(source.id()) != null) {
            return source.label() + " import already ran, add --force to read the files again.";
        }
        long now = System.currentTimeMillis();
        Path folder = source.folder().apply(services.dataDir);
        String summary;
        if (folder == null) {
            state.record(source.id(), new ImportState.Run(now, null, 0, 0, 0, 0));
            summary = source.label() + " import found no player files to read.";
        } else {
            int files = 0;
            int failed = 0;
            int players = 0;
            long keys = 0;
            for (Path file : playerFiles(folder)) {
                UUID uuid = ImportedPlayer.uuidOf(file);
                ImportedPlayer p;
                try {
                    p = source.reader().apply(uuid, JsonFiles.read(file));
                } catch (IOException | RuntimeException e) {
                    p = null;
                }
                if (p == null) {
                    failed++;
                    continue;
                }
                files++;
                long written = seed(p, source.id(), now);
                if (written > 0) {
                    players++;
                    keys += written;
                }
            }
            services.counters.saveAll();
            services.counters.boards().rebuild();
            state.record(source.id(), new ImportState.Run(now, folder.toString(), files, failed, players, keys));
            summary = String.format(Locale.ROOT, "%s import read %d player %s%s, seeded %d %s and wrote %d %s.",
                    source.label(), files, files == 1 ? "file" : "files",
                    failed == 0 ? "" : " and skipped " + failed + " it could not read",
                    players, players == 1 ? "player" : "players", keys, keys == 1 ? "counter" : "counters");
        }
        state.save();
        services.logger.at(Level.INFO).log("%s", summary);
        return summary;
    }

    private List<Path> playerFiles(Path folder) {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*.json")) {
            for (Path file : files) {
                if (ImportedPlayer.uuidOf(file) != null) {
                    out.add(file);
                }
            }
        } catch (IOException e) {
            services.logger.at(Level.WARNING).withCause(e).log("TrackerAPI could not list %s", folder);
        }
        Collections.sort(out);
        return out;
    }

    /**
     * Raises one player's counters to the imported values. The live object when the player is online and
     * their file has been merged, the file otherwise (a pending merge then reads the raised file). Returns
     * how many keys changed.
     */
    private long seed(ImportedPlayer p, String source, long now) {
        CounterStore store = services.counters;
        if (!p.name().isEmpty() && store.names().nameOf(p.uuid()).isEmpty()) {
            store.names().seen(p.uuid(), p.name(), now);
        }
        PlayerCounters live = store.live(p.uuid());
        if (live != null && live.isMerged()) {
            return apply(live, p, source, now);
        }
        PlayerCounters pc = store.readFile(p.uuid());
        if (pc == null) {
            pc = new PlayerCounters(p.uuid(), p.name(), now);
        }
        long written = apply(pc, p, source, now);
        store.save(pc);
        return written;
    }

    private static long apply(PlayerCounters pc, ImportedPlayer p, String source, long now) {
        Map<String, Long> current = pc.snapshot();
        long written = 0;
        for (Map.Entry<String, Long> e : p.counters().entrySet()) {
            Long cur = current.get(e.getKey());
            long v = e.getValue();
            if (cur == null || (CounterRules.isMin(e.getKey()) ? v < cur : v > cur)) {
                written++;
            }
        }
        pc.importMax(p.counters());
        if (pc.name().isEmpty() && !p.name().isEmpty()) {
            pc.setName(p.name(), pc.lastSeen());
        }
        JsonObject imported = pc.imported();
        if (imported == null) {
            imported = new JsonObject();
        }
        JsonObject entry = new JsonObject();
        entry.addProperty("at", now);
        entry.addProperty("keys", written);
        imported.add(source, entry);
        pc.setImported(imported);
        return written;
    }
}
