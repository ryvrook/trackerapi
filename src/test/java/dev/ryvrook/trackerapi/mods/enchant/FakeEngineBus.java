package dev.ryvrook.trackerapi.mods.enchant;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * An engine bus without an engine. It keeps the consumers by event class and {@link #fire} hands an event to every
 * consumer registered for its class, as the engine's dispatcher would for a global registration.
 */
final class FakeEngineBus implements EngineBus {
    private final Map<Class<?>, List<Consumer<Object>>> listeners = new ConcurrentHashMap<>();

    @Override
    public Runnable listen(Class<?> eventClass, Consumer<Object> consumer) {
        listeners.computeIfAbsent(eventClass, k -> new CopyOnWriteArrayList<>()).add(consumer);
        return () -> {
            List<Consumer<Object>> list = listeners.get(eventClass);
            if (list != null) {
                list.remove(consumer);
                if (list.isEmpty()) {
                    listeners.remove(eventClass);
                }
            }
        };
    }

    /** Dispatches an event to the consumers of its class. */
    void fire(Object event) {
        List<Consumer<Object>> list = listeners.get(event.getClass());
        if (list != null) {
            for (Consumer<Object> c : list) {
                c.accept(event);
            }
        }
    }

    /** How many consumers are registered over all classes. */
    int registrations() {
        int n = 0;
        for (List<Consumer<Object>> list : listeners.values()) {
            n += list.size();
        }
        return n;
    }

    /** The event classes with a consumer. */
    List<Class<?>> classes() {
        return new ArrayList<>(listeners.keySet());
    }

    /** The consumers of one class, for a test that calls one after an unbind. */
    List<Consumer<Object>> consumersOf(Class<?> eventClass) {
        List<Consumer<Object>> list = listeners.get(eventClass);
        return list == null ? List.of() : new ArrayList<>(list);
    }
}
