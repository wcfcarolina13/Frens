package net.shasankp000.GameAI.services;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvent;
import net.shasankp000.ChatUtils.BotDialoguePlayer;
import net.shasankp000.ChatUtils.BotDialogueSounds;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.GameAI.BotEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles combat-related callouts for bots during actual fights.
 * 
 * <p>Provides callouts for:
 * <ul>
 *   <li>Threat detection - when hostiles are first spotted</li>
 *   <li>Engagement - when the bot starts attacking</li>
 *   <li>Taking damage - when the bot is hit (by mobs, not player)</li>
 *   <li>Kill confirmation - when the bot defeats an enemy</li>
 *   <li>Combat end - when all threats are cleared</li>
 *   <li>Player hit reaction - when player hits the bot (different from touch)</li>
 * </ul>
 * 
 * <p>All callouts have cooldowns to prevent spam.
 */
public final class BotCombatCalloutService {
    
    private static final Logger LOGGER = LoggerFactory.getLogger("combat-callout");
    private static final Random RNG = new Random();
    
    // Cooldowns in milliseconds
    private static final long THREAT_DETECT_COOLDOWN_MS = 15_000L;  // 15 seconds
    private static final long ENGAGEMENT_COOLDOWN_MS = 20_000L;     // 20 seconds
    private static final long DAMAGE_COOLDOWN_MS = 15_000L;         // 15 seconds
    private static final long KILL_COOLDOWN_MS = 10_000L;           // 10 seconds
    private static final long COMBAT_END_COOLDOWN_MS = 30_000L;     // 30 seconds
    private static final long PLAYER_HIT_COOLDOWN_MS = 5_000L;      // 5 seconds

    // Nonverbal hurt grunts should be frequent but not spammy.
    private static final long HURT_GRUNT_COOLDOWN_MS = 900L;         // ~1 second
    
    // Track last callout times per bot
    private static final ConcurrentHashMap<UUID, Long> LAST_THREAT_DETECT_MS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> LAST_ENGAGEMENT_MS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> LAST_DAMAGE_MS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> LAST_KILL_MS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> LAST_COMBAT_END_MS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> LAST_PLAYER_HIT_MS = new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<UUID, Long> LAST_HURT_GRUNT_MS = new ConcurrentHashMap<>();
    
    // Track if bot is in combat (for combat end detection)
    private static final ConcurrentHashMap<UUID, Boolean> IN_COMBAT = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> LAST_COMBAT_ACTIVITY_MS = new ConcurrentHashMap<>();
    
    private BotCombatCalloutService() {}

    /**
     * Lightweight combat bookkeeping hook.
     *
     * <p>Some systems (e.g., threat scanning) detect that hostiles are present every tick, even when
     * we don't want to emit a voiced line. This method lets callers extend the "combat activity"
     * window so {@link #checkCombatEnd(ServerPlayerEntity)} doesn't end combat too early.
     */
    public static void noteCombatOngoing(ServerPlayerEntity bot, boolean hostilesPresent) {
        if (!canCallout(bot)) {
            return;
        }
        if (!hostilesPresent) {
            // Don't clear combat immediately; allow checkCombatEnd() to decide based on inactivity.
            return;
        }
        UUID botId = bot.getUuid();
        long now = System.currentTimeMillis();
        IN_COMBAT.put(botId, true);
        LAST_COMBAT_ACTIVITY_MS.put(botId, now);
    }
    
    /**
     * Called when the bot detects a hostile entity nearby.
     * Only triggers if the bot wasn't already in combat recently.
     * Uses existing "Something moved." audio which fits well for threat detection.
     */
    public static void onThreatDetected(ServerPlayerEntity bot, Entity threat) {
        if (!canCallout(bot)) return;
        
        long now = System.currentTimeMillis();
        UUID botId = bot.getUuid();
        
        // Don't callout if already in combat
        if (IN_COMBAT.getOrDefault(botId, false)) return;
        
        if (!checkCooldown(botId, LAST_THREAT_DETECT_MS, THREAT_DETECT_COOLDOWN_MS, now)) return;
        
        // Enter combat state
        IN_COMBAT.put(botId, true);
        LAST_COMBAT_ACTIVITY_MS.put(botId, now);
        
        // Use existing voiced audio that fits threat detection
        String line = "Something moved.";
        sayWithSound(bot, line, BotDialogueSounds.LINE_AMBIENT_SOMETHING_MOVED);
        LOGGER.debug("Bot {} detected threat: {}", bot.getName().getString(), getEntityName(threat));
    }
    
