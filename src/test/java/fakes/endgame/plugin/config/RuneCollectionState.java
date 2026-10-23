package fakes.endgame.plugin.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** The rune collection of the fake mod, rune id to the time it was found. */
public class RuneCollectionState {
    private final Map<String, Long> found = new ConcurrentHashMap<>();
    private final AtomicInteger claimedTiers = new AtomicInteger();

    public RuneCollectionState() {
    }

    public boolean markFound(String runeId) {
        return found.putIfAbsent(runeId, System.currentTimeMillis()) == null;
    }

    public boolean isFound(String runeId) {
        return found.containsKey(runeId);
    }

    public long foundAt(String runeId) {
        Long at = found.get(runeId);
        return at == null ? 0L : at;
    }

    public Map<String, Long> getFound() {
        return found;
    }

    public int getClaimedTiers() {
        return claimedTiers.get();
    }
}
