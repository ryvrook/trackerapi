package dev.ryvrook.trackerapi.mods.rpg;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** A players walk without worlds, the visitor runs at once on the calling thread for every player put here. */
final class FakeRpgPlayers implements RpgPlayers {
    private record Entry(Object playerRef, Object ref, Object store) {
    }

    private final Map<UUID, Entry> online = new LinkedHashMap<>();
    int visits;

    void put(UUID uuid, Object playerRef) {
        online.put(uuid, new Entry(playerRef, new Object(), new Object()));
    }

    void remove(UUID uuid) {
        online.remove(uuid);
    }

    @Override
    public void forEachOnline(Visitor visitor) {
        for (Map.Entry<UUID, Entry> e : Map.copyOf(online).entrySet()) {
            visits++;
            visitor.visit(e.getKey(), e.getValue().playerRef(), e.getValue().ref(), e.getValue().store());
        }
    }

    @Override
    public void one(UUID uuid, Visitor visitor) {
        Entry e = online.get(uuid);
        if (e != null) {
            visits++;
            visitor.visit(uuid, e.playerRef(), e.ref(), e.store());
        }
    }
}
