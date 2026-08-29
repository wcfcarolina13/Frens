package net.wcfcarolina13.GameAI.services.dialogue;

import net.wcfcarolina13.FilingSystem.ManualConfig;
import net.wcfcarolina13.Frens;

import java.util.Locale;

/**
 * One knob per dialogue stream. A rate of 0–100 (50 = the shipped cadence) becomes a cooldown
 * multiplier {@code 4^((50 - rate) / 50)}: 0 → ×4 (rarer), 100 → ×0.25 (chattier). Cooldowns
 * multiply by it, per-tick chances divide by it. Pure functions take the rate explicitly; the
 * {@link Stream} overloads read the live {@link ManualConfig} lazily at call time so a slider
 * change applies to the next line without any reload. The souls package must NOT call the
 * Stream overloads (it never references Frens) — directors receive an IntSupplier instead.
 */
public final class DialoguePacing {

    public enum Stream { SCRIPTED, BANTER_IDLE, BANTER_ACTIVE, LOCAL }

    public static final int DEFAULT_RATE = 50;

    private DialoguePacing() {
    }

    public static double multiplier(int rate) {
        int r = Math.max(0, Math.min(100, rate));
        return Math.pow(4.0, (50 - r) / 50.0);
    }

    public static long scaledCooldown(int rate, long baseMs) {
        return Math.round(baseMs * multiplier(rate));
    }

    public static double scaledChance(int rate, double base) {
        return Math.max(0.0, Math.min(1.0, base / multiplier(rate)));
    }

    /** "every ~8–15 min" / "every ~15–30 s" for the settings screen captions. */
    public static String describe(int rate, long minMs, long maxMs) {
        double m = multiplier(rate);
        long lo = Math.round(minMs * m);
        long hi = Math.round(maxMs * m);
        if (hi < 120_000L) {
            return String.format(Locale.ROOT, "every ~%d–%d s", Math.round(lo / 1000.0), Math.round(hi / 1000.0));
        }
        return String.format(Locale.ROOT, "every ~%d–%d min", Math.round(lo / 60_000.0), Math.round(hi / 60_000.0));
    }

    public static int rate(Stream stream) {
        ManualConfig cfg = Frens.CONFIG;
        if (cfg == null) {
            return DEFAULT_RATE;
        }
        return switch (stream) {
            case SCRIPTED -> cfg.getDialogueScriptedRate();
            case BANTER_IDLE -> cfg.getSoulBanterIdleRate();
            case BANTER_ACTIVE -> cfg.getSoulBanterActiveRate();
            case LOCAL -> cfg.getSoulLocalRate();
        };
    }

    public static long scaledCooldown(Stream stream, long baseMs) {
        return scaledCooldown(rate(stream), baseMs);
    }

    public static double scaledChance(Stream stream, double base) {
        return scaledChance(rate(stream), base);
    }
}
