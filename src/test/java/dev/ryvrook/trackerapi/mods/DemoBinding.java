package dev.ryvrook.trackerapi.mods;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.ModKeys;
import dev.ryvrook.trackerapi.api.StatSpec;
import dev.ryvrook.trackerapi.api.event.ModLevelUp;

import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A binding to the fake demo mod, written the way a real binding is: every class through the mod's loader, every
 * method a handle, one feature per group, a proxy for the listener, an undo for the unregistration. It is both
 * the test subject of the infrastructure and the pattern the real bindings follow.
 */
final class DemoBinding extends AbstractBinding {
    static final String ID = "demo";
    static final String API = "fakes.demo.DemoApi";
    static final String LISTENER = "fakes.demo.DemoListener";
    static final String EVENT = "fakes.demo.DemoLevelEvent";
    static final String F_LEVELS = "level up listener";
    static final String F_READS = "level reads";

    /** When set, the listener feature asks for a method the fake does not have, to test a partial bind. */
    volatile boolean breakListener;
    volatile Object api;
    private MethodHandle getLevel;

    DemoBinding() {
        super(ID, "Fakes:Demo", "Demo Mod");
    }

    @Override
    public Set<EventKind> kinds() {
        return Set.of(EventKind.MOD_LEVEL_UP);
    }

    @Override
    public List<StatSpec> stats() {
        return List.of(StatSpec.max(ModKeys.key(ID, ModKeys.LEVEL), "Level"),
                StatSpec.sum(ModKeys.key(ID, ModKeys.LEVEL_UPS), "Level ups"));
    }

    @Override
    protected void resolve(Object plugin) throws Throwable {
        Class<?> apiType = Handles.type(loader, API);
        MethodHandle get = Handles.statik(apiType, "get", apiType);
        api = get.invoke();
        feature(F_LEVELS, () -> {
            Class<?> listener = Handles.type(loader, LISTENER);
            Class<?> event = Handles.type(loader, EVENT);
            MethodHandle register = Handles.virtual(apiType, breakListener ? "registerLevelListener" : "registerLevelUpListener", void.class, listener);
            MethodHandle unregister = Handles.virtual(apiType, "unregisterLevelUpListener", boolean.class, listener);
            MethodHandle player = Handles.virtual(event, "getPlayer", UUID.class);
            MethodHandle oldLevel = Handles.virtual(event, "getOldLevel", int.class);
            MethodHandle newLevel = Handles.virtual(event, "getNewLevel", int.class);
            Object proxy = Handles.proxy(listener, (p, m, args) -> {
                if (m.getName().equals("onLevelUp") && args != null && args.length == 1) {
                    guard(F_LEVELS, () -> onLevelUp(args[0], player, oldLevel, newLevel));
                    return null;
                }
                return switch (m.getName()) {
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == args[0];
                    case "toString" -> "TrackerAPI demo listener";
                    default -> null;
                };
            });
            register.invoke(api, proxy);
            onUnbind(() -> {
                try {
                    unregister.invoke(api, proxy);
                } catch (Throwable ignored) {
                    // the mod is gone
                }
            });
        });
        feature(F_READS, () -> getLevel = Handles.virtual(apiType, "getPlayerLevel", int.class, UUID.class));
    }

    private void onLevelUp(Object event, MethodHandle player, MethodHandle oldLevel, MethodHandle newLevel) throws Throwable {
        if (!wants(EventKind.MOD_LEVEL_UP)) {
            return;
        }
        UUID uuid = uuidOf(player.invoke(event));
        int from = Handles.intOf(oldLevel.invoke(event), 0);
        int to = Handles.intOf(newLevel.invoke(event), 0);
        publish(new ModLevelUp(header(uuid), ID, ModKeys.PLAYER_TRACK, from, to, 0));
        fact(uuid, "level", Integer.toString(to));
    }

    /** Runs the guard for a test of the failure limit. */
    void guardForTest(String feature, Body body) {
        guard(feature, body);
    }

    /** A read through the handle, for the reads feature test. */
    int level(UUID player) {
        if (!has(F_READS)) {
            return -1;
        }
        try {
            return Handles.intOf(getLevel.invoke(api, player), -1);
        } catch (Throwable t) {
            return -1;
        }
    }
}
