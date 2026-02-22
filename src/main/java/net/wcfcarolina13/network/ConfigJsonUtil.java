package net.wcfcarolina13.network;

public final class ConfigJsonUtil {
    private ConfigJsonUtil() {}

    // Minimal stub to satisfy compile; implement real validation later.
    public static boolean isValidJson(String s) {
        return s != null && !s.isBlank();
    }

    // Return a JSON representation of the current config. Stubbed for compile-time.
    public static String configToJson() {
        try {
            // Prefer to serialize existing config if available (non-invasive).
            if (net.wcfcarolina13.Frens.CONFIG != null) {
                return net.wcfcarolina13.Frens.CONFIG.toString();
            }
        } catch (Throwable ignored) {}
        return "{}";
    }

    public static void applyConfigJson(String json) {
        if (json == null || json.isBlank()) return;
        try {
            // Best-effort: try to apply to the loaded ManualConfig if available.
            if (net.wcfcarolina13.Frens.CONFIG != null) {
                // The real implementation would parse/merge the JSON; here we just log it.
                net.wcfcarolina13.Frens.LOGGER.info("applyConfigJson called (stub): {}", json);
            }
        } catch (Throwable t) {
            // swallow in stub
        }
    }
}
