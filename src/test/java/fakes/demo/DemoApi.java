package fakes.demo;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** The API singleton of the demo mod, with a listener list, a static accessor and a read. */
public final class DemoApi {
    private static DemoApi instance;
    private final List<DemoListener> listeners = new CopyOnWriteArrayList<>();
    private int level = 7;

    public static DemoApi get() {
        if (instance == null) {
            instance = new DemoApi();
        }
        return instance;
    }

    public static boolean isAvailable() {
        return true;
    }

    public void registerLevelUpListener(DemoListener l) {
        listeners.add(l);
    }

    public boolean unregisterLevelUpListener(DemoListener l) {
        return listeners.remove(l);
    }

    public int listenerCount() {
        return listeners.size();
    }

    public int getPlayerLevel(UUID player) {
        return level;
    }

    public void fire(UUID player, int from, int to) {
        level = to;
        for (DemoListener l : listeners) {
            l.onLevelUp(new DemoLevelEvent(player, from, to));
        }
    }
}
