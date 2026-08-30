package net.wcfcarolina13.GameAI.souls;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What changed since this audience's last scene (conversation ontology Phase 1, 2026-08-29).
 * Real conversation is driven by what is DIFFERENT, not by what is there: the rain stopping,
 * crossing into a new biome, the first wolves anyone has seen, a bot taking a hit, coming home.
 * Pure: compares two groundings plus a per-audience seen-registry and yields seed anchors at
 * {@link #CHANGE_WEIGHT} — above every static grounding anchor, below a HIGH-salience event.
 *
 * <p>The registry is mutated: keys visible now are added after the first-seen check, so the
 * FIRST call for an audience (no previous grounding) reports first sightings only and primes
 * the registry for genuine novelty from then on.
 *
 * <p>Phase 2: the registry passed in is the union of the roster's persisted {@code mind.json}
 * seen-sets ({@link SoulTypes.SoulMind#seen()}), not a per-audience in-memory set — "the first
 * wolves any of you have seen" now survives a relaunch and holds across audiences. The caller
 * writes the mutated keys back to every roster member's mind ({@link SoulMindOps#withSeen}).
 */
final class SoulSceneDiff {

    static final int CHANGE_WEIGHT = 5;
    static final int HEALTH_DROP_THRESHOLD = 6;
    static final int HUNGRY_BELOW = 8;

    private SoulSceneDiff() {
    }

    static List<SoulBanterSeed.Anchor> diff(SoulTypes.GroundingSnapshot previous,
                                            SoulTypes.GroundingSnapshot current,
                                            Set<String> seen, String playerName) {
        List<SoulBanterSeed.Anchor> out = new ArrayList<>();
        if (current == null) {
            return out;
        }
        SoulTypes.BotSnapshot bot = current.bot();
        SoulTypes.SituationSnapshot situation = current.situation();
        String who = bot.name().isEmpty() ? "someone" : bot.name();
        String player = current.player().map(SoulTypes.PlayerSnapshot::name)
                .filter(n -> !n.isEmpty()).orElse(playerName == null ? "the player" : playerName);

        // First sightings — checked against the registry BEFORE this scene's keys are added.
        for (String animal : situation.nearbyAnimals()) {
            if (seen.add("animal:" + animal.toLowerCase(Locale.ROOT))) {
                out.add(change("animals", "the first " + animal + " any of you have seen"));
            }
        }
        for (String facility : situation.facilities()) {
            String name = facilityName(facility);
            if (!name.isEmpty() && seen.add("facility:" + name.toLowerCase(Locale.ROOT))) {
                out.add(change("facilities", "the " + name + " here, a new sight"));
            }
        }
        if (!bot.biome().isEmpty() && seen.add("biome:" + bot.biome().toLowerCase(Locale.ROOT)) && previous != null) {
            out.add(change("the land", "the first time in " + bot.biome()));
        }

        if (previous == null) {
            return out;
        }
        SoulTypes.BotSnapshot was = previous.bot();
        SoulTypes.SituationSnapshot before = previous.situation();

        if (!bot.weather().equalsIgnoreCase(was.weather()) && !bot.weather().isEmpty()) {
            String now = bot.weather().toLowerCase(Locale.ROOT);
            out.add(change("the weather", now.contains("clear")
                    ? "the " + was.weather().toLowerCase(Locale.ROOT).replace("ing", "") + " just stopped"
                    : "it just started " + now));
        }
        if (!bot.timePhase().equalsIgnoreCase(was.timePhase()) && !bot.timePhase().isEmpty()) {
            String phase = bot.timePhase().toLowerCase(Locale.ROOT);
            out.add(change("the hour", phase.contains("night") ? "night has fallen"
                    : phase.contains("dawn") || phase.contains("morning") ? "dawn is breaking"
                    : "it is " + phase + " now"));
        }
        if (!bot.biome().equalsIgnoreCase(was.biome()) && !bot.biome().isEmpty()) {
            out.add(change("the land", "you have just crossed into " + bot.biome()));
        }
        if (before.hostiles().isEmpty() && !situation.hostiles().isEmpty()) {
            out.add(change("danger", "hostiles just showed up (" + situation.hostiles().get(0).name() + ")"));
        } else if (!before.hostiles().isEmpty() && situation.hostiles().isEmpty()) {
            out.add(change("danger", "the hostiles are gone"));
        }
        if (was.health() - bot.health() >= HEALTH_DROP_THRESHOLD) {
            out.add(change("health", who + " just took a hit (" + Math.round(bot.health()) + "/" + Math.round(bot.maxHealth()) + ")"));
        }
        if (was.hunger() >= HUNGRY_BELOW && bot.hunger() < HUNGRY_BELOW) {
            out.add(change("food", who + " is getting hungry"));
        }
        if (!bot.heldItem().equalsIgnoreCase(was.heldItem()) && !bot.heldItem().isEmpty()) {
            out.add(change("gear", who + " switched to " + bot.heldItem()));
        }
        String playerHeld = current.player().map(SoulTypes.PlayerSnapshot::heldItem).orElse("");
        String playerHeldBefore = previous.player().map(SoulTypes.PlayerSnapshot::heldItem).orElse("");
        if (!playerHeld.isEmpty() && !playerHeld.equalsIgnoreCase(playerHeldBefore)) {
            out.add(change("the player", player + " is holding " + playerHeld + " now"));
        }
        if (situation.atBase().isPresent() && before.atBase().isEmpty()) {
            out.add(change("home", "you are back at " + situation.atBase().get()));
        } else if (situation.atBase().isEmpty() && before.atBase().isPresent()) {
            out.add(change("home", "you have left " + before.atBase().get() + " behind"));
        }
        if (situation.mount().isPresent() && before.mount().isEmpty()) {
            out.add(change("the mount", who + " is riding a " + situation.mount().get().type() + " now"));
        } else if (situation.mount().isEmpty() && before.mount().isPresent()) {
            out.add(change("the mount", who + " is on foot again"));
        }
        if (situation.deathCount() > before.deathCount() && before.deathCount() >= 0) {
            out.add(change("dying", who + " died since you last talked"));
        }
        return out;
    }

    /** "3x chest (storage)" → "chest"; "campfire (cook food)" → "campfire". */
    static String facilityName(String facilityLine) {
        String s = facilityLine == null ? "" : facilityLine.trim();
        int paren = s.indexOf('(');
        if (paren >= 0) {
            s = s.substring(0, paren).trim();
        }
        int x = s.indexOf("x ");
        if (x > 0 && s.substring(0, x).chars().allMatch(Character::isDigit)) {
            s = s.substring(x + 2).trim();
        }
        return s;
    }

    private static SoulBanterSeed.Anchor change(String topic, String phrase) {
        return new SoulBanterSeed.Anchor(topic, phrase, CHANGE_WEIGHT);
    }
}
