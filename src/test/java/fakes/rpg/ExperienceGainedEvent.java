package fakes.rpg;

/**
 * The mutable XP event. A NaN amount makes {@link #getXpAmount()} throw, so a test can see the binding guard a
 * callback that fails.
 */
public final class ExperienceGainedEvent {
    private final FakePlayerRef player;
    private double xpAmount;
    private final XPSource source;
    private boolean cancelled;
    private final Object sourceContext;

    public ExperienceGainedEvent(FakePlayerRef player, double xpAmount, XPSource source, Object sourceContext) {
        this.player = player;
        this.xpAmount = xpAmount;
        this.source = source;
        this.sourceContext = sourceContext;
    }

    public FakePlayerRef getPlayer() {
        return player;
    }

    public double getXpAmount() {
        if (Double.isNaN(xpAmount)) {
            throw new IllegalStateException("the amount is not a number");
        }
        return xpAmount;
    }

    public void setXpAmount(double xpAmount) {
        this.xpAmount = xpAmount;
    }

    public XPSource getSource() {
        return source;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    public Object getSourceContext() {
        return sourceContext;
    }
}
