package dev.ryvrook.trackerapi.mods.relics;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The string tables of Wan's Wonder Weapons 1.1.1, read from the asset pack of the jar. The item ids of the 16
 * relics with their casing as shipped, the root interaction ids of the signature abilities with the ability name
 * and the relic each belongs to, and the roots that switch a relic's form. The mod has no API, so these ids are
 * the whole contract.
 */
public final class Relics {
    private Relics() {
    }

    public static final String ASHTHORN = "WanMine_Ashthorn_Dagger";
    public static final String FROSTBURN = "WanMine_Frostburn_Dagger";
    public static final String NIGHTSHADE = "WanMine_Nightshade_Dagger";
    public static final String HEARTROOT = "WanMine_Heartroot_Dagger";
    /** The one relic whose id starts with a lower case m. */
    public static final String ECLIPSEBOUND = "Wanmine_Weapon_Eclipsebound_Dagger";
    public static final String CHROMATIC_CLEAVER = "WanMine_Chromatic_Cleaver_Dagger";
    public static final String LETHAL_LEFTOVERS = "WanMine_Lethal_Leftovers_Sword";
    public static final String GAIAS_WRATH = "WanMine_Gaias_Wrath_Sword";
    public static final String QUASAR_COSMIC = "WanMine_Quasar_Cosmic_Sword";
    public static final String SOULBLIGHT = "WanMine_Soulblight_Longsword";
    public static final String SOULBLIGHT_ASHEN = "WanMine_Soulblight_Ashen_Longsword";
    public static final String MAELSTROM = "WanMine_Maelstrom_Mace";
    public static final String HELIORAM = "WanMine_Helioram_Mace";
    public static final String MJOLLNIR = "WanMine_Mjollnir_Mace";
    public static final String GOD_SLAYER = "WanMine_God_Slayer_Greatsword";
    public static final String VOID_REQUIEM = "WanMine_Void_Requiem_Scythe";

    /** Every relic item id, in the order of the mod's docs. */
    public static final List<String> ITEMS = List.of(ASHTHORN, FROSTBURN, NIGHTSHADE, HEARTROOT, ECLIPSEBOUND,
            CHROMATIC_CLEAVER, LETHAL_LEFTOVERS, GAIAS_WRATH, QUASAR_COSMIC, SOULBLIGHT, SOULBLIGHT_ASHEN, MAELSTROM,
            HELIORAM, MJOLLNIR, GOD_SLAYER, VOID_REQUIEM);

    private static final Set<String> ITEM_SET = Set.copyOf(ITEMS);

    /** The two spellings the mod uses at the front of its item ids, so a relic added later is still a relic. */
    private static final String[] PREFIXES = {"WanMine_", "Wanmine_"};

    /** The item id when it is a relic, else null. Exact ids first, then the mod's prefixes. */
    public static String relicOf(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return null;
        }
        if (ITEM_SET.contains(itemId)) {
            return itemId;
        }
        for (String p : PREFIXES) {
            if (itemId.startsWith(p)) {
                return itemId;
            }
        }
        return null;
    }

    /** True for a relic item id. */
    public static boolean isRelic(String itemId) {
        return relicOf(itemId) != null;
    }

    /**
     * A signature ability root. {@code ability} is the short name published and counted, {@code relic} the item the
     * root belongs to, or null for a vanilla signature root that several relics share, which is then attributed to
     * the relic in hand.
     */
    public record Signature(String rootId, String ability, String relic) {
    }

    private static final List<Signature> SIGNATURE_LIST = List.of(
            new Signature("Root_WanMine_GodSlayer_Signature", "mortal_verdict", GOD_SLAYER),
            new Signature("Root_Weapon_Quasar_Cosmic_Signature_Vortexstrike", "black_hole", QUASAR_COSMIC),
            new Signature("Root_Weapon_Soulblight_Signature_Summon", "summon", SOULBLIGHT),
            new Signature("Root_Weapon_Soulblight_Signature_Summon_Burn", "summon_burn", SOULBLIGHT_ASHEN),
            new Signature("Root_Weapon_Void_Requiem_Signature_Airblades", "airblades", VOID_REQUIEM),
            new Signature("Root_Weapon_Void_Requiem_Ability4_Teleport", "teleport", VOID_REQUIEM),
            new Signature("Root_Weapon_Eclipsebound_Signature_Phoenix_Strike", "phoenix_strike", ECLIPSEBOUND),
            new Signature("Root_Weapon_Eclipsebound_Signature_Moonlight_Garden", "moonlight_garden", ECLIPSEBOUND),
            new Signature("Root_Weapon_Eclipsebound_Pounce_Scorch", "pounce_scorch", ECLIPSEBOUND),
            new Signature("Root_Weapon_Chromatic_Cleaver_Signature_Prismatic_Guillotine", "prismatic_guillotine", CHROMATIC_CLEAVER),
            // vanilla signature roots, the relic is the one in hand
            new Signature("Root_Weapon_Daggers_Signature_Razorstrike", "razorstrike", null),
            new Signature("Root_Weapon_Sword_Signature_Vortexstrike", "vortexstrike", null),
            new Signature("Root_Weapon_Mace_Signature_Groundslam", "groundslam", null));

    /** The signature roots by root id. */
    public static final Map<String, Signature> SIGNATURES = byRoot(SIGNATURE_LIST);

    private static Map<String, Signature> byRoot(List<Signature> list) {
        Map<String, Signature> map = new HashMap<>();
        for (Signature s : list) {
            map.put(s.rootId(), s);
        }
        return Map.copyOf(map);
    }

    /** The roots that switch a relic's form, Eclipsebound day and night and the Chromatic Cleaver stances. */
    public static final Set<String> FORM_SWITCHES = Set.of(
            "Root_Weapon_Eclipsebound_DayToNight_Switch",
            "Root_Weapon_Eclipsebound_NightToDay_Switch",
            "Root_Weapon_Chromatic_Cleaver_Stance_Change",
            "Root_Weapon_Chromatic_Cleaver_Telekinetic_Stance_Change");

    /** The signature a root id names, or null. */
    public static Signature signatureOf(String rootId) {
        return rootId == null ? null : SIGNATURES.get(rootId);
    }

    /** True for a form switch root. */
    public static boolean isFormSwitch(String rootId) {
        return rootId != null && FORM_SWITCHES.contains(rootId);
    }
}
