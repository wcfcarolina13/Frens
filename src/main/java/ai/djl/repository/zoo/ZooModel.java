package ai.djl.repository.zoo;

import ai.djl.inference.Predictor;
import ai.djl.ndarray.NDManager;

/** Minimal ZooModel stub to satisfy compile-time usage. Extends ai.djl.Model so it can be passed to translators. */
public class ZooModel<T, R> extends ai.djl.Model {

    public Predictor<T, R> newPredictor() {
        return new Predictor<>();
    }

    public void close() {
        // no-op
    }

    public NDManager getNDManager() {
        return new NDManager();
    }
}
