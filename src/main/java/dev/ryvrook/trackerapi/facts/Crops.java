package dev.ryvrook.trackerapi.facts;

/**
 * Id rules for crops that need no engine. A crop changes its block to a state variant as it grows, and the
 * engine names that variant {@code *<block>_State_Definitions_<stage>}, so a harvest reports
 * {@code *Plant_Crop_Carrot_Block_State_Definitions_StageFinal} where the planting reported
 * {@code Plant_Crop_Carrot_Block}. {@link #baseId} maps the first to the second so both counters use one id.
 */
public final class Crops {
    /** What the engine puts between a block id and the name of one of its states. */
    public static final String STATE_MARKER = "_State_";

    private Crops() {
    }

    /** The block a state variant belongs to, or the id itself when it is not a state. Null stays null. */
    public static String baseId(String blockId) {
        if (blockId == null) {
            return null;
        }
        String id = blockId.startsWith("*") ? blockId.substring(1) : blockId;
        int marker = id.lastIndexOf(STATE_MARKER);
        return marker > 0 ? id.substring(0, marker) : id;
    }
}
