package fakes.lucky;

import java.util.HashMap;
import java.util.UUID;

/** A Lucky Mining whose info class lost a field, for the partial bind test. */
public final class RenamedBreakBlockEventSystem {
    public static HashMap<UUID, LuckyMiningInfo> luckyMiningInfo = new HashMap<>();

    private RenamedBreakBlockEventSystem() {
    }

    public static final class LuckyMiningInfo {
        private String block;
        private double chance;
        private long lastBonus;

        public LuckyMiningInfo(String block, double chance, long lastBonus) {
            this.block = block;
            this.chance = chance;
            this.lastBonus = lastBonus;
        }
    }
}
