package net.wcfcarolina13.PathFinding.unified;

/** One search state: a feet cell plus the swim run (part of the key on water cells only). */
public final class SearchNode {
    public final int x;
    public final int y;
    public final int z;
    public final int swimRun;
    public final long packedKey;
    public int scaffoldLeft;
    public SearchNode parent;
    /** The move that reached this node on its best known path; null on the start node. */
    public PlannedMove move;
    public double g;
    public double h;
    public double f;
    /** Index in {@link BinaryHeapOpenSet}, -1 when not in the heap. */
    public int heapIndex = -1;

    public SearchNode(int x, int y, int z, int swimRun, long packedKey) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.swimRun = swimRun;
        this.packedKey = packedKey;
    }

    public boolean isOpen() {
        return heapIndex >= 0;
    }
}
