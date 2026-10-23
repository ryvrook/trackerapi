package fakes.arcanum.cad.casting;

import com.hypixel.hytale.component.system.EcsEvent;

/** The fake outcome event, the real factories and accessors. */
public final class CadCastOutcomeEvent extends EcsEvent {
    public enum Stage { PREPARATION, COMPLETION }

    public enum Outcome { SUCCESS, FAILURE }

    private final Stage stage;
    private final Outcome outcome;

    private CadCastOutcomeEvent(Stage stage, Outcome outcome) {
        this.stage = stage;
        this.outcome = outcome;
    }

    public static CadCastOutcomeEvent preparationFailure() {
        return new CadCastOutcomeEvent(Stage.PREPARATION, Outcome.FAILURE);
    }

    public static CadCastOutcomeEvent completionSuccess() {
        return new CadCastOutcomeEvent(Stage.COMPLETION, Outcome.SUCCESS);
    }

    public static CadCastOutcomeEvent completionFailure() {
        return new CadCastOutcomeEvent(Stage.COMPLETION, Outcome.FAILURE);
    }

    public Stage stage() {
        return stage;
    }

    public Outcome outcome() {
        return outcome;
    }
}
