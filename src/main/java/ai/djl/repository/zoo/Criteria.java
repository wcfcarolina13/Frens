package ai.djl.repository.zoo;

import java.nio.file.Path;

/** Minimal compile-time stub for DJL Criteria. Provides a fluent builder surface used by the project. */
public class Criteria<T, R> {

    public static Criteria builder() {
        return new Criteria();
    }

    public Criteria setTypes(Class<?> input, Class<?> output) {
        return this;
    }

    public Criteria optModelPath(Path path) {
        return this;
    }

    public Criteria optEngine(String engine) {
        return this;
    }

    public Criteria optTranslator(Object translator) {
        return this;
    }

    public Criteria optProgress(Object progress) {
        return this;
    }

    public Criteria build() {
        return this;
    }

    public ZooModel loadModel() {
        return new ZooModel();
    }
}
