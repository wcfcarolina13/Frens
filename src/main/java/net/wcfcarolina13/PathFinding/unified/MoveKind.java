package net.wcfcarolina13.PathFinding.unified;

/** Every move the unified finder plans. The executor switches over this with no default branch. */
public enum MoveKind {
    TRAVERSE, DIAGONAL, ASCEND, DESCEND, FALL, ASCEND_PLACE, SWIM, DOWNWARD
}
