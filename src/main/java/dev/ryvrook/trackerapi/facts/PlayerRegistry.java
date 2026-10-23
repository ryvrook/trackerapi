package dev.ryvrook.trackerapi.facts;

import dev.ryvrook.trackerapi.api.PlayerSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is online, where they are and since when. The hooks keep it current, the sampler writes the
 * snapshots, the API reads them. One object per online player, replaced wholesale by the sampler so a
 * reader never sees a half written sample. Safe from any thread.
 */
public final class PlayerRegistry {

    /** The mutable per session facts the hooks keep. */
    public static final class Online {
        public final UUID uuid;
        public volatile String name;
        public volatile String world;
        public volatile String gameMode = "";
        public volatile boolean creative;
        public volatile long sessionStart;
        public volatile boolean readyOnce;
        public volatile PlayerSnapshot snapshot;
        /** Time of the last death, for the respawn and life length facts, 0 while alive since join. */
        public volatile long lastDeathAt;
        /** The last position the sampler saw, so a teleport can report where from. */
        public volatile double lastX, lastY, lastZ;
        public volatile boolean placed;
        /** Strings the mod bindings keep about the player, such as {@code rpg.level}, copied into the snapshot. */
        public final java.util.concurrent.ConcurrentHashMap<String, String> facts = new java.util.concurrent.ConcurrentHashMap<>();

        Online(UUID uuid, String name, long now) {
            this.uuid = uuid;
            this.name = name == null ? "" : name;
            this.sessionStart = now;
        }
    }

    private final Map<UUID, Online> online = new ConcurrentHashMap<>();

    public Online join(UUID uuid, String name, long now) {
        Online o = online.computeIfAbsent(uuid, id -> new Online(id, name, now));
        if (name != null && !name.isEmpty()) {
            o.name = name;
        }
        return o;
    }

    public Online get(UUID uuid) {
        return uuid == null ? null : online.get(uuid);
    }

    public Online leave(UUID uuid) {
        return uuid == null ? null : online.remove(uuid);
    }

    public boolean isOnline(UUID uuid) {
        return uuid != null && online.containsKey(uuid);
    }

    public String worldOf(UUID uuid) {
        Online o = get(uuid);
        return o == null ? null : o.world;
    }

    public String nameOf(UUID uuid) {
        Online o = get(uuid);
        return o == null ? null : o.name;
    }

    public boolean isCreative(UUID uuid) {
        Online o = get(uuid);
        return o != null && o.creative;
    }

    public PlayerSnapshot snapshot(UUID uuid) {
        Online o = get(uuid);
        return o == null ? null : o.snapshot;
    }

    public List<PlayerSnapshot> snapshots() {
        List<PlayerSnapshot> out = new ArrayList<>(online.size());
        for (Online o : online.values()) {
            PlayerSnapshot s = o.snapshot;
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    public List<Online> all() {
        return new ArrayList<>(online.values());
    }

    public int count() {
        return online.size();
    }

    public void clear() {
        online.clear();
    }
}
