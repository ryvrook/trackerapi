package dev.ryvrook.trackerapi.imports;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hypixel.hytale.logger.HytaleLogger;
import dev.ryvrook.trackerapi.util.JsonFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;

/**
 * {@code server/import-state.json}, what each import read and when. Its presence is what makes the first
 * boot import run only once. IO thread only.
 *
 * <pre>{"Version": 1, "sources": {"feats": {"at": 1791915083101, "folder": "...", "files": 3, "failed": 0,
 *   "players": 3, "keys": 42}}}</pre>
 */
public final class ImportState {
    public static final int VERSION = 1;

    /** What one run of one source did. {@code folder} is null when the folder was not there. */
    public record Run(long at, String folder, int files, int failed, int players, long keys) {
    }

    private final Path file;
    private final HytaleLogger logger;
    private final JsonObject sources = new JsonObject();

    private ImportState(Path file, HytaleLogger logger) {
        this.file = file;
        this.logger = logger;
    }

    public static Path fileIn(Path dataDir) {
        return dataDir.resolve("server").resolve("import-state.json");
    }

    public static boolean exists(Path dataDir) {
        return Files.exists(fileIn(dataDir));
    }

    /** Reads the file. A missing or unreadable file reads as empty. */
    public static ImportState load(Path dataDir, HytaleLogger logger) {
        ImportState s = new ImportState(fileIn(dataDir), logger);
        try {
            JsonObject o = JsonFiles.read(s.file);
            JsonElement src = o == null ? null : o.get("sources");
            if (src != null && src.isJsonObject()) {
                for (var e : src.getAsJsonObject().entrySet()) {
                    s.sources.add(e.getKey(), e.getValue());
                }
            }
        } catch (IOException | RuntimeException e) {
            logger.at(Level.WARNING).withCause(e).log("TrackerAPI could not read %s and treats it as empty", s.file.getFileName());
        }
        return s;
    }

    /** The last run of a source, or null when it never ran. */
    public Run run(String source) {
        JsonElement e = sources.get(source);
        if (e == null || !e.isJsonObject()) {
            return null;
        }
        JsonObject o = e.getAsJsonObject();
        return new Run(num(o, "at"), o.has("folder") && o.get("folder").isJsonPrimitive() ? o.get("folder").getAsString() : null,
                (int) num(o, "files"), (int) num(o, "failed"), (int) num(o, "players"), num(o, "keys"));
    }

    public void record(String source, Run run) {
        JsonObject o = new JsonObject();
        o.addProperty("at", run.at());
        if (run.folder() != null) {
            o.addProperty("folder", run.folder());
        }
        o.addProperty("files", run.files());
        o.addProperty("failed", run.failed());
        o.addProperty("players", run.players());
        o.addProperty("keys", run.keys());
        sources.add(source, o);
    }

    public void save() {
        JsonObject o = new JsonObject();
        o.addProperty("Version", VERSION);
        o.add("sources", sources.deepCopy());
        try {
            JsonFiles.write(file, o, true);
        } catch (IOException e) {
            logger.at(Level.WARNING).withCause(e).log("TrackerAPI could not write %s", file.getFileName());
        }
    }

    private static long num(JsonObject o, String key) {
        Long v = ImportedPlayer.number(o.get(key));
        return v == null ? 0L : v;
    }
}
