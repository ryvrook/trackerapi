package dev.ryvrook.trackerapi.counters;

import dev.ryvrook.trackerapi.TestSupport;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.Keys;
import dev.ryvrook.trackerapi.api.ModKeys;
import dev.ryvrook.trackerapi.api.event.CompanionChanged;
import dev.ryvrook.trackerapi.api.event.DungeonRun;
import dev.ryvrook.trackerapi.api.event.EnchantmentTriggered;
import dev.ryvrook.trackerapi.api.event.ItemEnchanted;
import dev.ryvrook.trackerapi.api.event.LuckyDrop;
import dev.ryvrook.trackerapi.api.event.ModAbilityUsed;
import dev.ryvrook.trackerapi.api.event.ModAction;
import dev.ryvrook.trackerapi.api.event.ModBossDefeated;
import dev.ryvrook.trackerapi.api.event.ModChoiceChanged;
import dev.ryvrook.trackerapi.api.event.ModLevelUp;
import dev.ryvrook.trackerapi.api.event.ModMilestone;
import dev.ryvrook.trackerapi.api.event.ModXpGained;
import dev.ryvrook.trackerapi.api.event.VeinMined;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The counter rules for the generic mod events, by the binding's prefix. */
class ModCountersTest {
    private static final UUID P = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final String W = "default";

    @TempDir
    Path dir;
    private CounterStore store;
    private CounterSink sink;

    @BeforeEach
    void setUp() {
        store = TestSupport.store(dir);
        store.loadServer();
        store.join(P, "Alder", 1L);
        sink = new CounterSink(store);
    }

    private Header h() {
        return TestSupport.header(W, P);
    }

    private long get(String key) {
        return store.get(P, key);
    }

    @Test
    void levelsXpAndChoices() {
        sink.accept(new ModLevelUp(h(), "rpg", "player", 3, 5, 100));
        sink.accept(new ModLevelUp(h(), "rpg", "player", 5, 6, 100));
        sink.accept(new ModLevelUp(h(), "rpg", "player", 6, 4, 100)); // a profile swap looks like a drop, the best stays
        assertEquals(6, get("rpg:level"));
        assertEquals(4, get("rpg:level_ups"));
        assertEquals(0, get("rpg:max_level_reached"));
        sink.accept(new ModLevelUp(h(), "rpg", "player", 99, 100, 100));
        assertEquals(1, get("rpg:max_level_reached:player"));
        sink.accept(new ModLevelUp(h(), "mmo", "Mining", 1, 2, 0));
        assertEquals(2, get("mmo:skill_level:Mining"));
        assertEquals(1, get("mmo:skill_level_ups"));
        assertEquals(0, get("mmo:level"));

        sink.accept(new ModXpGained(h(), "rpg", "player", 12.6, "KILL"));
        sink.accept(new ModXpGained(h(), "rpg", "player", 0.2, "KILL"));
        sink.accept(new ModXpGained(h(), "mmo", "Mining", 5, null));
        assertEquals(13, get("rpg:xp"));
        assertEquals(13, get("rpg:xp:KILL"));
        assertEquals(5, get("mmo:xp:Mining"));
        assertEquals(13, store.server("rpg:xp"));

        sink.accept(new ModChoiceChanged(h(), "rpg", "class", "warrior", "Warrior", 1));
        sink.accept(new ModChoiceChanged(h(), "rpg", "class", "warrior", "Warrior", 2));
        sink.accept(new ModChoiceChanged(h(), "rpg", "class", "mage", "Mage", 1));
        assertEquals(1, get("rpg:class:warrior"));
        assertEquals(2, get("rpg:classes"));
    }

    @Test
    void abilitiesActionsAndMilestones() {
        sink.accept(new ModAbilityUsed(h(), "mmo", "Fireball", "Staff", "Goblin"));
        sink.accept(new ModAbilityUsed(h(), "mmo", "Fireball", null, null));
        sink.accept(new ModAbilityUsed(h(), "mmo", null, null, null));
        assertEquals(3, get("mmo:abilities"));
        assertEquals(2, get("mmo:abilities:Fireball"));

        sink.accept(new ModAction(h(), "parries", "perfect_parry", false, "Goblin", 0));
        sink.accept(new ModAction(h(), "parries", "perfect_parry", true, null, 0));
        assertEquals(2, get("parries:perfect_parry"));
        assertEquals(1, get("parries:perfect_parry:pvp"));

        sink.accept(new ModMilestone(h(), "mmo", "quest", "q1", "First steps"));
        sink.accept(new ModMilestone(h(), "mmo", "quest", "q1", "First steps"));
        sink.accept(new ModMilestone(h(), "mmo", "achievement", "a1", null));
        assertEquals(1, get("mmo:quest:q1"));
        assertEquals(1, get("mmo:quests"));
        assertEquals(1, get("mmo:achievements"));
    }

