package fakes.enchant.enchantment;

/**
 * The shape of the mod's {@code EnchantmentType}, a final class with an id, a max level and a legendary flag. A
 * broken one throws from {@code getId()}, for the guard test.
 */
public final class EnchantmentType {
    private final String id;
    private final int maxLevel;
    private final boolean legendary;
    private final boolean broken;

    public EnchantmentType(String id, int maxLevel, boolean legendary) {
        this(id, maxLevel, legendary, false);
    }

    public EnchantmentType(String id, int maxLevel, boolean legendary, boolean broken) {
        this.id = id;
        this.maxLevel = maxLevel;
        this.legendary = legendary;
        this.broken = broken;
    }

    public String getId() {
        if (broken) {
            throw new IllegalStateException("no id for " + id);
        }
        return id;
    }

    public String getDisplayName() {
        return id;
    }

    public int getMaxLevel() {
        return maxLevel;
    }

    public boolean isLegendary() {
        return legendary;
    }

    public static EnchantmentType fromId(String id) {
        return new EnchantmentType(id, 5, false);
    }

    @Override
    public String toString() {
        return id;
    }
}
