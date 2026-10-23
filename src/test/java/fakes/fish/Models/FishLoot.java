package fakes.fish.Models;

/** The shape of the mod's {@code FishLoot} asset, with the private {@code size} field the real one has. */
public class FishLoot {
    private final String itemId;
    private final String name;
    private final String rarity;
    private final int size;
    private final boolean broken;

    public FishLoot(String itemId, String name, String rarity, int size, boolean broken) {
        this.itemId = itemId;
        this.name = name;
        this.rarity = rarity;
        this.size = size;
        this.broken = broken;
    }

    public String getItemID() {
        return itemId;
    }

    public String getName() {
        return name;
    }

    public String getRarity() {
        if (broken) {
            throw new IllegalStateException("broken loot");
        }
        return rarity;
    }
}
