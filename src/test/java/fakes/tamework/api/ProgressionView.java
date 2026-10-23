package fakes.tamework.api;

import java.util.List;
import java.util.UUID;

/** The progression of one companion. The nested views the binding does not read are kept short. */
public record ProgressionView(String profileId, UUID npcUuid, String worldName, String roleId, HappinessView happiness, NeedsView needs,
                              BreedingView breeding, LevelingView leveling, LifeStageView lifeStage, TraitsView traits, TalentsView talents,
                              AttachmentsView attachments) {

    public record LevelingView(String configId, int level, double currentXp, double totalXp, double nextLevelXp, int maxLevel, boolean atMaxLevel) {
    }

    public record HappinessView(String configId, double value, double min, double max) {
    }

    public record NeedsView(String configId, double hunger, double thirst) {
    }

    public record BreedingView(String configId, boolean enabled, boolean readyNow) {
    }

    public record LifeStageView(String stage, boolean adult, String gender) {
    }

    public record TraitsView(String configId, long rollSeed, List<String> values) {
    }

    public record TalentsView(String configId, int availablePoints, int spentPoints, List<String> purchasedTalentIds) {
    }

    public record AttachmentsView(String configId) {
    }
}