    /**
     * Called when the bot starts attacking an enemy.
     * Uses existing "Engaging threats against allies" audio.
     */
    public static void onEngagement(ServerPlayerEntity bot, Entity target) {
        if (!canCallout(bot)) return;
        
        long now = System.currentTimeMillis();
        UUID botId = bot.getUuid();
        
        if (!checkCooldown(botId, LAST_ENGAGEMENT_MS, ENGAGEMENT_COOLDOWN_MS, now)) return;
        
        LAST_COMBAT_ACTIVITY_MS.put(botId, now);
        IN_COMBAT.put(botId, true);
        
        // Use existing voiced audio
        String line = "Engaging threats against allies.";
        sayWithSound(bot, line, BotDialogueSounds.LINE_COMBAT_ENGAGING);
    }
    
    /**
     * Called when the bot takes damage from a mob (not player).
     * Uses existing health/warning sounds that already have audio.
     */
    public static void onDamageTaken(ServerPlayerEntity bot, Entity attacker, float amount) {
        if (!canCallout(bot)) return;

        long now = System.currentTimeMillis();
        UUID botId = bot.getUuid();

        // Nonverbal feedback for being hit (no chat fallback).
        tryPlayHurtGrunt(bot, botId, now, amount);
        
        if (!checkCooldown(botId, LAST_DAMAGE_MS, DAMAGE_COOLDOWN_MS, now)) return;
        
        LAST_COMBAT_ACTIVITY_MS.put(botId, now);
        IN_COMBAT.put(botId, true);
        
        // Use existing voiced sounds for taking damage, with some variety.
        SoundEvent sound;
        String line;

        if (bot.getHealth() <= bot.getMaxHealth() * 0.3f) {
            // Low health - more urgent (use existing audio)
            line = "I'm a bit banged up. Maybe we should take it easy.";
            sound = BotDialogueSounds.LINE_WARNING_BANGED_UP;
        } else {
            // Normal damage: rotate among existing "status" lines so we don't overuse one clip.
            int pick = RNG.nextInt(3);
            if (pick == 0) {
                line = "I've taken a few too many hits today.";
                sound = BotDialogueSounds.LINE_STATUS_TOO_MANY_HITS;
            } else if (pick == 1) {
                line = "I could use a breather.";
                sound = BotDialogueSounds.LINE_STATUS_NEED_BREATHER;
            } else {
                line = "Not feeling my best right now.";
                sound = BotDialogueSounds.LINE_STATUS_NOT_BEST;
            }
        }

        sayWithSound(bot, line, sound);
    }
    
    /**
     * Called when the bot kills an enemy.
     */
    public static void onKill(ServerPlayerEntity bot, Entity killed) {
        if (!canCallout(bot)) return;
        
        long now = System.currentTimeMillis();
        UUID botId = bot.getUuid();
        
        if (!checkCooldown(botId, LAST_KILL_MS, KILL_COOLDOWN_MS, now)) return;
        
        LAST_COMBAT_ACTIVITY_MS.put(botId, now);
        
        KillCallout callout = pickKillCallout(killed);
        sayWithSound(bot, callout.line, callout.sound);
    }

    private static final class KillCallout {
        final String line;
        final SoundEvent sound;

        KillCallout(String line, SoundEvent sound) {
            this.line = line;
            this.sound = sound;
        }
    }
    
