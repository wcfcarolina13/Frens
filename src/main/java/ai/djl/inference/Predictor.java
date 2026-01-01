package ai.djl.inference;

/** Minimal Predictor stub. */
public class Predictor<T, R> implements AutoCloseable {

    public Predictor() {}

    public R predict(T input) throws Exception {
        return null;
    }

    @Override
    public void close() {
        // no-op
    }
}
