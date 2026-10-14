package dev.ryvrook.trackerapi;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.TrackerEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Event record class to kind, worked out once per class from the record's {@code kind()} on a probe. */
final class EventKinds {
    private static final Map<Class<?>, EventKind> BY_CLASS = new ConcurrentHashMap<>();

    private EventKinds() {
    }

    static EventKind of(Class<? extends TrackerEvent> type) {
        EventKind hit = BY_CLASS.get(type);
        if (hit != null) {
            return hit;
        }
        // Each record's simple name is its kind in camel case, BlockBroken is BLOCK_BROKEN.
        String simple = type.getSimpleName();
        StringBuilder sb = new StringBuilder(simple.length() + 8);
        for (int i = 0; i < simple.length(); i++) {
            char c = simple.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                sb.append('_');
            }
            sb.append(Character.toUpperCase(c));
        }
        EventKind kind;
        try {
            kind = EventKind.valueOf(sb.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
        BY_CLASS.put(type, kind);
        return kind;
    }
}
