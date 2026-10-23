package fakes.lucky;

import java.util.HashMap;
import java.util.UUID;

/**
 * The seam class of Lucky Mining, with the public static streak map and the nested info class shaped like the real
 * ones. The two helpers on the info class stand in for the mod's handler, which the test cannot run.
 */
public final class BreakBlockEventSystem {
    public static HashMap<UUID, LuckyMiningInfo> luckyMiningInfo = new HashMap<>();

    private BreakBlockEventSystem() {
    }

    public static final class LuckyMiningInfo {
        private String block;
        private double chance;
        private long lastBreak;

        public LuckyMiningInfo(String block, double chance, long lastBreak) {
            this.block = block;
            this.chance = chance;
            this.lastBreak = lastBreak;
        }

        /** What the mod does on a successful roll. */
        public void bonus(long now) {
            lastBreak = now;
            chance += 0.02;
        }

        /** What the mod does on a failed roll. */
        public void miss() {
            chance += 0.02;
        }
    }
}
