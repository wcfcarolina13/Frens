package ai.djl.modality;

import java.util.Collections;
import java.util.List;

/** Minimal Classifications stub used by project translators. */
public class Classifications {

    public static class Classification {
        private final String className;
        private final double probability;

        public Classification(String className, double probability) {
            this.className = className;
            this.probability = probability;
        }

        public String getClassName() { return className; }
        public double getProbability() { return probability; }
    }

    public Classifications(List<String> labels, float[] logits) {}
    public Classifications(List<String> labels, double[] probs) {}
    public Classifications(List<String> labels, ai.djl.ndarray.NDArray array) {}

    public Classification best() { return new Classification("", 0.0); }

    public List<Classification> topK(int k) { return Collections.emptyList(); }
}
