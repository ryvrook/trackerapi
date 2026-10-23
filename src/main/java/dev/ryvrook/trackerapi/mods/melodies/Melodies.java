package dev.ryvrook.trackerapi.mods.melodies;

import java.util.List;
import java.util.Locale;

/**
 * The string tables of Ymmersive Melodies 1.1.5, read from the item json files of the jar. The nine instrument
 * item ids share the prefix {@code Ymmersive_Melodies_}, the template parent is not an item a player holds. The
 * song state is the item metadata document {@code MelodyProgress} on the held instrument.
 */
public final class Melodies {
    private Melodies() {
    }

    /** The prefix of every instrument item id. */
    public static final String ITEM_PREFIX = "Ymmersive_Melodies_";
    /** The parent item of the instruments, never held. */
    public static final String TEMPLATE = "Template_Ymmersive_Melodies";

    /** The item metadata key and its fields, as {@code MelodyProgress.CODEC} writes them. */
    public static final String META_KEY = "MelodyProgress";
    public static final String META_MELODY = "Melody";
    public static final String META_TIME = "Time";
    public static final String META_START = "StartWorldTime";

    /** The id used for every uploaded song, whose own id is {@code <uploader uuid>:<name>}. */
    public static final String CUSTOM = "custom";

    /** The nine instrument item ids of 1.1.5. */
    public static final List<String> ITEMS = List.of(
            "Ymmersive_Melodies_Bagpipe", "Ymmersive_Melodies_Didgeridoo", "Ymmersive_Melodies_Flute",
            "Ymmersive_Melodies_Handpan", "Ymmersive_Melodies_Lute", "Ymmersive_Melodies_Piano",
            "Ymmersive_Melodies_Tiny_Drum", "Ymmersive_Melodies_Trumpet", "Ymmersive_Melodies_Vielle");

    /** True for an instrument item id, a later instrument with the same prefix included. */
    public static boolean isInstrument(String itemId) {
        return itemId != null && itemId.length() > ITEM_PREFIX.length() && itemId.startsWith(ITEM_PREFIX);
    }

    /** The instrument key of an item id, {@code lute} or {@code tiny_drum}, or null for any other item. */
    public static String instrumentKey(String itemId) {
        return isInstrument(itemId) ? itemId.substring(ITEM_PREFIX.length()).toLowerCase(Locale.ROOT) : null;
    }

    /** A name for people from an instrument key or a song id, {@code tiny_drum} to {@code Tiny Drum}. */
    public static String nameOf(String id) {
        if (id == null || id.isEmpty()) {
            return id;
        }
        StringBuilder sb = new StringBuilder(id.length());
        boolean up = true;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c == '_') {
                sb.append(' ');
                up = true;
            } else {
                sb.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return sb.toString();
    }

    /** True for an uploaded song id, which carries the uploader's uuid and a free text name. */
    public static boolean isUploaded(String melody) {
        return melody != null && melody.indexOf(':') >= 0;
    }

    /** The id a song is counted under, the built in id or {@link #CUSTOM}. */
    public static String songKey(String melody) {
        return isUploaded(melody) ? CUSTOM : melody;
    }
}
