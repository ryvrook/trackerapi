package fakes.fish.Models;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The shape of the mod's merged Almanac layout. The test sets the master book with {@link #setMaster}. A habitat
 * lists its page ids, a zone's title page is listed as an entry that is not a loot asset, as the mod does.
 */
public class BookAssetData {
    private static BookAssetData master;
    private final habitatsInfo[] habitats;
    public int reads;

    public BookAssetData(habitatsInfo... habitats) {
        this.habitats = habitats;
    }

    public static void setMaster(BookAssetData book) {
        master = book;
    }

    public static BookAssetData getMasterMergedBook() {
        return master;
    }

    public habitatsInfo[] getHabitats() {
        reads++;
        return habitats;
    }

    public List<FishEntry> getFishByHabitat(String zone) {
        for (habitatsInfo h : habitats) {
            if (h.ZoneName.toLowerCase(Locale.ROOT).equals(zone.toLowerCase(Locale.ROOT))) {
                List<FishEntry> out = new ArrayList<>();
                out.add(new FishEntry(h.ZoneName, false));
                for (String id : h.pages) {
                    out.add(new FishEntry(id, true));
                }
                return out;
            }
        }
        return List.of();
    }

    public List<FishEntry> getAllFish() {
        List<FishEntry> out = new ArrayList<>();
        for (habitatsInfo h : habitats) {
            out.addAll(getFishByHabitat(h.ZoneName));
        }
        return out;
    }

    public record FishEntry(String id, boolean isItem) {
    }

    public static class habitatsInfo {
        public String ZoneName;
        public String[] pages;

        public habitatsInfo(String zoneName, String... pages) {
            this.ZoneName = zoneName;
            this.pages = pages;
        }
    }
}
