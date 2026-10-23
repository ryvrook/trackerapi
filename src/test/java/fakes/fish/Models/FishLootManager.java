package fakes.fish.Models;

/** The shape of the mod's {@code FishLootManager}, a loot asset with its id. */
public class FishLootManager extends FishLoot {
    private final String id;

    public FishLootManager(String id, String itemId, String name, String rarity, int size, boolean broken) {
        super(itemId, name, rarity, size, broken);
        this.id = id;
    }

    public String getId() {
        return id;
    }
}