    /**
     * Called periodically to check if combat has ended.
     * Uses existing "Standing down unless attacked" audio.
     */
    public static void checkCombatEnd(ServerPlayerEntity bot) {
        if (!canCallout(bot)) return;
        
        UUID botId = bot.getUuid();
        if (!IN_COMBAT.getOrDefault(botId, false)) return;
        
        long now = System.currentTimeMillis();
        long lastActivity = LAST_COMBAT_ACTIVITY_MS.getOrDefault(botId, 0L);
        
        // Combat ends after a short quiet period. 5s was too aggressive for ranged fights (skeletons).
        if (now - lastActivity < 10_000L) return;
        
        if (!checkCooldown(botId, LAST_COMBAT_END_MS, COMBAT_END_COOLDOWN_MS, now)) {
            IN_COMBAT.put(botId, false);
            return;
        }
        
        IN_COMBAT.put(botId, false);
        
        // Use existing voiced audio
        String line = "Standing down unless attacked.";
        sayWithSound(bot, line, BotDialogueSounds.LINE_COMBAT_STANDING_DOWN);
        LOGGER.debug("Bot {} combat ended", bot.getName().getString());
    }
    
    /**
     * Called when the player hits the bot (not a friendly touch!).
     * This uses different dialogue than the touch system.
     */
    public static void onPlayerHit(ServerPlayerEntity bot, ServerPlayerEntity attacker, float damage) {
        if (!canCallout(bot)) return;
        
        long now = System.currentTimeMillis();
        UUID botId = bot.getUuid();

        // If we're going to do a worded player-hit reaction, don't also grunt.
        if (!checkCooldown(botId, LAST_PLAYER_HIT_MS, PLAYER_HIT_COOLDOWN_MS, now)) {
            tryPlayHurtGrunt(bot, botId, now, damage);
            return;
        }
        
        String attackerName = attacker.getName().getString();
        String line = pickPlayerHitLine(attackerName, damage);
        
        sayWithSound(bot, line, BotDialogueSounds.LINE_COMBAT_PLAYER_HIT);
    }
    
    // ========== Helper methods ==========
    
    private static boolean canCallout(ServerPlayerEntity bot) {
        if (bot == null || bot.isRemoved()) return false;
        if (!BotEventHandler.isRegisteredBot(bot)) return false;
        return true;
    }
    
    private static boolean checkCooldown(UUID botId, ConcurrentHashMap<UUID, Long> lastMap, 
                                          long cooldownMs, long now) {
        long last = lastMap.getOrDefault(botId, 0L);
        if (now - last < cooldownMs) return false;
        lastMap.put(botId, now);
        return true;
    }
    
