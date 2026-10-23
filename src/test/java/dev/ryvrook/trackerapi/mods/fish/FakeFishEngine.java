package dev.ryvrook.trackerapi.mods.fish;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * An engine without an engine. It keeps the consumers by event class, remembers which classes were registered at
 * the last priority, and {@link #fire} hands an event to every consumer of its class. A fake ref's player is read
 * from its {@code getUuid()}.
 */
final class FakeFishEngine implements FishEngine {
    private final Map<Class<?>, List<Consumer<Object>>> listeners = new ConcurrentHashMap<>();
    private final Set<Class<?>> last = ConcurrentHashMap.newKeySet();

    @Override
    public Runnable listen(Class<?> eventClass, boolean atLast, Consumer<Object> consumer) {
        listeners.computeIfAbsent(eventClass, k -> new CopyOnWriteArrayList<>()).add(consumer);
        if (atLast) {
            last.add(eventClass);
        }
        return () -> {
            List<Consumer<Object>> list = listeners.get(eventClass);
            if (list != null) {
                list.remove(consumer);
                if (list.isEmpty()) {
                    listeners.remove(eventClass);
                    last.remove(eventClass);
                }
            }
        };
    }

    @Override
    public UUID playerOf(Object ref) {
        if (ref == null) {
            return null;
        }
        try {
            Method m = ref.getClass().getMethod("getUuid");
            return (UUID) m.invoke(ref);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    void fire(Object event) {
        List<Consumer<Object>> list = listeners.get(event.getClass());
        if (list != null) {
            for (Consumer<Object> c : list) {
                c.accept(event);
            }
        }
    }

    int registrations() {
        int n = 0;
        for (List<Consumer<Object>> list : listeners.values()) {
            n += list.size();
        }
        return n;
    }

    List<Class<?>> classes() {
        return new ArrayList<>(listeners.keySet());
    }

    boolean atLast(Class<?> eventClass) {
        return last.contains(eventClass);
    }

    List<Consumer<Object>> consumersOf(Class<?> eventClass) {
        List<Consumer<Object>> list = listeners.get(eventClass);
        return list == null ? List.of() : new ArrayList<>(list);
    }
}
