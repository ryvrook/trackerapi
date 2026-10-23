package fakes.enchant.api.event;

import com.hypixel.hytale.event.IEvent;
import fakes.enchant.FakeItemStack;
import fakes.enchant.FakePlayerRef;
import fakes.enchant.enchantment.EnchantmentType;

/** The shape of the mod's {@code EnchantmentActivatedEvent}, an {@code IEvent<Void>} with four getters. */
public class EnchantmentActivatedEvent implements IEvent<Void> {
    private final FakePlayerRef playerRef;
    private final FakeItemStack item;
    private final EnchantmentType enchantment;
    private final int level;

    public EnchantmentActivatedEvent(FakePlayerRef playerRef, FakeItemStack item, EnchantmentType enchantment, int level) {
        this.playerRef = playerRef;
        this.item = item;
        this.enchantment = enchantment;
        this.level = level;
    }

    public FakePlayerRef getPlayerRef() {
        return playerRef;
    }

    public FakeItemStack getItem() {
        return item;
    }

    public EnchantmentType getEnchantment() {
        return enchantment;
    }

    public int getLevel() {
        return level;
    }
}