    private static void sayWithSound(ServerPlayerEntity bot, String line, SoundEvent sound) {
        BotDialoguePlayer.PlayResult res = BotDialoguePlayer.playSoundForBotDetailed(bot, sound);

        // If the line was throttled, do nothing (avoid falling back to chat spam).
        if (res == BotDialoguePlayer.PlayResult.THROTTLED) {
            return;
        }

        // If voiced dialogue is disabled for this bot, or sound playback failed, fall back to chat.
        if (res == BotDialoguePlayer.PlayResult.DISABLED || res == BotDialoguePlayer.PlayResult.FAILED) {
            ChatUtils.sendChatMessages(
                    bot.getCommandSource().withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS),
                    line,
                    true
            );
        }
    }

    private static void tryPlayHurtGrunt(ServerPlayerEntity bot, UUID botId, long now, float amount) {
        if (bot == null || botId == null) {
            return;
        }
        if (amount <= 0.0f) {
            return;
        }

        // Avoid grunting on ultra-tiny damage ticks.
        if (amount < 0.5f && RNG.nextFloat() < 0.85f) {
            return;
        }

        long last = LAST_HURT_GRUNT_MS.getOrDefault(botId, 0L);
        if (now - last < HURT_GRUNT_COOLDOWN_MS) {
            return;
        }
        LAST_HURT_GRUNT_MS.put(botId, now);

        // Don't force a chat fallback for nonverbal sounds.
        BotDialoguePlayer.playSoundForBotDetailed(bot, BotDialogueSounds.FX_HURT_GRUNT);
    }
    
    private static String getEntityName(Entity entity) {
        if (entity == null) return "something";
        EntityType<?> type = entity.getType();
        return type.getName().getString();
    }
    
    // ========== Line pickers ==========
    
    @SuppressWarnings("unused")
    private static String pickThreatLine(String threatName) {
        return choose(
            "Heads up! " + threatName + "!",
            "Watch out, " + threatName + " incoming!",
            "We've got company—" + threatName + "!",
            threatName + " spotted!",
            "Hostile ahead!"
        );
    }
    
    @SuppressWarnings("unused")
    private static String pickEngagementLine() {
        return choose(
            "Engaging!",
            "I've got this one!",
            "Taking it on!",
            "Fighting!",
            "Here we go!"
        );
    }
    
    @SuppressWarnings("unused")
    private static String pickDamageLine() {
        return choose(
            "Ow! That hurt!",
            "I'm hit!",
            "Taking damage!",
            "Ouch!",
            "They got me!"
        );
    }
    
    @SuppressWarnings("unused")
    private static String pickLowHealthLine() {
        return choose(
            "I'm in trouble here!",
            "Need to back off!",
            "I'm hurt bad!",
            "Getting low!",
            "This isn't good!"
        );
    }
    
    private static KillCallout pickKillCallout(Entity killed) {
        // Use the already-recorded kill variants that exist in sounds.json.
        // We pick a *sound event* so subtitles can be accurate and the clip pool is consistent.
        EntityType<?> type = killed != null ? killed.getType() : null;

        // Creature-specific kill lines: more immersive, but keep some randomness/fallback so it doesn't get too on-the-nose.
        if (type != null) {
            boolean preferSpecific = RNG.nextDouble() < 0.65;

            if (type == EntityType.CREEPER) {
                if (preferSpecific) {
                    return RNG.nextBoolean()
                            ? new KillCallout("Creeper down.", BotDialogueSounds.LINE_COMBAT_KILL_CREEPER_1)
                            : new KillCallout("No boom today.", BotDialogueSounds.LINE_COMBAT_KILL_CREEPER_2);
                }
                // High-stakes mob: even generic fallback should feel decisive.
                return new KillCallout("Target down!", BotDialogueSounds.LINE_COMBAT_KILL_TARGET_DOWN);
            }

            if (type == EntityType.SKELETON || type == EntityType.STRAY || type == EntityType.WITHER_SKELETON) {
                if (preferSpecific) {
                    return RNG.nextBoolean()
                            ? new KillCallout("Skeleton down.", BotDialogueSounds.LINE_COMBAT_KILL_SKELETON_1)
                            : new KillCallout("Bones scattered.", BotDialogueSounds.LINE_COMBAT_KILL_SKELETON_2);
                }
                return new KillCallout("One less to worry about.", BotDialogueSounds.LINE_COMBAT_KILL_ONE_LESS);
            }

            if (type == EntityType.ZOMBIE || type == EntityType.DROWNED || type == EntityType.HUSK || type == EntityType.ZOMBIE_VILLAGER) {
                if (preferSpecific) {
                    return RNG.nextBoolean()
                            ? new KillCallout("Zombie down.", BotDialogueSounds.LINE_COMBAT_KILL_ZOMBIE_1)
                            : new KillCallout("Back in the ground.", BotDialogueSounds.LINE_COMBAT_KILL_ZOMBIE_2);
                }
                return new KillCallout("One less to worry about.", BotDialogueSounds.LINE_COMBAT_KILL_ONE_LESS);
            }

            if (type == EntityType.SPIDER || type == EntityType.CAVE_SPIDER) {
                if (preferSpecific) {
                    return RNG.nextBoolean()
                            ? new KillCallout("Spider down.", BotDialogueSounds.LINE_COMBAT_KILL_SPIDER_1)
                            : new KillCallout("Webs won't save you.", BotDialogueSounds.LINE_COMBAT_KILL_SPIDER_2);
                }
                return new KillCallout("Target down!", BotDialogueSounds.LINE_COMBAT_KILL_TARGET_DOWN);
            }

            if (type == EntityType.ENDERMAN) {
                if (preferSpecific) {
                    return RNG.nextBoolean()
                            ? new KillCallout("Enderman down.", BotDialogueSounds.LINE_COMBAT_KILL_ENDERMAN_1)
                            : new KillCallout("Glad I didn't blink.", BotDialogueSounds.LINE_COMBAT_KILL_ENDERMAN_2);
                }
                return new KillCallout("Got it!", BotDialogueSounds.LINE_COMBAT_KILL_GOT_IT);
            }

            if (type == EntityType.WITCH) {
                if (preferSpecific) {
                    return RNG.nextBoolean()
                            ? new KillCallout("Witch down.", BotDialogueSounds.LINE_COMBAT_KILL_WITCH_1)
                            : new KillCallout("Potion problem solved.", BotDialogueSounds.LINE_COMBAT_KILL_WITCH_2);
                }
                return new KillCallout("Target down!", BotDialogueSounds.LINE_COMBAT_KILL_TARGET_DOWN);
            }

            if (type == EntityType.SLIME) {
                if (preferSpecific) {
                    return RNG.nextBoolean()
                            ? new KillCallout("Slime down.", BotDialogueSounds.LINE_COMBAT_KILL_SLIME_1)
                            : new KillCallout("Back to goo.", BotDialogueSounds.LINE_COMBAT_KILL_SLIME_2);
                }
                return new KillCallout("Got it!", BotDialogueSounds.LINE_COMBAT_KILL_GOT_IT);
            }

            if (type == EntityType.PILLAGER) {
                if (preferSpecific) {
                    return RNG.nextBoolean()
                            ? new KillCallout("Pillager down.", BotDialogueSounds.LINE_COMBAT_KILL_PILLAGER_1)
                            : new KillCallout("One less raider.", BotDialogueSounds.LINE_COMBAT_KILL_PILLAGER_2);
                }
                return new KillCallout("One less to worry about.", BotDialogueSounds.LINE_COMBAT_KILL_ONE_LESS);
            }

            if (type == EntityType.VINDICATOR) {
                if (preferSpecific) {
                    return RNG.nextBoolean()
                            ? new KillCallout("Vindicator down.", BotDialogueSounds.LINE_COMBAT_KILL_VINDICATOR_1)
                            : new KillCallout("Axe is out.", BotDialogueSounds.LINE_COMBAT_KILL_VINDICATOR_2);
                }
                return new KillCallout("Target down!", BotDialogueSounds.LINE_COMBAT_KILL_TARGET_DOWN);
            }

            if (type == EntityType.EVOKER) {
                if (preferSpecific) {
                    return RNG.nextBoolean()
                            ? new KillCallout("Evoker down.", BotDialogueSounds.LINE_COMBAT_KILL_EVOKER_1)
                            : new KillCallout("No more tricks.", BotDialogueSounds.LINE_COMBAT_KILL_EVOKER_2);
                }
                return new KillCallout("Target down!", BotDialogueSounds.LINE_COMBAT_KILL_TARGET_DOWN);
            }

            if (type == EntityType.MAGMA_CUBE) {
                if (preferSpecific) {
                    return RNG.nextBoolean()
                            ? new KillCallout("Magma cube down.", BotDialogueSounds.LINE_COMBAT_KILL_MAGMA_CUBE_1)
                            : new KillCallout("Back to magma.", BotDialogueSounds.LINE_COMBAT_KILL_MAGMA_CUBE_2);
                }
                return new KillCallout("Got it!", BotDialogueSounds.LINE_COMBAT_KILL_GOT_IT);
            }

            if (type == EntityType.RAVAGER) {
                if (preferSpecific) {
                    return RNG.nextBoolean()
                            ? new KillCallout("Ravager down.", BotDialogueSounds.LINE_COMBAT_KILL_RAVAGER_1)
                            : new KillCallout("Beast is down.", BotDialogueSounds.LINE_COMBAT_KILL_RAVAGER_2);
                }
                return new KillCallout("Target down!", BotDialogueSounds.LINE_COMBAT_KILL_TARGET_DOWN);
            }

            if (type == EntityType.VEX) {
                if (preferSpecific) {
                    return RNG.nextBoolean()
                            ? new KillCallout("Vex down.", BotDialogueSounds.LINE_COMBAT_KILL_VEX_1)
                            : new KillCallout("No more flying blades.", BotDialogueSounds.LINE_COMBAT_KILL_VEX_2);
                }
                return new KillCallout("Got it!", BotDialogueSounds.LINE_COMBAT_KILL_GOT_IT);
            }
        }

        // Bias based on common "feel" per creature type.
        if (type == EntityType.CREEPER) {
            // Creepers are "high stakes": lean into the more explicit confirmation.
            return RNG.nextDouble() < 0.75
                    ? new KillCallout("Target down!", BotDialogueSounds.LINE_COMBAT_KILL_TARGET_DOWN)
                    : new KillCallout("Got it!", BotDialogueSounds.LINE_COMBAT_KILL_GOT_IT);
        }

        if (type == EntityType.SKELETON
                || type == EntityType.STRAY
                || type == EntityType.WITHER_SKELETON
                || type == EntityType.ZOMBIE
                || type == EntityType.DROWNED
                || type == EntityType.HUSK
                || type == EntityType.ZOMBIE_VILLAGER) {
            return RNG.nextDouble() < 0.70
                    ? new KillCallout("One less to worry about.", BotDialogueSounds.LINE_COMBAT_KILL_ONE_LESS)
                    : new KillCallout("Target down!", BotDialogueSounds.LINE_COMBAT_KILL_TARGET_DOWN);
        }

        // Default: roughly even split.
        double r = RNG.nextDouble();
        if (r < 0.34) {
            return new KillCallout("Got it!", BotDialogueSounds.LINE_COMBAT_KILL_GOT_IT);
        }
        if (r < 0.67) {
            return new KillCallout("Target down!", BotDialogueSounds.LINE_COMBAT_KILL_TARGET_DOWN);
        }
        return new KillCallout("One less to worry about.", BotDialogueSounds.LINE_COMBAT_KILL_ONE_LESS);
    }
    
    @SuppressWarnings("unused")
    private static String pickCombatEndLine() {
        return choose(
            "All clear.",
            "That was close.",
            "We're safe now.",
            "Threat neutralized.",
            "Area secure."
        );
    }
    
    private static String pickPlayerHitLine(String playerName, float damage) {
        if (damage >= 4.0f) {
            // Heavy hit
            return choose(
                "Hey! What was that for, " + playerName + "?!",
                "Ow! That actually hurt!",
                "Whoa, easy there!",
                "I'm on your side, " + playerName + "!",
                "Friendly fire, " + playerName + "!"
            );
        } else {
            // Light hit
            return choose(
                "Hey! Watch it, " + playerName + ".",
                "What's the big idea?",
                "Was that necessary?",
                "Ow. Thanks for that.",
                "Really, " + playerName + "?"
            );
        }
    }
    
    private static String choose(String... options) {
        if (options == null || options.length == 0) return "";
        return options[RNG.nextInt(options.length)];
    }
    
    /**
     * Reset combat state for a bot (e.g., on death or respawn).
     */
    public static void resetCombatState(UUID botId) {
        IN_COMBAT.remove(botId);
        LAST_COMBAT_ACTIVITY_MS.remove(botId);
        LAST_THREAT_DETECT_MS.remove(botId);
        LAST_ENGAGEMENT_MS.remove(botId);
        LAST_DAMAGE_MS.remove(botId);
        LAST_KILL_MS.remove(botId);
        LAST_COMBAT_END_MS.remove(botId);
        LAST_PLAYER_HIT_MS.remove(botId);
    }
    
    /**
     * Server tick handler - checks for combat end for all bots.
     * Register this with ServerTickEvents.END_SERVER_TICK.
     */
    public static void onServerTick(net.minecraft.server.MinecraftServer server) {
        if (server == null) return;
        
        // Only check every 20 ticks (1 second)
        if (server.getTicks() % 20 != 0) return;
        
        for (ServerPlayerEntity bot : BotEventHandler.getRegisteredBots(server)) {
            if (bot == null || bot.isRemoved()) continue;
            checkCombatEnd(bot);
        }
    }
}
