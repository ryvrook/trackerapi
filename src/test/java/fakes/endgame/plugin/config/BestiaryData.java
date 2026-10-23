package fakes.endgame.plugin.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** The bestiary holder of the fake mod, with the per player state and the per creature entry as nested classes. */
public final class BestiaryData {
    private BestiaryData() {
    }

    public static class PlayerBestiaryState {
        final Map<String, NPCEntry> entries = new ConcurrentHashMap<>();
        final AtomicInteger claimedDiscoveryMilestone = new AtomicInteger();

        public PlayerBestiaryState() {
        }

        public Map<String, NPCEntry> getEntries() {
            return entries;
        }

        public NPCEntry getOrCreate(String npcTypeId) {
            return entries.computeIfAbsent(npcTypeId, k -> new NPCEntry());
        }

        public int getTotalKills() {
            int n = 0;
            for (NPCEntry e : entries.values()) {
                n += e.getKillCount();
            }
            return n;
        }

        public int getDiscoveredCount() {
            int n = 0;
            for (NPCEntry e : entries.values()) {
                if (e.isDiscovered()) {
                    n++;
                }
            }
            return n;
        }

        public int getClaimedDiscoveryMilestone() {
            return claimedDiscoveryMilestone.get();
        }
    }

    public static class NPCEntry {
        final AtomicInteger killCount = new AtomicInteger();
        volatile long firstKillTimestamp;
        volatile boolean discovered;
        final AtomicInteger claimedMilestone = new AtomicInteger();

        public NPCEntry() {
        }

        public int getKillCount() {
            return killCount.get();
        }

        public long getFirstKillTimestamp() {
            return firstKillTimestamp;
        }

        public boolean isDiscovered() {
            return discovered;
        }

        public int getClaimedMilestone() {
            return claimedMilestone.get();
        }

        public void recordKill() {
            if (killCount.incrementAndGet() == 1) {
                firstKillTimestamp = System.currentTimeMillis();
            }
        }

        public void markDiscovered() {
            discovered = true;
        }
    }
}
