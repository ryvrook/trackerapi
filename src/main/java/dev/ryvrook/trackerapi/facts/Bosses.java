package dev.ryvrook.trackerapi.facts;

import java.util.Set;

/**
 * Which creatures are bosses, by NPC type id. A boss fight credits every player who took part, not only the
 * killing blow, see {@link BossLedger}. The same list and prefixes as Feats 1.4.0.
 */
public final class Bosses {
    private static final Set<String> BOSSES = Set.of(
            "Dragon_Fire", "Dragon_Frost", "Goblin_Duke", "Scarak_Broodmother",
            "Dungeon_Scarak_Broodmother", "Goblin_Ogre", "Shadow_Knight", "Golem_Guardian_Void",
            // Chapter I. Multi phase bosses swap roles instead of dying, so only the last phase dies.
            "Goblin_Duke_Phase_2", "Goblin_Duke_Phase_3_Fast", "Goblin_Duke_Phase_3_Slow",
            "Dungeon_Feastmaster_Boss", "Bear_Voidtaken", "Skeleton_Elite", "Skeleton_Elite_Phase_2",
            // The Dread Wade, behind the Ancient Gateway
            "Hedera");

    private Bosses() {
    }

    /**
     * True for a boss whose fight should credit every player who took part, not only the killing blow.
     * Pass an NPC type id.
     */
    public static boolean isBoss(String npcTypeId) {
        return npcTypeId != null && (BOSSES.contains(npcTypeId) || isGobliterator(npcTypeId));
    }

    /** True for the Gobliterator role that dies at the end of the fight, on either difficulty. */
    public static boolean isGobliteratorFinal(String npcTypeId) {
        return npcTypeId != null && npcTypeId.startsWith("Goblin_Gobliterator_Phase3");
    }

    /** True for the hard mode Gobliterator role that dies at the end of the fight. */
    public static boolean isGobliteratorFinalHard(String npcTypeId) {
        return "Goblin_Gobliterator_Phase3_HM".equals(npcTypeId);
    }

    /** Every Gobliterator boss role. The fight swaps roles between phases, so exactly one of them dies. */
    private static boolean isGobliterator(String id) {
        return id.startsWith("Goblin_Gobliterator_Phase") || id.startsWith("Goblin_Gobliterator_Transition")
                || id.startsWith("Goblin_Gobliterator_SawTransition");
    }
}
