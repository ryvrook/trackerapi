package fakes.endgame.plugin.components;

import fakes.endgame.plugin.config.BestiaryData;
import fakes.endgame.plugin.config.RuneCollectionState;

/**
 * The per player component of the fake mod, with the three reads the binding seeds from. A combo personal best
 * below zero makes its getter throw, so a test can see a failing seed guarded.
 */
public class PlayerEndgameComponent {
    private BestiaryData.PlayerBestiaryState bestiaryState = new BestiaryData.PlayerBestiaryState();
    private RuneCollectionState runeCollection = new RuneCollectionState();
    private int comboPersonalBest;

    public PlayerEndgameComponent() {
    }

    public BestiaryData.PlayerBestiaryState getBestiaryState() {
        return bestiaryState;
    }

    public void setBestiaryState(BestiaryData.PlayerBestiaryState state) {
        this.bestiaryState = state;
    }

    public RuneCollectionState getRuneCollection() {
        return runeCollection;
    }

    public int getComboPersonalBest() {
        if (comboPersonalBest < 0) {
            throw new IllegalStateException("combo best is broken");
        }
        return comboPersonalBest;
    }

    public void setComboPersonalBest(int best) {
        this.comboPersonalBest = best;
    }
}
