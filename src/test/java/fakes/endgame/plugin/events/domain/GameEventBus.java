package fakes.endgame.plugin.events.domain;

import com.hypixel.hytale.registry.Registration;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The fake mod's bus, shaped like the real one: consumers keyed by the exact event class, a synchronous publish
 * on the caller's thread that swallows listener failures, and an engine registration that removes the consumer.
 */
public final class GameEventBus {
    private final Map<Class<?>, List<Consumer<?>>> listeners = new ConcurrentHashMap<>();

    public GameEventBus() {
    }

    public <T extends GameEvent> Registration subscribe(Class<T> type, Consumer<T> consumer) {
        List<Consumer<?>> list = listeners.computeIfAbsent(type, t -> new CopyOnWriteArrayList<>());
        list.add(consumer);
        return new Registration(() -> true, () -> list.remove(consumer));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public void publish(GameEvent event) {
        List<Consumer<?>> list = listeners.get(event.getClass());
        if (list == null) {
            return;
        }
        for (Consumer c : list) {
            try {
                c.accept(event);
            } catch (RuntimeException e) {
                // the real bus logs and goes on
            }
        }
    }

    /** Test side, how many consumers listen to the class. */
    public int listenerCount(Class<?> type) {
        List<Consumer<?>> list = listeners.get(type);
        return list == null ? 0 : list.size();
    }

    /** Test side, how many consumers listen in all. */
    public int listenerCount() {
        int n = 0;
        for (List<Consumer<?>> list : listeners.values()) {
            n += list.size();
        }
        return n;
    }
}
