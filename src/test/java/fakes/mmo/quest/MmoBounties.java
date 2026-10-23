package fakes.mmo.quest;

/** The bounty test of the fake mod. Quest ids starting with "bounty" are bounties. */
public final class MmoBounties {
    private MmoBounties() {
    }

    public static boolean isBounty(String questId) {
        return questId != null && questId.startsWith("bounty");
    }
}
