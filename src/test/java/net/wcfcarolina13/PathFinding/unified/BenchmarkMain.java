package net.wcfcarolina13.PathFinding.unified;

/**
 * Prints the unified finder's scenario table (design section 10) for a quick look while tuning.
 * Run from the test source set, e.g. an IDE run configuration on this class.
 * {@code --moves} also prints every plan's moves.
 */
public final class BenchmarkMain {
    private BenchmarkMain() {
    }

    public static void main(String[] args) {
        boolean moves = args.length > 0 && args[0].equals("--moves");
        System.out.println(BenchmarkScenarios.header());
        for (BenchmarkScenarios.Case c : BenchmarkScenarios.all()) {
            BenchmarkScenarios.Row row = BenchmarkScenarios.run(c);
            System.out.println(BenchmarkScenarios.format(row));
            if (!row.exec().executable()) {
                System.out.println("    exec: " + row.exec());
            }
            if (moves) {
                for (PlannedMove m : row.plan().moves()) {
                    System.out.println("    " + m);
                }
            }
        }
    }
}
