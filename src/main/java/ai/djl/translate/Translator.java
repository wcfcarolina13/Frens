package ai.djl.translate;

import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;

/** Minimal Translator interface stub. */
public interface Translator<I, O> {

    default void prepare(NDManager manager, ai.djl.Model model) throws Exception {}

    NDList processInput(TranslatorContext ctx, I input) throws Exception;

    O processOutput(TranslatorContext ctx, NDList list) throws Exception;

    default Batchifier getBatchifier() { return null; }
}
