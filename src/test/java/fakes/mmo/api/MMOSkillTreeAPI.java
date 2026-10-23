package fakes.mmo.api;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import fakes.mmo.api.events.PlayerGainXpEvent;
import fakes.mmo.data.SkillComponent;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** The static API of the fake mod, the XP listener list and the component reads with the real signatures. */
public class MMOSkillTreeAPI {
    private static final CopyOnWriteArrayList<Consumer<PlayerGainXpEvent>> xpListeners = new CopyOnWriteArrayList<>();
    private static final Map<String, Integer> levels = new HashMap<>();
    private static int totalLevel;
    private static final SkillComponent component = new SkillComponent();

    public MMOSkillTreeAPI() {
    }

    public static void addXpListener(Consumer<PlayerGainXpEvent> listener) {
        xpListeners.add(listener);
    }

    public static boolean removeXpListener(Consumer<PlayerGainXpEvent> listener) {
        return xpListeners.remove(listener);
    }

    public static void fireXpEvent(PlayerGainXpEvent event) {
        for (Consumer<PlayerGainXpEvent> l : xpListeners) {
            try {
                l.accept(event);
            } catch (Throwable ignored) {
                // as the mod does
            }
        }
    }

    public static SkillComponent getSkillComponent(Store<EntityStore> store, Ref<EntityStore> ref) {
        return component;
    }

    public static Map<String, Integer> getAllLevelsByName(Store<EntityStore> store, Ref<EntityStore> ref) {
        return new HashMap<>(levels);
    }

    public static int getTotalLevel(Store<EntityStore> store, Ref<EntityStore> ref) {
        return totalLevel;
    }

    // ---- test knobs, not in the real API ----

    public static int listenerCount() {
        return xpListeners.size();
    }

    public static void fire(PlayerRef player, String skill, long amount, int before, int after) {
        fireXpEvent(new PlayerGainXpEvent(player, skill, amount, amount, 0L, 0L, before, after));
    }

    public static void setLevel(String skill, int level) {
        levels.put(skill, level);
    }

    public static void setTotalLevel(int level) {
        totalLevel = level;
    }

    public static void setClass(String classId) {
        component.currentClassId = classId;
    }
}