    @Test
    void dungeonsAndBosses() {
        sink.accept(new DungeonRun(h(), "dungeons", "Crypt", DungeonRun.Phase.ENTERED, "Hardcore", 3, 0));
        sink.accept(new DungeonRun(h(), "dungeons", "Crypt", DungeonRun.Phase.COMPLETED, "Hardcore", 3, 600_000));
        sink.accept(new DungeonRun(h(), "dungeons", "Crypt", DungeonRun.Phase.ENTERED, "Normal", 1, 0));
        sink.accept(new DungeonRun(h(), "dungeons", "Crypt", DungeonRun.Phase.COMPLETED, "Normal", 1, 400_000));
        sink.accept(new DungeonRun(h(), "dungeons", "Tomb", DungeonRun.Phase.ENTERED, null, 0, 0));
        sink.accept(new DungeonRun(h(), "dungeons", "Tomb", DungeonRun.Phase.FAILED, null, 0, 90_000));
        sink.accept(new DungeonRun(h(), "dungeons", "Tomb", DungeonRun.Phase.LEFT, null, 0, 0));
        assertEquals(3, get("dungeons:runs"));
        assertEquals(2, get("dungeons:runs:Crypt"));
        assertEquals(2, get("dungeons:cleared"));
        assertEquals(1, get("dungeons:cleared_mode:Hardcore"));
        assertEquals(400_000, get("dungeons:fastest"));
        assertEquals(400_000, get("dungeons:fastest:Crypt"));
        assertEquals(1, get("dungeons:failed"));
        assertEquals(1, get("dungeons:failed:Tomb"));
        assertTrue(CounterRules.isMin("dungeons:fastest"));
        assertTrue(CounterRules.isMin("dungeons:fastest:Crypt"));
        assertFalse(CounterRules.isMin("dungeons:cleared"));

        sink.accept(new ModBossDefeated(h(), "dungeons", "Lich", "Crypt", List.of(P)));
        assertEquals(1, get("dungeons:bosses"));
        assertEquals(1, get("dungeons:bosses:Lich"));
    }

    @Test
    void enchantsMiningAndCompanions() {
        sink.accept(new ItemEnchanted(h(), "enchant", "Sword", "Sharpness", 3));
        sink.accept(new ItemEnchanted(h(), "enchant", "Pick", "Haste", 1));
        sink.accept(new EnchantmentTriggered(h(), "enchant", "Sharpness", "Sword"));
        assertEquals(2, get("enchant:items"));
        assertEquals(1, get("enchant:items:Sharpness"));
        assertEquals(3, get("enchant:highest_level"));
        assertEquals(1, get("enchant:procs:Sharpness"));
        assertTrue(CounterRules.isMax("enchant:highest_level"));

        sink.accept(new VeinMined(h(), "vein", "Ore_Iron", 9));
        sink.accept(new VeinMined(h(), "vein", "Ore_Iron", 4));
        sink.accept(new VeinMined(h(), "vein", "Ore_Iron", 0));
        assertEquals(2, get("vein:veins"));
        assertEquals(13, get("vein:blocks"));
        assertEquals(13, get("vein:blocks:Ore_Iron"));
        assertEquals(9, get("vein:largest"));
        assertTrue(CounterRules.isMax("vein:largest"));

        sink.accept(new LuckyDrop(h(), "lucky", "Ore_Gold", "Ore_Gold", 5));
        sink.accept(new LuckyDrop(h(), "lucky", "Ore_Gold", "Ore_Gold", 2));
        assertEquals(2, get("lucky:drops:Ore_Gold"));
        assertEquals(5, get("lucky:streak_best"));
        assertTrue(CounterRules.isMax("lucky:streak_best"));

        sink.accept(new CompanionChanged(h(), "tamework", "c1", "Wolf", CompanionChanged.Change.TAMED, 1));
        sink.accept(new CompanionChanged(h(), "tamework", "c1", "Wolf", CompanionChanged.Change.LEVEL_UP, 4));
        sink.accept(new CompanionChanged(h(), "tamework", "c1", "Wolf", CompanionChanged.Change.BRED, 0));
        sink.accept(new CompanionChanged(h(), "tamework", "c1", "Wolf", CompanionChanged.Change.DIED, 4));
        sink.accept(new CompanionChanged(h(), "tamework", "c1", "Wolf", CompanionChanged.Change.MOUNTED, 4));
        assertEquals(1, get("tamework:tamed:Wolf"));
        assertEquals(1, get("tamework:companion_level_ups"));
        assertEquals(4, get("tamework:companion_level_best"));
        assertEquals(1, get("tamework:bred"));
        assertEquals(1, get("tamework:companions_lost"));
    }

    @Test
    void modKeysAreExternalNotReservedAndRulesKnowTheBests() {
        assertTrue(Keys.isMod("rpg:level"));
        assertFalse(Keys.isMod("rpg"));
        assertFalse(Keys.isMod("kills"));
        assertFalse(Keys.isReserved("rpg:level"));
        assertTrue(Keys.isExternal("rpg:level"));
        assertTrue(CounterRules.isMax("rpg:level"));
        assertTrue(CounterRules.isMax("mmo:skill_level:Mining"));
        assertFalse(CounterRules.isMax("rpg:level_ups"));
        assertFalse(CounterRules.isMax("coinage:level"));
        assertEquals("rpg:level:x", ModKeys.key("rpg", "level", "x"));
        assertEquals("classes", ModKeys.plural("class"));
        assertEquals("bounties", ModKeys.plural("bounty"));
        assertEquals("quests", ModKeys.plural("quest"));
        assertEquals("races", ModKeys.plural("race"));
        assertEquals("days", ModKeys.plural("day"));
    }
}
