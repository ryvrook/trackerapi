package dev.ryvrook.trackerapi.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Small JSON file helpers. Every write goes through a temp file and a move, so a reader never sees a torn file. */
public final class JsonFiles {
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    public static final Gson PRETTY = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private JsonFiles() {
    }

    /** Reads a JSON object, or null when the file is missing. Throws on a file that does not parse. */
    public static JsonObject read(Path file) throws IOException {
        if (!Files.exists(file)) {
            return null;
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (text.isBlank()) {
            return null;
        }
        JsonElement e = JsonParser.parseString(text);
        return e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    public static void write(Path file, JsonElement json, boolean pretty) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, (pretty ? PRETTY : GSON).toJson(json), StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Moves a file that does not parse aside as {@code <name>.corrupt-<millis>.json}, keeping one copy. */
    public static void quarantine(Path file) {
        try {
            String name = file.getFileName().toString();
            String base = name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
            Path aside = file.resolveSibling(base + ".corrupt-" + System.currentTimeMillis() + ".json");
            Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // leave it, the next write replaces it
        }
    }
}
