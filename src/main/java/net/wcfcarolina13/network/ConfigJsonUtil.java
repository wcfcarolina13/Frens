package net.wcfcarolina13.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.wcfcarolina13.FilingSystem.ManualConfig;
import net.wcfcarolina13.FilingSystem.SharedConfig;

/**
 * Serializes and merges the shared subset of {@link ManualConfig} (see {@link SharedConfig}).
 * Never touches API keys or other host-local settings.
 */
public final class ConfigJsonUtil {

    private static final Gson GSON = new GsonBuilder().create();

    private ConfigJsonUtil() {}

    /** True when {@code s} parses as a JSON object. */
    public static boolean isValidJson(String s) {
        return parseObject(s) != null;
    }

    /** Snapshot of {@code Frens.CONFIG}; {@code "{}"} when it is unavailable. */
    public static String configToJson() {
        ManualConfig config = null;
        try {
            config = net.wcfcarolina13.Frens.CONFIG;
        } catch (Throwable ignored) {
            // Frens may not be initialised (unit tests, datagen) — fall through to "{}".
        }
        return configToJson(config);
    }

    /** Snapshot of {@code config} as JSON; {@code "{}"} when {@code config} is null. */
    public static String configToJson(ManualConfig config) {
        if (config == null) {
            return "{}";
        }
        return GSON.toJson(SharedConfig.capture(config));
    }

    /** Merges {@code json} onto {@code Frens.CONFIG}. Returns false when nothing was applied. */
    public static boolean applyConfigJson(String json) {
        ManualConfig config = null;
        try {
            config = net.wcfcarolina13.Frens.CONFIG;
        } catch (Throwable ignored) {
            // Frens may not be initialised — treated as "no target".
        }
        return applyConfigJson(json, config);
    }

    /**
     * Merges the shared fields carried by {@code json} onto {@code target}. Fields absent from
     * the JSON keep the target's current value.
     *
     * @return true when the merge ran; false (target untouched, WARN logged) on a null target
     *         or malformed / non-object JSON.
     */
    public static boolean applyConfigJson(String json, ManualConfig target) {
        if (target == null) {
            warn("applyConfigJson: no target config, ignoring payload");
            return false;
        }
        JsonObject object = parseObject(json);
        if (object == null) {
            warn("applyConfigJson: malformed config JSON, ignoring payload");
            return false;
        }
        try {
            SharedConfig snapshot = GSON.fromJson(object, SharedConfig.class);
            if (snapshot == null) {
                warn("applyConfigJson: config JSON deserialized to null, ignoring payload");
                return false;
            }
            snapshot.applyTo(target);
            return true;
        } catch (RuntimeException e) {
            warn("applyConfigJson: failed to apply config JSON: " + e);
            return false;
        }
    }

    private static JsonObject parseObject(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            var element = JsonParser.parseString(json);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void warn(String message) {
        try {
            net.wcfcarolina13.Frens.LOGGER.warn(message);
        } catch (Throwable ignored) {
            // Logger unavailable outside a running mod environment.
        }
    }
}
