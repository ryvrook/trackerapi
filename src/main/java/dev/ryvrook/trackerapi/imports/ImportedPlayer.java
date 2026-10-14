package dev.ryvrook.trackerapi.imports;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.ryvrook.trackerapi.api.Keys;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One player read from another mod's file, already translated to TrackerAPI keys. {@code name} is empty
 * when the file had none.
 */
record ImportedPlayer(UUID uuid, String name, Map<String, Long> counters) {

    /** The uuid a player file is named after, or null for anything else in the folder. */
    static UUID uuidOf(Path file) {
        String name = file.getFileName().toString();
        if (!name.endsWith(".json") || name.contains(".corrupt-")) {
            return null;
        }
        try {
            return UUID.fromString(name.substring(0, name.length() - 5).toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * A whole number from a JSON value, also in the extended form {@code {"$numberLong": "12"}} that BSON
     * writers use. Fractions are rounded. Null for anything else.
     */
    static Long number(JsonElement e) {
        if (e == null) {
            return null;
        }
        try {
            if (e.isJsonPrimitive()) {
                JsonPrimitive p = e.getAsJsonPrimitive();
                if (!p.isNumber()) {
                    return null;
                }
                String text = p.getAsString();
                if (text.indexOf('.') < 0 && text.indexOf('e') < 0 && text.indexOf('E') < 0) {
                    return Long.parseLong(text);
                }
                return Math.round(p.getAsDouble());
            }
            if (e.isJsonObject()) {
                JsonObject o = e.getAsJsonObject();
                for (String k : new String[]{"$numberLong", "$numberInt", "$numberDouble"}) {
                    JsonElement v = o.get(k);
                    if (v != null && v.isJsonPrimitive()) {
                        return Math.round(Double.parseDouble(v.getAsString()));
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // not a number
        }
        return null;
    }

    static String string(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    static JsonObject object(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    /**
     * Puts one translated value. Zero and negative values are dropped, except for the height records that
     * can be below zero. Two source keys landing on one target key are added together when the target total, or
     * the total of a per world key, is in {@code summed}, otherwise the larger is kept (the smaller for {@code lowest_y}).
     */
    static void put(Map<String, Long> out, String key, long value, Set<String> summed) {
        boolean signed = key.equals(Keys.HIGHEST_Y) || key.equals(Keys.LOWEST_Y);
        if (value <= 0 && !signed) {
            return;
        }
        Long prev = out.get(key);
        if (prev == null) {
            out.put(key, value);
        } else if (summed.contains(key.substring(key.lastIndexOf(':') + 1))) {
            out.put(key, prev + value);
        } else if (key.equals(Keys.LOWEST_Y)) {
            out.put(key, Math.min(prev, value));
        } else {
            out.put(key, Math.max(prev, value));
        }
    }

    /** True for an id with no upper case letter, which is how Feats and Chronicle name group keys such as {@code use:chest}. */
    static boolean isGroupId(String id) {
        for (int i = 0; i < id.length(); i++) {
            if (Character.isUpperCase(id.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
