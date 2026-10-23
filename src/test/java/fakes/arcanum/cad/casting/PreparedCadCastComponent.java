package fakes.arcanum.cad.casting;

import com.hypixel.hytale.component.ComponentType;
import fakes.arcanum.spell.compile.CompiledSpell;

/** The fake prepared cast, the members the binding reads. */
public final class PreparedCadCastComponent {
    private static final ComponentType<?, ?> TYPE = new ComponentType<>();

    private final int inventorySectionId;
    private final short inventorySlot;
    private final CompiledSpell spell;
    private final String spellName;
    private final int energyCost;

    public PreparedCadCastComponent(int inventorySectionId, short inventorySlot, CompiledSpell spell, String spellName, int energyCost) {
        this.inventorySectionId = inventorySectionId;
        this.inventorySlot = inventorySlot;
        this.spell = spell;
        this.spellName = spellName;
        this.energyCost = energyCost;
    }

    public static ComponentType<?, ?> getComponentType() {
        return TYPE;
    }

    public int inventorySectionId() {
        return inventorySectionId;
    }

    public short inventorySlot() {
        return inventorySlot;
    }

    public CompiledSpell spell() {
        return spell;
    }

    public String spellName() {
        if (spellName != null && spellName.equals("throw")) {
            throw new IllegalStateException("spell name broke");
        }
        return spellName;
    }

    public int energyCost() {
        return energyCost;
    }
}
