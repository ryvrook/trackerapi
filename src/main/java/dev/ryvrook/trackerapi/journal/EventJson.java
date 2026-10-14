package dev.ryvrook.trackerapi.journal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns an event record into the JSON object the journal writes. The header comes first and flat,
 * {@code kind}, {@code at}, {@code seq}, {@code world} and {@code player}, with JSON null for a missing world
 * or player so every line starts the same way. Then every other record component by name, in declaration
 * order. Nested records such as {@code Actor} become objects, uuids strings, enums their names, collections
 * arrays. Components that are null, and floating point values that are not finite, are left out.
 *
 * <p>The accessors of each record class are looked up once and kept. Safe from any thread.
 */
public final class EventJson {
    private static final ClassValue<Component[]> COMPONENTS = new ClassValue<>() {
        @Override
        protected Component[] computeValue(Class<?> type) {
            return components(type);
        }
    };

    private record Component(String name, Method accessor) {
    }

    private EventJson() {
    }

    /** The event as the journal writes it, header fields first. */
    public static JsonObject toJson(TrackerEvent event) {
        JsonObject o = new JsonObject();
        Header h = event.header();
        o.addProperty("kind", event.kind().id());
        o.addProperty("at", h == null ? 0L : h.at());
        o.addProperty("seq", h == null ? 0L : h.seq());
        o.add("world", h == null || h.world() == null ? JsonNull.INSTANCE : new JsonPrimitive(h.world()));
        o.add("player", h == null || h.player() == null ? JsonNull.INSTANCE : new JsonPrimitive(h.player().toString()));
        for (Component c : COMPONENTS.get(event.getClass())) {
            if (c.name.equals("header")) {
                continue;
            }
            JsonElement v = value(read(c, event), 0);
            if (v != null) {
                o.add(c.name, v);
            }
        }
        return o;
    }

    /** The record's components except the header, or an empty object for a class that is not a record. */
    public static JsonObject body(Object record) {
        JsonObject o = new JsonObject();
        for (Component c : COMPONENTS.get(record.getClass())) {
            if (c.name.equals("header")) {
                continue;
            }
            JsonElement v = value(read(c, record), 0);
            if (v != null) {
                o.add(c.name, v);
            }
        }
        return o;
    }

    private static Object read(Component c, Object target) {
        try {
            return c.accessor.invoke(target);
        } catch (IllegalAccessException | InvocationTargetException e) {
            return null;
        }
    }

    private static Component[] components(Class<?> type) {
        if (!type.isRecord()) {
            return new Component[0];
        }
        RecordComponent[] rc = type.getRecordComponents();
        List<Component> out = new ArrayList<>(rc.length);
        for (RecordComponent r : rc) {
            Method m = r.getAccessor();
            try {
                m.setAccessible(true);
            } catch (RuntimeException ignored) {
                // a public record of an exported package needs no help
            }
            out.add(new Component(r.getName(), m));
        }
        return out.toArray(new Component[0]);
    }

    /** One value as JSON, or null to leave the component out. */
    private static JsonElement value(Object v, int depth) {
        if (v == null || depth > 8) {
            return null;
        }
        if (v instanceof String s) {
            return new JsonPrimitive(s);
        }
        if (v instanceof Boolean b) {
            return new JsonPrimitive(b);
        }
        if (v instanceof Double d) {
            return d.isNaN() || d.isInfinite() ? null : new JsonPrimitive(d);
        }
        if (v instanceof Float f) {
            return f.isNaN() || f.isInfinite() ? null : new JsonPrimitive(f);
        }
        if (v instanceof Number n) {
            return new JsonPrimitive(n);
        }
        if (v instanceof Character ch) {
            return new JsonPrimitive(ch);
        }
        if (v instanceof UUID u) {
            return new JsonPrimitive(u.toString());
        }
        if (v instanceof Enum<?> e) {
            return new JsonPrimitive(e.name());
        }
        if (v instanceof Collection<?> c) {
            JsonArray a = new JsonArray(c.size());
            for (Object item : c) {
                JsonElement e = value(item, depth + 1);
                a.add(e == null ? JsonNull.INSTANCE : e);
            }
            return a;
        }
        if (v instanceof Map<?, ?> map) {
            JsonObject o = new JsonObject();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                JsonElement inner = value(e.getValue(), depth + 1);
                if (inner != null && e.getKey() != null) {
                    o.add(String.valueOf(e.getKey()), inner);
                }
            }
            return o;
        }
        if (v instanceof int[] ints) {
            JsonArray a = new JsonArray(ints.length);
            for (int i : ints) {
                a.add(i);
            }
            return a;
        }
        if (v instanceof Object[] arr) {
            JsonArray a = new JsonArray(arr.length);
            for (Object item : arr) {
                JsonElement e = value(item, depth + 1);
                a.add(e == null ? JsonNull.INSTANCE : e);
            }
            return a;
        }
        if (v.getClass().isRecord()) {
            JsonObject o = new JsonObject();
            for (Component c : COMPONENTS.get(v.getClass())) {
                JsonElement inner = value(read(c, v), depth + 1);
                if (inner != null) {
                    o.add(c.name, inner);
                }
            }
            return o;
        }
        return new JsonPrimitive(v.toString());
    }
}
