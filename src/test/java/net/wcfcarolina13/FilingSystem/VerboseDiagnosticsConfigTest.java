package net.wcfcarolina13.FilingSystem;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerboseDiagnosticsConfigTest {
    @Test
    void defaultsToFalseForNewAndExistingConfigs() throws Exception {
        var constructor = ManualConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertFalse(constructor.newInstance().isVerboseDiagnostics());
        assertFalse(new Gson().fromJson("{}", ManualConfig.class).isVerboseDiagnostics());
    }

    @Test
    void verboseDiagnosticsRoundTripsThroughConfigJson() {
        Gson gson = new Gson();
        ManualConfig config = gson.fromJson("{}", ManualConfig.class);
        config.setVerboseDiagnostics(true);
        String json = gson.toJson(config);
        assertTrue(gson.fromJson(json, com.google.gson.JsonObject.class)
                .get("verboseDiagnostics").getAsBoolean());
        ManualConfig restored = gson.fromJson(json, ManualConfig.class);
        assertTrue(restored.isVerboseDiagnostics());
        restored.setVerboseDiagnostics(false);
        assertFalse(gson.fromJson(gson.toJson(restored), ManualConfig.class).isVerboseDiagnostics());
    }
}
