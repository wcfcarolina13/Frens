package net.wcfcarolina13.GameAI.services;

/** Outcome kind of a leaf-obstruction clear attempt (see {@link LeafClearService}). */
public enum LeafClearAction {
    NO_OBSTRUCTION,
    BYPASS_STEP,
    BYPASS_PROGRESS,
    MINING_STARTED,
    MINING_IN_PROGRESS,
    NO_CLEAR
}
