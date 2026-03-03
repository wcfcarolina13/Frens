package net.wcfcarolina13.Commands;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.wcfcarolina13.CommandUtils;
import net.wcfcarolina13.GameAI.llm.LLMOrchestrator;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import net.wcfcarolina13.items.ModItems;
import net.minecraft.sound.SoundEvent;
import net.wcfcarolina13.ChatUtils.BotDialoguePlayer;
import net.wcfcarolina13.ChatUtils.BotDialogueSounds;
import net.wcfcarolina13.ChatUtils.ChatUtils;
import net.wcfcarolina13.DangerZoneDetector.DangerZoneDetector;
import net.wcfcarolina13.Database.QTableExporter;
import net.wcfcarolina13.Entity.AutoFaceEntity;
import net.wcfcarolina13.Entity.LookController;
import net.wcfcarolina13.Entity.RayCasting;
import net.wcfcarolina13.Entity.RespawnHandler;
import net.wcfcarolina13.Entity.createFakePlayer;
import net.wcfcarolina13.FilingSystem.LLMClientFactory;
import net.wcfcarolina13.FilingSystem.ManualConfig;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.GameAI.BotEventHandler;
import net.wcfcarolina13.GameAI.services.BotPersistenceService;
import net.wcfcarolina13.GameAI.services.BotCommandStateService;
import net.wcfcarolina13.GameAI.services.BotHomeService;
import net.wcfcarolina13.GameAI.services.BotIdleHobbiesService;
import net.wcfcarolina13.GameAI.services.BotInventoryFullDialogueService;
import net.wcfcarolina13.GameAI.services.SafePositionService;
import net.wcfcarolina13.GameAI.State;
import net.wcfcarolina13.GameAI.StateActions;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Path;
import net.wcfcarolina13.OllamaClient.ollamaClient;
import net.wcfcarolina13.PathFinding.ChartPathToBlock;
import net.wcfcarolina13.PathFinding.PathFinder;
import net.wcfcarolina13.PathFinding.PathTracer;
import net.wcfcarolina13.PathFinding.Segment;
import net.wcfcarolina13.PlayerUtils.*;
import net.wcfcarolina13.GameAI.skills.SkillManager;
import net.wcfcarolina13.GameAI.skills.SkillPreferences;
import net.wcfcarolina13.GameAI.services.BotInventoryStorageService;
import net.wcfcarolina13.GameAI.services.BotTargetingService;
import net.wcfcarolina13.GameAI.services.HealingService;
import net.wcfcarolina13.GameAI.services.InventoryAccessPolicy;
import net.wcfcarolina13.GameAI.services.MovementService;
import net.wcfcarolina13.GameAI.services.CraftingHelper;
import net.wcfcarolina13.GameAI.services.SmeltingService;
import net.wcfcarolina13.GameAI.services.ProtectedZoneService;
import net.wcfcarolina13.GameAI.services.SkillResumeService;
import net.wcfcarolina13.GameAI.services.SleepService;
import net.wcfcarolina13.GameAI.services.TaskService;
import net.wcfcarolina13.GameAI.services.WorkDirectionService;
import net.wcfcarolina13.GameAI.services.WizardTomeGrantService;
import net.wcfcarolina13.GameAI.skills.SkillContext;
import net.wcfcarolina13.GameAI.skills.SkillExecutionResult;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.services.ChestStoreService;
import net.wcfcarolina13.GameAI.services.DebugToggleService;
import net.wcfcarolina13.ui.BotInventoryAccess;
import net.wcfcarolina13.FunctionCaller.FunctionCallerV2;
import net.wcfcarolina13.ServiceLLMClients.LLMClient;
import net.wcfcarolina13.ServiceLLMClients.LLMServiceHandler;
import net.wcfcarolina13.WorldUitls.isFoodItem;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import net.minecraft.entity.ItemEntity;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.Identifier;
import net.minecraft.entity.player.PlayerInventory;


import static net.wcfcarolina13.PathFinding.PathFinder.*;
import static net.minecraft.server.command.CommandManager.literal;
import net.wcfcarolina13.PacketHandler.InputPacketHandler;
import net.wcfcarolina13.GameAI.services.SurvivalRecruitmentService;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.wcfcarolina13.network.RecruitmentPromptPayload;
import net.wcfcarolina13.network.CompanionQuestStatePayload;

public class modCommandRegistry {

    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private static final ExecutorService skillExecutor = Executors.newCachedThreadPool();
    static final double DEFAULT_GUARD_RADIUS = 6.0D;
    public static boolean isTrainingMode = false;
    public static boolean enableReinforcementLearning = false;
    public static String botName = "";
    public static final Logger LOGGER = LoggerFactory.getLogger("mod-command-registry");

    // Eye-of-Ender access is intentionally limited: it has a cooldown and does NOT represent a full Wizard's Tome unlock.
    private static final long COMPANION_EYE_SPELL_COOLDOWN_TICKS = 20L * 60L; // 60s
    private static final Map<UUID, Long> COMPANION_EYE_SPELL_LAST_TICK = new ConcurrentHashMap<>();
    private static final Set<String> DIALOGUE_TEST_SUPPORTED_TRIGGERS = Set.of(
            "fighting_multiple_dangerous", "combat_multi",
            "combat_ended", "post_combat",
            "combat_ended_explosion", "post_explosion",
            "combat_ended_multiple_dangerous", "post_combat_multi",
            "combat_ended_single_weak", "post_combat_single",
            "player_hit_bot", "ff_received",
            "bot_hit_player", "ff_dealt",
            "villager_noise_nearby", "villager",
            "player_opens_villager_trade", "villager_negotiate",
            "tamed_wolf_nearby", "wolf_nearby",
            "wolf_takes_damage", "wolf_hurt",
            "tamed_animal_nearby", "animal_nearby",
            "random_idle_not_combat", "ambient",
            "in_high_threat_location", "high_threat",
            "scary_sound_nearby", "scary",
            "in_boat_not_combat", "boat",
            "in_boat_deep_water", "boat_deep",
            "in_boat_dolphin_nearby", "boat_dolphin",
            "boat_breaks", "boat_break",
            "standing_on_edge", "precipice",
            "safe_vista", "vista",
            "falling_or_elytra", "freefall",
            "random_ambient", "meta",
            "baby_zombie_on_chicken", "meme_chicken",
            "creeper_hiss", "meme_creeper",
            "world_start_or_milestone", "meme_steve",
            "survive_near_death_or_totem", "meme_technoblade",
            "lightning_at_night", "meme_herobrine",
            "shelter_completion", "shelter",
            "batch3_biomes", "topic_biomes",
            "batch3_structures", "topic_structures",
            "batch3_dimensions", "topic_dimensions",
            "batch3_traders_mounts", "topic_mounts",
            "batch3_travel", "topic_travel"
    );
    private static final Set<Item> GO_TO_LOOK_STORAGE_UTILITY_EXCLUSIONS = Set.of(
            ModItems.WIZARD_TOME,
            Items.GOAT_HORN,
            Items.ENDER_EYE,
            Items.CHEST,
            Items.BARREL,
            Items.CRAFTING_TABLE,
            Items.FURNACE,
            Items.BLAST_FURNACE,
            Items.SMOKER
    );
        private static final Map<String, Long> RECRUIT_MODE_SWITCH_CONFIRM_UNTIL_MS = new ConcurrentHashMap<>();


    public record BotStopTask(MinecraftServer server, ServerCommandSource botSource,
                                  String botName) implements Runnable {

        @Override
        public void run() {

            stopMoving(server, botSource, botName);
            LOGGER.info("{} has stopped walking!", botName);


        }
    }


    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(
                        literal("bot")
                            .requires(Frens::hasBotCommandPermission)
                            .then(literal("spawn")
                                .then(CommandManager.argument("bot_name", StringArgumentType.string())
                                        .then(CommandManager.argument("mode", StringArgumentType.string())
                                                .executes(context -> {
                                                    String botName = StringArgumentType.getString(context, "bot_name");
                                                    String spawnMode = StringArgumentType.getString(context, "mode");
                                                    try {
                                                        LOGGER.info("Executing /bot spawn {} {}", botName, spawnMode);
                                                        spawnBot(context, spawnMode, null);
                                                        LOGGER.info("/bot spawn completed successfully for {} {}", botName, spawnMode);
                                                        return 1;
                                                    } catch (Exception e) {
                                                        LOGGER.error("❌ Exception while executing /bot spawn {} {}", botName, spawnMode, e);
                                                        context.getSource().sendError(Text.literal(
                                                                "An internal error occurred while running /bot spawn. Check server log for details."
                                                        ));
                                                        return 0;
                                                    }
                                                })
                                                .then(CommandManager.argument("gamemode", StringArgumentType.string())
                                                        .executes(context -> {
                                                            String botName = StringArgumentType.getString(context, "bot_name");
                                                            String spawnMode = StringArgumentType.getString(context, "mode");
                                                            String gameMode = StringArgumentType.getString(context, "gamemode");
                                                            try {
                                                                LOGGER.info("Executing /bot spawn {} {} {}", botName, spawnMode, gameMode);
                                                                spawnBot(context, spawnMode, gameMode);
                                                                LOGGER.info("/bot spawn completed successfully for {} {} {}", botName, spawnMode, gameMode);
                                                                return 1;
                                                            } catch (Exception e) {
                                                                LOGGER.error("❌ Exception while executing /bot spawn {} {} {}", botName, spawnMode, gameMode, e);
                                                                context.getSource().sendError(Text.literal(
                                                                        "An internal error occurred while running /bot spawn. Check server log for details."
                                                                ));
                                                                return 0;
                                                            }
                                                        })
                                                )
                                            )
                                            )

                                        // In-game debug toggle: /bot debug <on|off>, /bot debug status, /bot debug clear
                                        .then(literal("debug")
                                            .then(CommandManager.argument("mode", StringArgumentType.string())
                                                .executes(context -> {
                                                    boolean enabled = parseToggle(StringArgumentType.getString(context, "mode"));
                                                    DebugToggleService.setRuntimeVerbose(enabled);
                                                    ChatUtils.sendSystemMessage(context.getSource(), "Debug set to " + (enabled ? "on" : "off"));
                                                    return 1;
                                                })
                                            )
                                            .then(literal("status")
                                                .executes(context -> {
                                                    Boolean override = DebugToggleService.getRuntimeOverride();
                                                    boolean effective = DebugToggleService.verbose();
                                                    String msg = "Debug: runtimeOverride=" + (override == null ? "none" : override.toString()) + " effective=" + effective;
                                                    ChatUtils.sendSystemMessage(context.getSource(), msg);
                                                    return 1;
                                                })
                                            )
                                            .then(literal("clear")
                                                .executes(context -> {
                                                    DebugToggleService.clearRuntimeOverride();
                                                    ChatUtils.sendSystemMessage(context.getSource(), "Debug runtime override cleared (env/properties now effective)");
                                                    return 1;
                                                })
                                            )
                                        )
                                // Operator helper: quickly give yourself the Wizard's Tome quest item.
                                .then(literal("wizard_tome")
                                        .executes(context -> executeGiveWizardTome(context.getSource(), 1))
                                        .then(CommandManager.argument("amount", IntegerArgumentType.integer(1, 64))
                                                .executes(context -> executeGiveWizardTome(
                                                        context.getSource(),
                                                        IntegerArgumentType.getInteger(context, "amount")
                                                ))
                                        )
                                )
	                        )
	                        .then(literal("rl")
	                                .then(CommandManager.argument("mode", StringArgumentType.string())
	                                        .executes(context -> {
	                                            boolean enabled = parseToggle(StringArgumentType.getString(context, "mode"));
	                                            enableReinforcementLearning = enabled;
	                                            ChatUtils.sendSystemMessage(context.getSource(),
	                                                    "Reinforcement learning loop set to " + (enabled ? "on" : "off"));
	                                            return 1;
	                                        })
	                                )
	                        )
	                        .then(literal("llm")
	                                .then(literal("world")
	                                        .then(CommandManager.argument("mode", StringArgumentType.string())
	                                                .executes(context -> {
                                                    boolean enabled = parseToggle(StringArgumentType.getString(context, "mode"));
                                                    String key = context.getSource().getServer().getSaveProperties().getLevelName()
                                                            + ":" + context.getSource().getWorld().getRegistryKey().getValue();
                                                    LLMOrchestrator.setWorldEnabled(key, enabled);
                                                    ChatUtils.sendSystemMessage(context.getSource(),
                                                            "LLM world toggle set to " + (enabled ? "on" : "off"));
                                                    return 1;
                                                })
                                        )
                                )
                                .then(literal("bot")
                                        .then(CommandManager.argument("bot", EntityArgumentType.player())
                                                .then(CommandManager.argument("mode", StringArgumentType.string())
                                                        .executes(context -> {
                                                            boolean enabled = parseToggle(StringArgumentType.getString(context, "mode"));
                                                            ServerPlayerEntity bot = EntityArgumentType.getPlayer(context, "bot");
                                                            LLMOrchestrator.setBotEnabled(bot.getUuid(), enabled);
                                                            ChatUtils.sendSystemMessage(context.getSource(),
                                                                    bot.getName().getString() + " LLM set to " + (enabled ? "on" : "off"));
                                                            return 1;
                                                        })
                                                )
                                        )
                                )
                        )

	                        .then(BotInventoryCommands.build())
	                        .then(BotSkillCommands.buildSkill())
	                        .then(BotSkillCommands.buildFish())
	                        .then(BotSkillCommands.buildFlare())
	                        .then(BotSkillCommands.buildShelter())
	                        .then(BotSkillCommands.buildBuild())
	                        .then(BotSkillCommands.buildLeash())
	                        .then(BotSkillCommands.buildHitch())
	                        .then(BotSkillCommands.buildFortify())
	                        .then(BotLifecycleCommands.buildList())
	                        .then(BotLifecycleCommands.buildDespawn())
	                        .then(BotLifecycleCommands.buildStop())
	                        .then(BotLifecycleCommands.buildResume())
                            .then(BotLifecycleCommands.buildResumeShort())
	                        .then(BotLifecycleCommands.buildHeal())
	                        .then(BotLifecycleCommands.buildSleep())
	                        .then(BotUtilityCommands.buildDirection())
	                        .then(BotUtilityCommands.buildZone())
	                        .then(BotUtilityCommands.buildLookPlayer())
	                        .then(BotUtilityCommands.buildFollow())
                            .then(BotUtilityCommands.buildFollowDistance())
                            .then(BotUtilityCommands.buildFollowCheck())
                            .then(BotUtilityCommands.buildSoundTest())
	                            .then(BotUtilityCommands.buildTestChatter())
	                            .then(BotUtilityCommands.buildDialogueTest())
	                            .then(BotUtilityCommands.buildChatCheck())
	                            .then(BotUtilityCommands.buildIdentityCheck())
	                            .then(BotHomeCommands.buildAutoReturnSunset())
	                            .then(BotHomeCommands.buildAutoReturnSunsetGuardPatrolEligible())
                            .then(BotHomeCommands.buildAutoReturnSunsetPreferLastBed())
                            .then(BotHomeCommands.buildIdleHobbies())
                            .then(BotHomeCommands.buildAutoHuntStarving())
                            .then(BotHomeCommands.buildIdleNow())
                            .then(BotHomeCommands.buildBase())
                            .then(BotHomeCommands.buildUnleashTethered())
	                        .then(BotHomeCommands.buildLeashOnDismount())
                            .then(BotLearningCommands.buildLearn())
	                        .then(BotMovementCommands.buildCome())
                            .then(BotCompanionCommands.build())
	                        .then(BotMovementCommands.buildRegroup())
                            .then(BotMovementCommands.buildGoToLook())
                            .then(BotMovementCommands.buildShelterLook())
                            .then(BotMovementCommands.buildBuildLook())
	                        .then(BotMovementCommands.buildGuard())
	                        .then(BotMovementCommands.buildPatrol())
	                        .then(BotMovementCommands.buildStay("stay"))
	                        .then(BotMovementCommands.buildStay("stay_here"))
	                        .then(BotMovementCommands.buildReturn("return_to_base"))
	                        .then(BotMovementCommands.buildReturn("return"))
	                        .then(BotCombatCommands.buildAssist())
	                        .then(BotCombatCommands.buildDefend())
	                        .then(BotCombatCommands.buildStance())
	                        .then(BotEquipCommands.build())
	                        .then(literal("config")
	                                .then(literal("teleportDuringSkills")
	                                        .then(CommandManager.argument("mode", StringArgumentType.string())
                                                .executes(context -> executeTeleportConfigTargets(
                                                        context,
                                                        null,
                                                        parseToggle(StringArgumentType.getString(context, "mode"))))
                                                .then(CommandManager.argument("target", StringArgumentType.string())
                                                        .executes(context -> executeTeleportConfigTargets(
                                                                context,
                                                                StringArgumentType.getString(context, "target"),
                                                                parseToggle(StringArgumentType.getString(context, "mode")))))
                                        )
                                )
                                .then(literal("inventoryFullPause")
                                        .then(CommandManager.argument("mode", StringArgumentType.string())
                                                .executes(context -> executeInventoryFullConfigTargets(
                                                        context,
                                                        null,
                                                        parseToggle(StringArgumentType.getString(context, "mode"))))
                                                .then(CommandManager.argument("target", StringArgumentType.string())
                                                        .executes(context -> executeInventoryFullConfigTargets(
                                                                context,
                                                                StringArgumentType.getString(context, "target"),
                                                                parseToggle(StringArgumentType.getString(context, "mode")))))
                                        )
                                )
                                .then(literal("owner")
                                        .then(CommandManager.argument("alias", StringArgumentType.string())
                                                .then(CommandManager.argument("player", EntityArgumentType.player())
                                                        .executes(context -> executeSetOwner(
                                                                context,
                                                                StringArgumentType.getString(context, "alias"),
                                                                EntityArgumentType.getPlayer(context, "player")))))
                                )
                                .then(literal("pathfinder")
                                        .then(CommandManager.argument("mode", StringArgumentType.string())
                                                .executes(context -> {
                                                    String mode = StringArgumentType.getString(context, "mode").toLowerCase();
                                                    boolean baritone;
                                                    switch (mode) {
                                                        case "baritone": baritone = true; break;
                                                        case "classic": case "bidir": baritone = false; break;
                                                        default:
                                                            context.getSource().sendFeedback(
                                                                    () -> Text.literal("Usage: /bot config pathfinder <baritone|classic>"), false);
                                                            return 0;
                                                    }
                                                    net.wcfcarolina13.PathFinding.PathFinder.USE_BARITONE_STYLE = baritone;
                                                    if (Frens.CONFIG != null) {
                                                        Frens.CONFIG.setBaritonePathfinderEnabled(baritone);
                                                        Frens.CONFIG.save();
                                                    }
                                                    String name = baritone ? "baritone" : "classic (bidirectional A*)";
                                                    context.getSource().sendFeedback(
                                                            () -> Text.literal("Pathfinder switched to: " + name), true);
                                                    return 1;
                                                }))
                                        .executes(context -> {
                                            String current = net.wcfcarolina13.PathFinding.PathFinder.USE_BARITONE_STYLE
                                                    ? "baritone" : "classic (bidirectional A*)";
                                            context.getSource().sendFeedback(
                                                    () -> Text.literal("Current pathfinder: " + current), false);
                                            return 1;
                                        })
                                )
                        )
                        .then(literal("forceplace")
                                .then(CommandManager.argument("value", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            builder.suggest("on"); builder.suggest("off");
                                            return builder.buildFuture();
                                        })
                                        .executes(context -> {
                                            String raw = StringArgumentType.getString(context, "value");
                                            Boolean val = parseToggle(raw);
                                            if (val == null) {
                                                context.getSource().sendFeedback(
                                                        () -> Text.literal("Usage: /bot config forceplace <on|off>"), false);
                                                return 0;
                                            }
                                            Frens.CONFIG.setFortifyForcePlaceEnabled(val);
                                            Frens.CONFIG.save();
                                            boolean enabled = val;
                                            context.getSource().sendFeedback(
                                                    () -> Text.literal("Fortify force-place: " + (enabled ? "enabled" : "disabled")), true);
                                            return 1;
                                        }))
                                .executes(context -> {
                                    boolean current = Frens.CONFIG.isFortifyForcePlaceEnabled();
                                    context.getSource().sendFeedback(
                                            () -> Text.literal("Fortify force-place: " + (current ? "enabled" : "disabled")), false);
                                    return 1;
                                })
                        )
                        .then(literal("walk")
                                .then(CommandManager.argument("bot", EntityArgumentType.player())
                                        .then(CommandManager.argument("till", IntegerArgumentType.integer())
                                                .executes(context -> { botWalk(context); return 1; })
                                        )
                                )
                        )
                        .then(literal("jump")
                                .then(CommandManager.argument("bot", EntityArgumentType.player())
                                        .executes(context -> { botJump(context); return 1; })
                                )
                        )
                        .then(literal("teleport_forward")
                                .then(CommandManager.argument("bot", EntityArgumentType.player())
                                        .executes(context -> { teleportForward(context); return 1; })
                                )
                        )
                        .then(literal("craft")
                                .then(CommandManager.argument("item", StringArgumentType.string())
                                        .executes(context -> executeCraftGeneric(context,
                                                StringArgumentType.getString(context, "item"),
                                                1,
                                                null,
                                                getActiveBotOrThrow(context)))
                                        .then(CommandManager.argument("amount", IntegerArgumentType.integer(1))
                                                .executes(context -> executeCraftGeneric(context,
                                                        StringArgumentType.getString(context, "item"),
                                                        IntegerArgumentType.getInteger(context, "amount"),
                                                        null,
                                                        getActiveBotOrThrow(context)))
                                                .then(CommandManager.argument("material", StringArgumentType.string())
                                                        .executes(context -> executeCraftGeneric(context,
                                                                StringArgumentType.getString(context, "item"),
                                                                IntegerArgumentType.getInteger(context, "amount"),
                                                                StringArgumentType.getString(context, "material"),
                                                                getActiveBotOrThrow(context)))
                                                        .then(CommandManager.argument("bot", EntityArgumentType.player())
                                                                .executes(context -> executeCraftGeneric(context,
                                                                        StringArgumentType.getString(context, "item"),
                                                                        IntegerArgumentType.getInteger(context, "amount"),
                                                                        StringArgumentType.getString(context, "material"),
                                                                        EntityArgumentType.getPlayer(context, "bot"))))))
                                        .then(CommandManager.argument("material", StringArgumentType.string())
                                                .executes(context -> executeCraftGeneric(context,
                                                        StringArgumentType.getString(context, "item"),
                                                        1,
                                                        StringArgumentType.getString(context, "material"),
                                                        getActiveBotOrThrow(context)))
                                                .then(CommandManager.argument("bot", EntityArgumentType.player())
                                                        .executes(context -> executeCraftGeneric(context,
                                                                StringArgumentType.getString(context, "item"),
                                                                1,
                                                                StringArgumentType.getString(context, "material"),
                                                                EntityArgumentType.getPlayer(context, "bot")))))
                                        .then(CommandManager.argument("bot", EntityArgumentType.player())
                                                .executes(context -> executeCraftGeneric(context,
                                                        StringArgumentType.getString(context, "item"),
                                                        1,
                                                        null,
                                                        EntityArgumentType.getPlayer(context, "bot"))))
                                )
                        )
                        .then(literal("place")
                                .then(CommandManager.argument("item", StringArgumentType.string())
                                        .executes(context -> executePlaceGeneric(context,
                                                StringArgumentType.getString(context, "item"),
                                                1,
                                                getActiveBotOrThrow(context)))
                                        .then(CommandManager.argument("count", IntegerArgumentType.integer(1))
                                                .executes(context -> executePlaceGeneric(context,
                                                        StringArgumentType.getString(context, "item"),
                                                        IntegerArgumentType.getInteger(context, "count"),
                                                        getActiveBotOrThrow(context)))
                                                .then(CommandManager.argument("bot", EntityArgumentType.player())
                                                        .executes(context -> executePlaceGeneric(context,
                                                                StringArgumentType.getString(context, "item"),
                                                                IntegerArgumentType.getInteger(context, "count"),
                                                                EntityArgumentType.getPlayer(context, "bot")))))
                                        .then(CommandManager.argument("bot", EntityArgumentType.player())
                                                .executes(context -> executePlaceGeneric(context,
                                                        StringArgumentType.getString(context, "item"),
                                                        1,
                                                        EntityArgumentType.getPlayer(context, "bot"))))
                                )
                        )
                        .then(literal("cook")
                                .executes(context -> executeCook(context, getActiveBotOrThrow(context), null, null))
                                .then(CommandManager.argument("item", StringArgumentType.string())
                                        .executes(context -> executeCook(context, getActiveBotOrThrow(context), StringArgumentType.getString(context, "item"), null))
                                        .then(CommandManager.argument("fuel", StringArgumentType.string())
                                                .executes(context -> executeCook(context, getActiveBotOrThrow(context),
                                                        StringArgumentType.getString(context, "item"),
                                                        StringArgumentType.getString(context, "fuel")))
                                                .then(CommandManager.argument("bot", EntityArgumentType.player())
                                                        .executes(context -> executeCook(context, EntityArgumentType.getPlayer(context, "bot"),
                                                                StringArgumentType.getString(context, "item"),
                                                                StringArgumentType.getString(context, "fuel"))))))
                                .then(CommandManager.argument("fuel", StringArgumentType.string())
                                        .executes(context -> executeCook(context, getActiveBotOrThrow(context), null,
                                                StringArgumentType.getString(context, "fuel")))
                                        .then(CommandManager.argument("bot", EntityArgumentType.player())
                                                .executes(context -> executeCook(context, EntityArgumentType.getPlayer(context, "bot"), null,
                                                        StringArgumentType.getString(context, "fuel")))))
                                .then(CommandManager.argument("bot", EntityArgumentType.player())
                                        .executes(context -> executeCook(context, EntityArgumentType.getPlayer(context, "bot"), null, null)))
                        )
	                        .then(literal("store")
	                                .then(literal("deposit")
	                                        .executes(context -> executeStoreDeposit(context, "all", "", getActiveBotOrThrow(context)))
	                                        .then(CommandManager.argument("bot", EntityArgumentType.player())
	                                                .executes(context -> executeStoreDeposit(context, "all", "", EntityArgumentType.getPlayer(context, "bot"))))
	                                        .then(CommandManager.argument("amount", StringArgumentType.string())
	                                                .executes(context -> executeStoreDeposit(context,
	                                                        StringArgumentType.getString(context, "amount"),
	                                                        "",
	                                                        getActiveBotOrThrow(context)))
	                                                .then(CommandManager.argument("bot", EntityArgumentType.player())
	                                                        .executes(context -> executeStoreDeposit(context,
	                                                                StringArgumentType.getString(context, "amount"),
	                                                                "",
	                                                                EntityArgumentType.getPlayer(context, "bot"))))
	                                                .then(CommandManager.argument("item", StringArgumentType.string())
	                                                        .executes(context -> executeStoreDeposit(context,
	                                                                StringArgumentType.getString(context, "amount"),
	                                                                StringArgumentType.getString(context, "item"),
	                                                                getActiveBotOrThrow(context)))
	                                                        .then(CommandManager.argument("bot", EntityArgumentType.player())
	                                                                .executes(context -> executeStoreDeposit(context,
	                                                                        StringArgumentType.getString(context, "amount"),
	                                                                        StringArgumentType.getString(context, "item"),
	                                                                        EntityArgumentType.getPlayer(context, "bot"))))))
	                                )
	                                .then(literal("withdraw")
	                                        .executes(context -> executeStoreWithdraw(context, "all", "", getActiveBotOrThrow(context)))
	                                        .then(CommandManager.argument("bot", EntityArgumentType.player())
	                                                .executes(context -> executeStoreWithdraw(context, "all", "", EntityArgumentType.getPlayer(context, "bot"))))
	                                        .then(CommandManager.argument("amount", StringArgumentType.string())
	                                                .executes(context -> executeStoreWithdraw(context,
	                                                        StringArgumentType.getString(context, "amount"),
	                                                        "",
	                                                        getActiveBotOrThrow(context)))
	                                                .then(CommandManager.argument("bot", EntityArgumentType.player())
	                                                        .executes(context -> executeStoreWithdraw(context,
	                                                                StringArgumentType.getString(context, "amount"),
	                                                                "",
	                                                                EntityArgumentType.getPlayer(context, "bot"))))
	                                                .then(CommandManager.argument("item", StringArgumentType.string())
	                                                        .executes(context -> executeStoreWithdraw(context,
	                                                                StringArgumentType.getString(context, "amount"),
	                                                                StringArgumentType.getString(context, "item"),
	                                                                getActiveBotOrThrow(context)))
	                                                        .then(CommandManager.argument("bot", EntityArgumentType.player())
	                                                                .executes(context -> executeStoreWithdraw(context,
	                                                                        StringArgumentType.getString(context, "amount"),
	                                                                        StringArgumentType.getString(context, "item"),
	                                                                        EntityArgumentType.getPlayer(context, "bot"))))))
	                                )
	                        )
                        .then(literal("debug_serialization")
                                .then(CommandManager.argument("bot", EntityArgumentType.player())
                                        .executes(context -> {
                                            try {
                                                ServerPlayerEntity bot = EntityArgumentType.getPlayer(context, "bot");
                                                if (bot == null) return 0;
                                                State state = BotEventHandler.createInitialState(bot);
                                                
                                                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                                ObjectOutputStream oos = new ObjectOutputStream(baos);
                                                oos.writeObject(state);
                                                oos.close();
                                                
                                                ChatUtils.sendSystemMessage(context.getSource(), "Serialization successful! Size: " + baos.size());
                                                return 1;
                                            } catch (Exception e) {
                                                ChatUtils.sendSystemMessage(context.getSource(), "Serialization failed: " + e.toString());
                                                e.printStackTrace();
                                                return 0;
                                            }
                                        })
                                )
                        )
                        .then(literal("debug_qtable_serialization")
                                .executes(context -> {
                                    try {
                                        net.wcfcarolina13.Database.QTable qTable = new net.wcfcarolina13.Database.QTable();
                                        ServerPlayerEntity bot = context.getSource().getPlayer();
                                        if (bot != null) {
                                            State state = BotEventHandler.createInitialState(bot);
                                            qTable.addEntry(state, StateActions.Action.STAY, 0.0, state);
                                        }
                                        
                                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                        ObjectOutputStream oos = new ObjectOutputStream(baos);
                                        oos.writeObject(qTable);
                                        oos.close();
                                        
                                        ChatUtils.sendSystemMessage(context.getSource(), "QTable Serialization successful! Size: " + baos.size());
                                        return 1;
                                    } catch (Exception e) {
                                        ChatUtils.sendSystemMessage(context.getSource(), "QTable Serialization failed: " + e.toString());
                                        e.printStackTrace();
                                        return 0;
                                    }
                                })
                        )
                        .then(literal("test_chat_message")
                                .then(CommandManager.argument("bot", EntityArgumentType.player())
                                        .executes(context -> { testChatMessage(context); return 1; })
                                )
                        )
                        .then(literal("go_to")
                                .then(CommandManager.argument("bot", EntityArgumentType.player())
                                        .then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
                                                .then(CommandManager.argument("sprint", StringArgumentType.string())
                                                        .executes(context -> { botGo(context); return 1; })
                                                )
                                        )
                                )
                        )
                        .then(literal("send_message_to")
                                .then(CommandManager.argument("bot", EntityArgumentType.player())
                                        .then(CommandManager.argument("message", StringArgumentType.greedyString())
                                                .executes(context -> {

                                                    ollamaClient.execute(context);

                                                     return 1;

                                                })
                                        )
                                )
                        )
                        .then(literal("detect_entities")
                                .then(CommandManager.argument("bot", EntityArgumentType.player())
                                        .executes(context -> {
                                            ServerPlayerEntity bot = EntityArgumentType.getPlayer(context, "bot");
                                            if (bot != null) {
                                                RayCasting.detect(bot);
                                            }
                                            return 1;
                                        })
                                )
                        )
                        .then(literal("get_block_map")
                                .then(CommandManager.argument("bot", EntityArgumentType.player())
                                        .then(CommandManager.argument("vertical", IntegerArgumentType.integer())
                                                .then(CommandManager.argument("horizontal", IntegerArgumentType.integer())
                                                        .executes(context -> {
                                                            ServerPlayerEntity bot = EntityArgumentType.getPlayer(context, "bot");
                                                            int y = IntegerArgumentType.getInteger(context, "vertical");
                                                            int x = IntegerArgumentType.getInteger(context, "horizontal");

                                                            InternalMap internalMap = new InternalMap(bot, y, x);
                                                            internalMap.updateMap();
                                                            internalMap.printMap();
                                                            return 1;
                                                        })
                                                )
                                        )

                                )

                        )

                        .then(literal("detect_blocks")
                                .then(CommandManager.argument("bot", EntityArgumentType.player())
                                        .then(CommandManager.argument("block_type", StringArgumentType.string())
                                                .executes(context -> {

                                                    ServerPlayerEntity bot = EntityArgumentType.getPlayer(context, "bot");
                                                    String blockType = StringArgumentType.getString(context, "block_type");

                                                    BlockPos outPutPos = blockDetectionUnit.detectBlocks(bot, blockType);

                                                    LOGGER.info("Detected Block: {} at x={}, y={}, z={}", blockType, outPutPos.getX(), outPutPos.getY(), outPutPos.getZ());
                                                    blockDetectionUnit.setIsBlockDetectionActive(false);

                                                    return 1;
                                                })
                                        )
                                )
                        )

                        .then(literal("turn")
                                .then(CommandManager.argument("bot", EntityArgumentType.player())
                                        .then(CommandManager.argument("direction", StringArgumentType.string())
                                                .executes(context -> {

                                                    ServerPlayerEntity bot = EntityArgumentType.getPlayer(context, "bot");
                                                    MinecraftServer server = bot.getCommandSource().getServer();
                                                    String direction = StringArgumentType.getString(context, "direction");

                                                    switch (direction) {
                                                        case "left", "right", "back" -> {
                                                            turnTool.turn(bot.getCommandSource().withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS), direction);

                                                            LOGGER.info("Now facing {} which is in {} in {} axis", direction, bot.getFacing().getId(), bot.getFacing().getAxis().getId());
                                                        }
                                                        default -> {
                                                            server.execute(() -> {
                                                                ChatUtils.sendChatMessages(bot.getCommandSource().withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS), "Invalid parameters! Accepted parameters: left, right, back only!");
                                                            });
                                                        }
                                                    }

                                                    return 1;
                                                })
                                        )
                                )
                        )


                        .then(literal("chart_path_to_block")
                                .then(CommandManager.argument("bot", EntityArgumentType.player())
                                        .then(CommandManager.argument("block_type", StringArgumentType.string())
                                                .then(CommandManager.argument("x", IntegerArgumentType.integer())
                                                        .then(CommandManager.argument("y", IntegerArgumentType.integer())
                                                                .then(CommandManager.argument("z", IntegerArgumentType.integer())
                                                                        .executes(context -> {

                                                                            ServerPlayerEntity bot = EntityArgumentType.getPlayer(context, "bot");
                                                                            String blockType = StringArgumentType.getString(context, "block_type");
                                                                            int x = IntegerArgumentType.getInteger(context, "x");
                                                                            int y = IntegerArgumentType.getInteger(context, "y");
                                                                            int z = IntegerArgumentType.getInteger(context, "z");

                                                                            BlockPos targetPos = new BlockPos(x, y, z);

                                                                            ChartPathToBlock.chart(bot, targetPos, blockType);

                                                                            return 1;
                                                                        })
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )

                        .then(literal("reset_autoface")
                                .then(CommandManager.argument("bot", EntityArgumentType.player())
                                        .executes(context -> {

                                            ServerPlayerEntity bot = EntityArgumentType.getPlayer(context, "bot");
                                            MinecraftServer server = bot.getCommandSource().getServer();
                                            blockDetectionUnit.setIsBlockDetectionActive(false);
                                            PathTracer.flushAllMovementTasks();
                                            AutoFaceEntity.setBotExecutingTask(false);
                                            AutoFaceEntity.isBotMoving = false;

                                            server.execute(() -> {
                                                ChatUtils.sendChatMessages(bot.getCommandSource().withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS), "Autoface module reset complete.");
                                            });

                                            return 1;
                                        })

                                )
                        )

                        .then(literal("mine_block")
                                .then(CommandManager.argument("bot", EntityArgumentType.player())
                                        .then(CommandManager.argument("block_type", StringArgumentType.string())
                                                .then(CommandManager.argument("x", IntegerArgumentType.integer())
                                                        .then(CommandManager.argument("y", IntegerArgumentType.integer())
                                                                .then(CommandManager.argument("z", IntegerArgumentType.integer())
                                                                        .executes(context -> {

                                                                            ServerPlayerEntity bot = EntityArgumentType.getPlayer(context, "bot");
                                                                            int x = IntegerArgumentType.getInteger(context, "x");
                                                                            int y = IntegerArgumentType.getInteger(context, "y");
                                                                            int z = IntegerArgumentType.getInteger(context, "z");
                                                                            MiningTool.mineBlock(bot, new BlockPos(x, y, z));
                                                                            
                                                                            return 1;
                                                                        })
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )


                        .then(literal("use-key")
                                .then(CommandManager.argument("bot", EntityArgumentType.player())
                                        .then(CommandManager.argument("key", StringArgumentType.string())
                                                .executes(context -> {
                                                    MinecraftServer server = context.getSource().getServer();

                                                    ServerCommandSource serverSource = server.getCommandSource();
                                                    String inputKey = StringArgumentType.getString(context, "key");

                                                    switch (inputKey) {
                                                        case "W":
                                                            InputPacketHandler.manualPacketPressWKey(context);
                                                            break;
                                                        case "S":
                                                            InputPacketHandler.manualPacketPressSKey(context);
                                                            break;
                                                        case "A":
                                                            InputPacketHandler.manualPacketPressAKey(context);
                                                            break;
                                                        case "D":
                                                            InputPacketHandler.manualPacketPressDKey(context);
                                                            break;
                                                        case "Sneak":
                                                            InputPacketHandler.manualPacketSneak(context);
                                                            break;
                                                        case "LSHIFT":
                                                            InputPacketHandler.manualPacketSneak(context);
                                                            break;
                                                        case "Sprint":
                                                            InputPacketHandler.manualPacketSprint(context);
                                                            break;
                                                        default:
                                                            ChatUtils.sendSystemMessage(serverSource, "This key is not registered.");
                                                            break;
                                                    }

                                                    return 1;
                                                })
                                        )

                                )
                        )

                        .then(literal("look")
                                .then(CommandManager.argument("bot", EntityArgumentType.player())
                                        .then(CommandManager.argument("bot_name", StringArgumentType.string())
                                                .then(CommandManager.argument("direction", StringArgumentType.string())
                                                        .executes(context -> {

                                                            MinecraftServer server = context.getSource().getServer();

                                                            ServerCommandSource serverSource = server.getCommandSource();

                                                            String botName = StringArgumentType.getString(context, "bot_name");

                                                            ServerPlayerEntity bot = context.getSource().getServer().getPlayerManager().getPlayer(botName);

                                                            String direction = StringArgumentType.getString(context, "direction");

                                                            switch (direction) {

                                                                case("north"):
                                                                    InputPacketHandler.BotLookController.lookInDirection(bot, Direction.NORTH);
                                                                    break;

                                                                case("south"):
                                                                    InputPacketHandler.BotLookController.lookInDirection(bot, Direction.SOUTH);
                                                                    break;

                                                                case("east"):
                                                                    InputPacketHandler.BotLookController.lookInDirection(bot, Direction.EAST);
                                                                    break;

                                                                case("west"):
                                                                    InputPacketHandler.BotLookController.lookInDirection(bot, Direction.WEST);
                                                                    break;

                                                                default:
                                                                    ChatUtils.sendSystemMessage(serverSource, "Invalid direction.");
                                                                    break;
                                                            }

                                                            return 1;
                                                        })

                                                )
                                        )

                                )

                        )

                        .then(literal("release-all-keys")
                                .then(CommandManager.argument("bot", EntityArgumentType.player())
                                        .then(CommandManager.argument("bot_name", StringArgumentType.string())
                                                .executes(context -> {
                                                    MinecraftServer server = context.getSource().getServer();

                                                    ServerCommandSource serverSource = server.getCommandSource();

                                                    String botName = StringArgumentType.getString(context, "bot_name");

                                                    InputPacketHandler.manualPacketReleaseMovementKey(context);

                                                    ChatUtils.sendSystemMessage(serverSource, "Released all movement keys for bot: " + botName);

                                                    return 1;
                                                })
                                        )

                                )
                        )

                        .then(literal("detectDangerZone")
                                .then(CommandManager.argument("bot", EntityArgumentType.player())
                                        .then(CommandManager.argument("lavaRange", IntegerArgumentType.integer())
                                                .then(CommandManager.argument("cliffRange", IntegerArgumentType.integer())
                                                        .then(CommandManager.argument("cliffDepth", IntegerArgumentType.integer())
                                                                .executes(context -> {

                                                                    ServerPlayerEntity bot = EntityArgumentType.getPlayer(context, "bot");
                                                                    ServerCommandSource botSource = bot.getCommandSource().withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS);
                                                                    MinecraftServer server = botSource.getServer();

                                                                    int lavaRange = IntegerArgumentType.getInteger(context, "lavaRange");     // Range to check for lava blocks
                                                                    int cliffRange = IntegerArgumentType.getInteger(context, "cliffRange");     // Forward range to check for cliffs
                                                                    int cliffDepth = IntegerArgumentType.getInteger(context, "cliffDepth");    // Downward range to check for solid blocks

                                                                    server.execute(() -> {
                                                                        // Putting this part in a thread so that it doesn't hang the game.

                                                                        double dangerDistance = DangerZoneDetector.detectDangerZone(bot, lavaRange, cliffRange, cliffDepth);
                                                                        if (dangerDistance > 0) {
                                                                            DebugToggleService.debug(LOGGER, "Danger detected! Effective distance: {}", dangerDistance);
                                                                            ChatUtils.sendChatMessages(botSource, "Danger detected! Effective distance to danger: " + (int) dangerDistance + " blocks");

                                                                        } else {
                                                                            DebugToggleService.debug(LOGGER, "No danger nearby.");
                                                                            ChatUtils.sendChatMessages(botSource, "No danger nearby");
                                                                        }

                                                                    });

                                                                    return 1;
                                                                })
                                                        )
                                                )
                                        )
                                )
                        )


                        .then(literal("getHotbarItems")
                                .then(CommandManager.argument("bot", EntityArgumentType.player())
                                        .executes(context -> {
                                            ServerPlayerEntity bot = EntityArgumentType.getPlayer(context, "bot");
                                            ServerCommandSource botSource = bot.getCommandSource().withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS);

                                            List<ItemStack> hotbarItems = hotBarUtils.getHotbarItems(bot);

                                            StringBuilder messageBuilder = new StringBuilder(); // Initialize a StringBuilder

                                            for (int i = 0; i < hotbarItems.size(); i++) {
                                                int slotIndex = i; // Avoid issues with lambda expressions

                                                ItemStack itemStack = hotbarItems.get(slotIndex);

                                                if (itemStack.isEmpty()) {

                                                    messageBuilder.append("Slot ").append(i+1).append(": EMPTY\n"); // Append for empty slots

                                                } else {

                                                    messageBuilder.append("Slot ").append(i+1).append(": ")
                                                            .append(itemStack.getName().getString()) // Add item name
                                                            .append(" (Count: ").append(itemStack.getCount()).append(")\n"); // Add item count

                                                }


                                            }

                                            String finalMessage = messageBuilder.toString();

                                            ChatUtils.sendChatMessages(botSource, finalMessage);


                                            return 1;
                                        })
                                )

                        )

                        .then(literal("getSelectedItem")
                                .then(CommandManager.argument("bot", EntityArgumentType.player())
                                        .executes(context -> {

                                            ServerPlayerEntity bot = EntityArgumentType.getPlayer(context, "bot");

                                            ServerCommandSource botSource = bot.getCommandSource().withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS);

                                            String selectedItem = hotBarUtils.getSelectedHotbarItemStack(bot).getItem().getName().getString();

                                            ChatUtils.sendChatMessages(botSource, "Currently selected item: " + selectedItem);

                                            return 1;
                                        })

                                )

                        )

                        .then(literal("getHungerLevel")
                                .then(CommandManager.argument("bot", EntityArgumentType.player())
                                        .executes(context -> {

                                            ServerPlayerEntity bot = EntityArgumentType.getPlayer(context, "bot");

                                            ServerCommandSource botSource = bot.getCommandSource().withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS);

                                            int botHungerLevel = getPlayerHunger.getBotHungerLevel(bot);

                                            ChatUtils.sendChatMessages(botSource, "Hunger level: " + botHungerLevel);

                                            return 1;

                                        })
                                )
                        )

                        .then(literal("getOxygenLevel")
                                .then(CommandManager.argument("bot", EntityArgumentType.player())
                                        .executes(context -> {

                                            ServerPlayerEntity bot = EntityArgumentType.getPlayer(context, "bot");

                                            ServerCommandSource botSource = bot.getCommandSource().withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS);

                                            int botHungerLevel = getPlayerOxygen.getBotOxygenLevel(bot);

                                            ChatUtils.sendChatMessages(botSource, "Oxygen level: " + botHungerLevel);

                                            return 1;
                                        })
                                )
                        )
                        .then(literal("getHealth")
                                .then(CommandManager.argument("bot", EntityArgumentType.player())
                                        .executes(context -> {

                                            ServerPlayerEntity bot = EntityArgumentType.getPlayer(context, "bot");

                                            ServerCommandSource botSource = bot.getCommandSource().withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS);

                                            int botHealthLevel = (int) bot.getHealth();

                                            ChatUtils.sendChatMessages(botSource, "Health level: " + botHealthLevel);

                                            return 1;
                                        })
                                )
                        )

                        .then(literal("isFoodItem")
                                .then(CommandManager.argument("bot", EntityArgumentType.player())
                                        .executes(context -> {

                                            ServerPlayerEntity bot = EntityArgumentType.getPlayer(context, "bot");

                                            ServerCommandSource botSource = bot.getCommandSource().withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS);

                                            ItemStack selectedItemStack = hotBarUtils.getSelectedHotbarItemStack(bot);

                                            if (isFoodItem.checkFoodItem(selectedItemStack)) {

                                                ChatUtils.sendChatMessages(botSource, "Currently selected item: " + selectedItemStack.getItem().getName().getString() + " is a food item.");

                                            }

                                            else {

                                                ChatUtils.sendChatMessages(botSource, "Currently selected item: " + selectedItemStack.getItem().getName().getString() + " is not a food item.");

                                            }

                                            return 1;
                                        })
                                )
                        )


                        .then(literal("equipArmor")
                                .then(CommandManager.argument("bot", EntityArgumentType.player())
                                        .executes(context -> {

                                            ServerPlayerEntity bot = EntityArgumentType.getPlayer(context, "bot");

                                            armorUtils.autoEquipArmor(bot);

                                            return 1;
                                        })

                                )
                        )
                        .then(literal("removeArmor")
                                .then(CommandManager.argument("bot", EntityArgumentType.player())
                                        .executes(context -> {

                                            ServerPlayerEntity bot = EntityArgumentType.getPlayer(context, "bot");

                                            armorUtils.autoDeEquipArmor(bot);

                                            return 1;
                                        })

                                )
                        )

                        .then(literal("exportQTableToJSON")
                                .executes(context -> {

                                    MinecraftServer server = context.getSource().getServer(); // gets the minecraft server
                                    ServerCommandSource serverSource = server.getCommandSource();

                                    ChatUtils.sendSystemMessage(serverSource, "Exporting Q-table to JSON. Please wait.... ");

                                    QTableExporter.exportQTable(BotEventHandler.qTableDir + "/qtable.bin", BotEventHandler.qTableDir + "/fullQTable.json");

                                    ChatUtils.sendSystemMessage(serverSource, "Q-table has been successfully exported to a json file at: " + BotEventHandler.qTableDir + "/fullQTable.json" );

                                    return 1;
                                })
                        )

	                        .then(literal("forget")
	                                .then(CommandManager.argument("alias", StringArgumentType.string())
	                                        .executes(context -> {
	                                            String alias = StringArgumentType.getString(context, "alias");
	                                            MinecraftServer server = context.getSource().getServer();
	                                            ServerPlayerEntity bot = server.getPlayerManager().getPlayer(alias);
	
	                                            if (bot == null) {
	                                                context.getSource().sendError(Text.literal("Bot '" + alias + "' not found."));
	                                                return 0;
	                                            }
	
	                                            // Unregister from mod's internal tracking, which calls BotPersistenceService.removeBot()
	                                            BotEventHandler.unregisterBot(bot);
	
	                                            // Explicitly delete player data file
	                                            BotPersistenceService.deletePlayerDataFile(server, bot.getUuid());
	
	                                            // Clear from mod's config if present
	                                            Frens.CONFIG.removeBotEntry(alias);
	                                            Frens.CONFIG.save();
	
	                                            context.getSource().sendFeedback(() -> Text.literal("§aBot '" + alias + "' has been forgotten."), false);
	                                            LOGGER.info("Bot '{}' (UUID {}) has been forgotten and its data deleted.", alias, bot.getUuid());
	                                            return 1;
	                                        }))
	                        )
                        .then(literal("give")
                                // /bot give <item> [count]
                                .then(CommandManager.argument("item", StringArgumentType.string())
                                        .executes(ctx -> executeGive(ctx, null,
                                                StringArgumentType.getString(ctx, "item"),
                                                1))
                                        .then(CommandManager.argument("count", IntegerArgumentType.integer(1))
                                                .executes(ctx -> executeGive(ctx, null,
                                                        StringArgumentType.getString(ctx, "item"),
                                                        IntegerArgumentType.getInteger(ctx, "count"))))
                                )
                                // /bot give <bot> <item> [count]
                                .then(CommandManager.argument("bot", EntityArgumentType.player())
                                        .then(CommandManager.argument("item", StringArgumentType.string())
                                                .executes(ctx -> executeGive(ctx,
                                                        EntityArgumentType.getPlayer(ctx, "bot"),
                                                        StringArgumentType.getString(ctx, "item"),
                                                        1))
                                                .then(CommandManager.argument("count", IntegerArgumentType.integer(1))
                                                        .executes(ctx -> executeGive(ctx,
                                                                EntityArgumentType.getPlayer(ctx, "bot"),
                                                                StringArgumentType.getString(ctx, "item"),
                                                                IntegerArgumentType.getInteger(ctx, "count"))))
                                        )
                                        // /bot give <bot> <player> <item> [count]
                                        .then(CommandManager.argument("player", EntityArgumentType.player())
                                                .then(CommandManager.argument("item", StringArgumentType.string())
                                                        .executes(ctx -> executeGive(
                                                                ctx,
                                                                EntityArgumentType.getPlayer(ctx, "bot"),
                                                                EntityArgumentType.getPlayer(ctx, "player"),
                                                                StringArgumentType.getString(ctx, "item"),
                                                                1
                                                        ))
                                                        .then(CommandManager.argument("count", IntegerArgumentType.integer(1))
                                                                .executes(ctx -> executeGive(
                                                                        ctx,
                                                                        EntityArgumentType.getPlayer(ctx, "bot"),
                                                                        EntityArgumentType.getPlayer(ctx, "player"),
                                                                        StringArgumentType.getString(ctx, "item"),
                                                                        IntegerArgumentType.getInteger(ctx, "count")
                                                                ))
                                                        )
                                                )
                                        )
                                )
                        )
                        .then(literal("open")
                                .executes(ctx -> {
                                    ServerCommandSource source = ctx.getSource();
                                    ServerPlayerEntity viewer = source.getPlayer(); // returns null if console; handle below
                                    if (viewer == null) {
                                        source.sendError(Text.literal("Run from a player, not console."));
                                        return 0;
                                    }

                                    List<ServerPlayerEntity> targets;
                                    try {
                                        targets = BotTargetingService.resolve(source, null);
                                    } catch (CommandSyntaxException e) {
                                        // Mirror /bot skill fallback behavior: if no remembered target, open the active bot.
                                        targets = List.of(getActiveBotOrThrow(ctx));
                                    }

                                    if (targets.size() != 1) {
                                        source.sendError(Text.literal("Specify exactly one bot to open."));
                                        return 0;
                                    }

                                    ServerPlayerEntity bot = targets.get(0);
                                    BotTargetingService.remember(source, bot.getGameProfile().name());

                                    if (!InventoryAccessPolicy.canOpen(viewer, bot)) {
                                        source.sendError(Text.literal("You don't have permission to open this bot's inventory."));
                                        return 0;
                                    }

                                    boolean ok = BotInventoryAccess.openBotInventory(viewer, bot);
                                    if (!ok) {
                                        if (Frens.isOperator(viewer)) {
                                            source.sendError(Text.literal("Failed to open bot inventory."));
                                        } else {
                                            source.sendError(Text.literal("Out of range or wrong dimension."));
                                        }
                                        return 0;
                                    }
                                    return 1;
                                })
                                .then(CommandManager.argument("alias", StringArgumentType.string())
                                        .executes(ctx -> {
                                            ServerCommandSource source = ctx.getSource();
                                            ServerPlayerEntity viewer = source.getPlayer(); // returns null if console; handle below
                                            if (viewer == null) {
                                                source.sendError(Text.literal("Run from a player, not console."));
                                                return 0;
                                            }

                                            String alias = StringArgumentType.getString(ctx, "alias");
                                            List<ServerPlayerEntity> targets = BotTargetingService.resolve(source, alias);
                                            if (targets.size() != 1) {
                                                source.sendError(Text.literal("Specify exactly one bot to open."));
                                                return 0;
                                            }
                                            ServerPlayerEntity bot = targets.get(0);
                                            BotTargetingService.remember(source, bot.getGameProfile().name());

                                            // Ownership / op check (see Section 3)
                                            if (!InventoryAccessPolicy.canOpen(viewer, bot)) {
                                                source.sendError(Text.literal("You don't have permission to open this bot's inventory."));
                                                return 0;
                                            }

	                                            boolean ok = BotInventoryAccess.openBotInventory(viewer, bot);
	                                            if (!ok) {
	                                                if (Frens.isOperator(viewer)) {
	                                                    source.sendError(Text.literal("Failed to open bot inventory."));
	                                                } else {
	                                                    source.sendError(Text.literal("Out of range or wrong dimension."));
	                                                }
	                                                return 0;
	                                            }
	                                            return 1;
	                                        })
	                                )
                        )
                        .then(CommandManager.argument("inline", StringArgumentType.greedyString())
                                .executes(context -> executeInlineBotCommand(context, StringArgumentType.getString(context, "inline"))))
            );

	            dispatcher.register(
	                    literal("equip")
                            .requires(Frens::hasBotCommandPermission)
	                            .executes(context -> {
	                                ServerPlayerEntity player = context.getSource().getPlayer();
	                                if (player == null) {
                                    throw new SimpleCommandExceptionType(Text.literal("Specify a player when running from console or command blocks.")).create();
                                }
                                return executeEquip(context, player);
                            })
                            .then(CommandManager.argument("player", EntityArgumentType.player())
                                    .executes(context -> executeEquip(context, EntityArgumentType.getPlayer(context, "player")))
                            )
            );

                // Register admin tooling for survival recruitment mode as a separate command tree.
                // This avoids accidental nesting under other subcommands in the huge /bot tree above.
	                dispatcher.register(
	                    literal("bot")
                            .requires(Frens::hasBotCommandPermission)
	                        .then(literal("recruit")
	                            .then(literal("status")
	                                .executes(context -> executeRecruitStatus(context))
                            )
                                .then(literal("mode_access")
                                    .then(literal("status")
                                        .executes(context -> executeRecruitModeAccessStatus(context))
                                    )
                                    .then(literal("allow")
                                        .then(CommandManager.argument("player", EntityArgumentType.player())
                                            .executes(context -> executeRecruitModeAccessAllow(
                                                    context,
                                                    EntityArgumentType.getPlayer(context, "player"),
                                                    true))
                                        )
                                    )
                                    .then(literal("revoke")
                                        .then(CommandManager.argument("player", EntityArgumentType.player())
                                            .executes(context -> executeRecruitModeAccessAllow(
                                                    context,
                                                    EntityArgumentType.getPlayer(context, "player"),
                                                    false))
                                        )
                                    )
                                    .then(literal("clear")
                                        .executes(context -> executeRecruitModeAccessClear(context))
                                    )
                                )
                            .then(literal("reset")
                                .executes(context -> executeRecruitReset(context))
                            )
                            .then(literal("enable")
                                .executes(context -> executeRecruitEnable(context, true))
                            )
                            .then(literal("disable")
                                .executes(context -> executeRecruitEnable(context, false))
                            )
                            .then(literal("setstage")
                                .then(CommandManager.argument("stage", IntegerArgumentType.integer(0))
                                    .executes(context -> executeRecruitSetStage(context, IntegerArgumentType.getInteger(context, "stage")))
                                )
                            )
                        )
                );
        });
    }


    private static GameMode parseGameModeOrNull(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (v) {
            case "creative", "c" -> GameMode.CREATIVE;
            case "survival", "s" -> GameMode.SURVIVAL;
            default -> null;
        };
    }

    private static String normalizeSpawnModeOrNull(String rawMode) {
        if (rawMode == null) {
            return null;
        }
        String mode = rawMode.trim().toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "admin", "play" -> "admin";
            case "questing", "quest" -> "questing";
            case "training", "train" -> "training";
            default -> null;
        };
    }

    private static boolean isAdminLikeSpawnMode(String normalizedMode) {
        return "admin".equalsIgnoreCase(normalizedMode) || "questing".equalsIgnoreCase(normalizedMode);
    }

    private static ServerPlayerEntity getIssuerOrNull(CommandContext<ServerCommandSource> context) {
        if (context == null || context.getSource() == null) {
            return null;
        }
        // In this MC/Fabric version, getPlayer() returns null for console/command blocks.
        return context.getSource().getPlayer();
    }

    private static boolean hasOperatorPermissions(CommandContext<ServerCommandSource> context) {
        return Frens.hasBotCommandPermission(context.getSource());
    }

    private static int executeRecruitStatus(CommandContext<ServerCommandSource> context) {
        if (!hasOperatorPermissions(context)) {
            ChatUtils.sendSystemMessage(context.getSource(), "You must be an operator to use /bot recruit.");
            return 0;
        }
        MinecraftServer server = context.getSource().getServer();
        if (server == null || Frens.CONFIG == null) {
            ChatUtils.sendSystemMessage(context.getSource(), "Recruitment status unavailable: server/config not ready.");
            return 0;
        }

        String worldKey = server.getSaveProperties().getLevelName();
        ManualConfig.SurvivalRecruitmentState st = Frens.CONFIG.getOrCreateSurvivalRecruitmentState(worldKey);
        boolean enabled = SurvivalRecruitmentService.isEnabled(server);

        ChatUtils.sendSystemMessage(context.getSource(), "Survival recruitment mode: " + enabled);
        ChatUtils.sendSystemMessage(context.getSource(), "World key: " + (worldKey == null ? "default" : worldKey));
        ChatUtils.sendSystemMessage(context.getSource(), "Mode selection done: " + st.isModeSelectionDone()
                + " (selected=" + (st.getSelectedWorldMode() == null ? "unset" : st.getSelectedWorldMode()) + ")");
        Map<String, String> delegates = st.getModeSelectionDelegatesByUuid();
        ChatUtils.sendSystemMessage(context.getSource(), "Mode delegates: " + delegates.size());
        if (!delegates.isEmpty()) {
            List<String> names = new ArrayList<>(delegates.values());
            names.sort(String.CASE_INSENSITIVE_ORDER);
            ChatUtils.sendSystemMessage(context.getSource(), "Delegate names: " + String.join(", ", names));
        }
        ChatUtils.sendSystemMessage(context.getSource(), "Recruited: " + st.isRecruited() + " (botAlias=" + st.getBotAlias() + ")");
        if (st.isRecruited()) {
            String by = (st.getRecruitedByName() == null || st.getRecruitedByName().isBlank()) ? "unknown" : st.getRecruitedByName();
            String uuid = (st.getRecruitedByUuid() == null || st.getRecruitedByUuid().isBlank()) ? "unknown" : st.getRecruitedByUuid();
            ChatUtils.sendSystemMessage(context.getSource(), "Recruited by: " + by + " (" + uuid + ")");
            ChatUtils.sendSystemMessage(context.getSource(), "Recruited at (epoch ms): " + st.getRecruitedAtEpochMs());
        }

        ChatUtils.sendSystemMessage(context.getSource(), "Companion quest: stage=" + st.getCompanionQuestStage() + " permanent=" + st.isPermanentCompanion());
        if (st.isCompanionAnchorSet()) {
            BlockPos anchor = BlockPos.fromLong(st.getCompanionAnchorPos());
            String dim = st.getCompanionAnchorDimension();
            ChatUtils.sendSystemMessage(context.getSource(), "Anchor: set=true dim=" + (dim == null ? "?" : dim)
                    + " pos=" + anchor.getX() + "," + anchor.getY() + "," + anchor.getZ());
        } else {
            ChatUtils.sendSystemMessage(context.getSource(), "Anchor: set=false");
        }

        return 1;
    }

    private static int executeRecruitModeAccessStatus(CommandContext<ServerCommandSource> context) {
        if (!hasOperatorPermissions(context)) {
            ChatUtils.sendSystemMessage(context.getSource(), "You must be an operator to use /bot recruit.");
            return 0;
        }
        MinecraftServer server = context.getSource().getServer();
        if (server == null || Frens.CONFIG == null) {
            ChatUtils.sendSystemMessage(context.getSource(), "Mode access status unavailable: server/config not ready.");
            return 0;
        }

        ManualConfig.SurvivalRecruitmentState st = SurvivalRecruitmentService.getState(server);
        Map<String, String> delegates = st.getModeSelectionDelegatesByUuid();
        ChatUtils.sendSystemMessage(context.getSource(), "Mode delegates: " + delegates.size());
        if (delegates.isEmpty()) {
            ChatUtils.sendSystemMessage(context.getSource(), "No delegated players. Operators can always choose world mode.");
            return 1;
        }

        List<String> names = new ArrayList<>(delegates.values());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        ChatUtils.sendSystemMessage(context.getSource(), "Delegated players: " + String.join(", ", names));
        return 1;
    }

    private static int executeRecruitModeAccessAllow(CommandContext<ServerCommandSource> context,
                                                     ServerPlayerEntity target,
                                                     boolean allowed) {
        if (!hasOperatorPermissions(context)) {
            ChatUtils.sendSystemMessage(context.getSource(), "You must be an operator to use /bot recruit.");
            return 0;
        }
        MinecraftServer server = context.getSource().getServer();
        if (server == null || Frens.CONFIG == null) {
            ChatUtils.sendSystemMessage(context.getSource(), "Mode access update unavailable: server/config not ready.");
            return 0;
        }
        if (target == null || target.isRemoved()) {
            ChatUtils.sendSystemMessage(context.getSource(), "Target player not found.");
            return 0;
        }

        ManualConfig.SurvivalRecruitmentState st = SurvivalRecruitmentService.getState(server);
        st.setDelegateWorldModeChoice(target.getUuidAsString(), target.getName().getString(), allowed);
        Frens.CONFIG.save();

        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (p == null || p.isRemoved() || (p instanceof createFakePlayer)) {
                continue;
            }
            SurvivalRecruitmentService.sendRecruitmentState(p);
        }

        if (allowed) {
            ChatUtils.sendSystemMessage(context.getSource(), "Granted world-mode selection access to " + target.getName().getString() + ".");
        } else {
            ChatUtils.sendSystemMessage(context.getSource(), "Revoked world-mode selection access from " + target.getName().getString() + ".");
        }
        return 1;
    }

    private static int executeRecruitModeAccessClear(CommandContext<ServerCommandSource> context) {
        if (!hasOperatorPermissions(context)) {
            ChatUtils.sendSystemMessage(context.getSource(), "You must be an operator to use /bot recruit.");
            return 0;
        }
        MinecraftServer server = context.getSource().getServer();
        if (server == null || Frens.CONFIG == null) {
            ChatUtils.sendSystemMessage(context.getSource(), "Mode access update unavailable: server/config not ready.");
            return 0;
        }

        ManualConfig.SurvivalRecruitmentState st = SurvivalRecruitmentService.getState(server);
        int previous = st.getModeSelectionDelegatesByUuid().size();
        st.clearWorldModeDelegates();
        Frens.CONFIG.save();

        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (p == null || p.isRemoved() || (p instanceof createFakePlayer)) {
                continue;
            }
            SurvivalRecruitmentService.sendRecruitmentState(p);
        }

        ChatUtils.sendSystemMessage(context.getSource(), "Cleared " + previous + " delegated world-mode access entr" + (previous == 1 ? "y." : "ies."));
        return 1;
    }

    private static int executeRecruitEnable(CommandContext<ServerCommandSource> context, boolean enabled) {
        if (!hasOperatorPermissions(context)) {
            ChatUtils.sendSystemMessage(context.getSource(), "You must be an operator to use /bot recruit.");
            return 0;
        }
        if (Frens.CONFIG == null) {
            ChatUtils.sendSystemMessage(context.getSource(), "Recruitment toggle unavailable: config not ready.");
            return 0;
        }

        MinecraftServer server = context.getSource().getServer();
        String botAlias = "Jake";
        if (server != null) {
            String targetWorldMode = enabled ? "questing" : "admin";
            ManualConfig.SurvivalRecruitmentState before = SurvivalRecruitmentService.getState(server);
            String currentWorldMode = before != null ? before.getSelectedWorldMode() : null;
            boolean shouldWarnAboutSwitch = before != null
                    && before.isModeSelectionDone()
                    && currentWorldMode != null
                    && !currentWorldMode.isBlank()
                    && !currentWorldMode.equalsIgnoreCase(targetWorldMode);
            if (shouldWarnAboutSwitch) {
                String worldKey = server.getSaveProperties().getLevelName();
                if (worldKey == null || worldKey.isBlank()) {
                    worldKey = "default";
                }
                String confirmKey = (worldKey + "->" + targetWorldMode).toLowerCase(Locale.ROOT);
                long now = System.currentTimeMillis();
                Long confirmUntil = RECRUIT_MODE_SWITCH_CONFIRM_UNTIL_MS.get(confirmKey);
                if (confirmUntil == null || confirmUntil < now) {
                    RECRUIT_MODE_SWITCH_CONFIRM_UNTIL_MS.put(confirmKey, now + 12_000L);
                    ChatUtils.sendSystemMessage(context.getSource(),
                            "Warning: switching world mode from '" + currentWorldMode + "' to '" + targetWorldMode +
                                    "' may cause future questline/build progression conflicts.");
                    ChatUtils.sendSystemMessage(context.getSource(),
                            "Run the same command again within 12 seconds to confirm.");
                    return 0;
                }
                RECRUIT_MODE_SWITCH_CONFIRM_UNTIL_MS.remove(confirmKey);
            }

            SurvivalRecruitmentService.setWorldMode(server, enabled, context.getSource().getName());
            ManualConfig.SurvivalRecruitmentState st = SurvivalRecruitmentService.getState(server);
            botAlias = st != null ? st.getBotAlias() : botAlias;
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                if (p != null && !(p instanceof createFakePlayer)) {
                    SurvivalRecruitmentService.sendRecruitmentState(p);
                    if (!enabled) {
                        ServerPlayNetworking.send(p, new RecruitmentPromptPayload(false, botAlias));
                    }
                }
            }
        }

        ChatUtils.sendSystemMessage(context.getSource(), "Survival recruitment mode for this world set to " + enabled + ".");
        return 1;
    }

    private static int executeRecruitReset(CommandContext<ServerCommandSource> context) {
        if (!hasOperatorPermissions(context)) {
            ChatUtils.sendSystemMessage(context.getSource(), "You must be an operator to use /bot recruit.");
            return 0;
        }
        MinecraftServer server = context.getSource().getServer();
        if (server == null || Frens.CONFIG == null) {
            ChatUtils.sendSystemMessage(context.getSource(), "Recruitment reset unavailable: server/config not ready.");
            return 0;
        }

        String worldKey = server.getSaveProperties().getLevelName();
        ManualConfig.SurvivalRecruitmentState st = Frens.CONFIG.getOrCreateSurvivalRecruitmentState(worldKey);
        st.setRecruited(false);
        st.setRecruitedByUuid(null);
        st.setRecruitedByName(null);
        st.setRecruitedAtEpochMs(0L);
        // Keep botAlias as-is to preserve customization.

        st.setCompanionQuestStage(0);
        st.setPermanentCompanion(false);
        st.setCompanionAnchorSet(false);
        st.setCompanionAnchorDimension(null);
        st.setCompanionAnchorPos(0L);

        st.setCompanionDead(false);
        st.setCompanionDiedAtEpochMs(0L);
        st.setCompanionDiedDimension(null);
        st.setCompanionDiedPos(0L);

        Frens.CONFIG.save();

        String alias = st.getBotAlias();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (p != null && !(p instanceof createFakePlayer)) {
                SurvivalRecruitmentService.sendRecruitmentState(p);
                ServerPlayNetworking.send(p, new CompanionQuestStatePayload(alias, 0, false));
            }
        }

        ChatUtils.sendSystemMessage(context.getSource(), "Survival recruitment state reset for world '" + (worldKey == null ? "default" : worldKey) + "'.");
        return 1;
    }

    private static int executeRecruitSetStage(CommandContext<ServerCommandSource> context, int stage) {
        if (!hasOperatorPermissions(context)) {
            ChatUtils.sendSystemMessage(context.getSource(), "You must be an operator to use /bot recruit.");
            return 0;
        }
        MinecraftServer server = context.getSource().getServer();
        if (server == null || Frens.CONFIG == null) {
            ChatUtils.sendSystemMessage(context.getSource(), "Setstage unavailable: server/config not ready.");
            return 0;
        }
        if (!SurvivalRecruitmentService.isEnabled(server)) {
            ChatUtils.sendSystemMessage(context.getSource(), "Survival recruitment mode is disabled. Enable it first with /bot recruit enable.");
            return 0;
        }

        ManualConfig.SurvivalRecruitmentState st = SurvivalRecruitmentService.getState(server);
        if (st == null || !st.isRecruited()) {
            ChatUtils.sendSystemMessage(context.getSource(), "World is not recruited yet. Use /bot recruit status or complete recruitment first.");
            return 0;
        }

        st.setCompanionQuestStage(stage);
        Frens.CONFIG.save();

        String alias = st.getBotAlias();
        int updatedStage = st.getCompanionQuestStage();
        boolean perm = st.isPermanentCompanion();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (p != null && !(p instanceof createFakePlayer)) {
                ServerPlayNetworking.send(p, new CompanionQuestStatePayload(alias, updatedStage, perm));
            }
        }

        ChatUtils.sendSystemMessage(context.getSource(), "Companion quest stage set to " + updatedStage + " for botAlias='" + alias + "'.");
        return 1;
    }

    private static void spawnBot(CommandContext<ServerCommandSource> context, String spawnMode, String explicitGameMode) {
        try {
            MinecraftServer server = context.getSource().getServer(); // gets the minecraft server
            String requestedSpawnMode = spawnMode == null ? "" : spawnMode.trim();
            String normalizedSpawnMode = normalizeSpawnModeOrNull(requestedSpawnMode);
            if (normalizedSpawnMode == null) {
                ServerCommandSource serverSource = server.getCommandSource();
                LOGGER.warn("spawnBot: invalid spawn mode '{}' for requested bot", requestedSpawnMode);
                ChatUtils.sendSystemMessage(serverSource, "Invalid spawn mode!");
                ChatUtils.sendSystemMessage(serverSource,
                        "Usage: /bot spawn <your bot's name> <spawnMode: admin|questing|training> [gamemode: survival|creative]");
                ChatUtils.sendSystemMessage(serverSource,
                        "Legacy aliases: play -> admin, quest -> questing, train -> training");
                return;
            }

            // Survival recruitment gating: don't let non-ops bypass recruitment or resurrection.
            // Non-ops may only spawn the recruited companion (and only in play mode, and only if alive).
            if (SurvivalRecruitmentService.isEnabled(server)) {
                ServerPlayerEntity issuer = context.getSource().getEntity() instanceof ServerPlayerEntity p ? p : null;
                if (issuer != null && !Frens.isOperator(issuer)) {
                    ManualConfig.SurvivalRecruitmentState st = SurvivalRecruitmentService.getState(server);
                    if (st == null || !st.isRecruited()) {
                        ChatUtils.sendSystemMessage(context.getSource(),
                                "Bots are unavailable until recruited. Find a village and recruit first (or ask an admin)."
                        );
                        return;
                    }

                    String requested = StringArgumentType.getString(context, "bot_name");
                    String recruitedAlias = st.getBotAlias();
                    if (requested == null || recruitedAlias == null || !recruitedAlias.equalsIgnoreCase(requested.trim())) {
                        ChatUtils.sendSystemMessage(context.getSource(), "You can only spawn the recruited companion ('" + recruitedAlias + "') in this world.");
                        return;
                    }

                    if (!isAdminLikeSpawnMode(normalizedSpawnMode)) {
                        ChatUtils.sendSystemMessage(context.getSource(), "Only admin/questing spawning is allowed for the recruited companion.");
                        return;
                    }

                    if (st.isCompanionDead()) {
                        ChatUtils.sendSystemMessage(context.getSource(), recruitedAlias + " is dead.");
                        ChatUtils.sendSystemMessage(context.getSource(), "You must perform the Nether ritual to bring them back.");
                        return;
                    }
                }
            }

            BlockPos spawnPos = getBlockPos(context);

            RegistryKey<World> dimType = context.getSource().getWorld().getRegistryKey();

            Vec2f facing = context.getSource().getRotation();

            // Center the bot in the block space to avoid corner collisions
            Vec3d pos = new Vec3d(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);

            GameMode mode = GameMode.SURVIVAL;
            GameMode parsedExplicit = parseGameModeOrNull(explicitGameMode);
            if (parsedExplicit != null) {
                mode = parsedExplicit;
            } else if (Frens.CONFIG != null) {
                ManualConfig.BotControlSettings ctrl = Frens.CONFIG.getEffectiveBotControl(StringArgumentType.getString(context, "bot_name"));
                if (ctrl != null && "creative".equalsIgnoreCase(ctrl.getGameMode())) {
                    mode = GameMode.CREATIVE;
                }
            }

            botName = StringArgumentType.getString(context, "bot_name");

            ServerCommandSource serverSource = server.getCommandSource();
                if (!requestedSpawnMode.equalsIgnoreCase(normalizedSpawnMode)) {
                ChatUtils.sendSystemMessage(serverSource,
                    "Note: '/bot spawn " + botName + " " + requestedSpawnMode + "' maps to mode '" + normalizedSpawnMode + "'.");
                }
                if ("play".equalsIgnoreCase(requestedSpawnMode)) {
                ChatUtils.sendSystemMessage(serverSource,
                    "Heads up: 'play' is now a legacy alias. Prefer 'admin' (or 'questing').");
                }

            LOGGER.info("spawnBot starting: botName={}, mode={}, dimType={}, pos={}, facingYaw={}, facingPitch={}",
                    botName, normalizedSpawnMode, dimType.getValue(), pos, facing.y, facing.x);

            ServerPlayerEntity existingBot = server.getPlayerManager().getPlayer(botName);
            if (existingBot != null) {
                if (existingBot.isRemoved() || !existingBot.isAlive()) {
                    LOGGER.warn("spawnBot: found stale bot instance for {} (removed={} alive={}); forcing removal and respawn",
                            botName, existingBot.isRemoved(), existingBot.isAlive());
                    BotPersistenceService.removeBot(existingBot);
                    existingBot = null;
                }
            }
            if (existingBot != null) {
                LOGGER.info("spawnBot: existing bot {} found, aborting active tasks", botName);
                TaskService.forceAbort(existingBot.getUuid(), "§cSpawning bot '" + botName + "'.");

                // IMPORTANT: avoid spawning a duplicate fake player with the same UUID (causes “Force-added player with duplicate UUID”
                // and can lead to commands targeting a different in-memory instance than the one you see).
                ServerWorld targetWorld = server.getWorld(dimType);
                if (targetWorld == null) {
                    LOGGER.error("spawnBot: world {} missing; cannot reposition existing bot {}", dimType.getValue(), botName);
                    ChatUtils.sendSystemMessage(serverSource, "Error: world not available for spawning " + botName + ".");
                    return;
                }
                if (!(existingBot instanceof createFakePlayer)) {
                    ChatUtils.sendSystemMessage(serverSource,
                            "Error: A real player named '" + botName + "' is online; cannot spawn a bot with that name.");
                    return;
                }

                isTrainingMode = "training".equalsIgnoreCase(normalizedSpawnMode);

                existingBot.teleport(targetWorld, pos.x, pos.y, pos.z, java.util.Set.of(), (float) facing.y, (float) facing.x, true);
                Objects.requireNonNull(existingBot.getAttributeInstance(EntityAttributes.KNOCKBACK_RESISTANCE)).setBaseValue(0.0);
                existingBot.interactionManager.changeGameMode(mode);
                RespawnHandler.registerRespawnListener(existingBot);
                BotEventHandler.registerBot(existingBot);
                ServerPlayerEntity owner = context.getSource().getEntity() instanceof ServerPlayerEntity player ? player : null;
                if (owner != null) {
                    Frens.CONFIG.ensureOwner(botName, owner.getUuid(), owner.getName().getString());
                }
                AutoFaceEntity.startAutoFace(existingBot);

                BotEventHandler.rememberSpawn(targetWorld, pos, facing.y, facing.x);
                LOGGER.info("spawnBot: repositioned existing bot {} at {} (mode={})", botName, spawnPos.toShortString(), normalizedSpawnMode);

                // If an admin force-spawns a play bot before recruitment, treat the world as recruited to avoid UI/gating confusion.
                if (isAdminLikeSpawnMode(normalizedSpawnMode)
                        && SurvivalRecruitmentService.isEnabled(server)
                        && !SurvivalRecruitmentService.isWorldRecruited(server)
                        && Frens.CONFIG != null) {
                    ManualConfig.SurvivalRecruitmentState updated = Frens.CONFIG.getOrCreateSurvivalRecruitmentState(server.getSaveProperties().getLevelName());
                    updated.setBotAlias(botName);
                    updated.setRecruited(true);
                    ServerPlayerEntity issuer = context.getSource().getEntity() instanceof ServerPlayerEntity p ? p : null;
                    if (issuer != null) {
                        updated.setRecruitedByUuid(issuer.getUuidAsString());
                        updated.setRecruitedByName(issuer.getName().getString());
                    }
                    updated.setRecruitedAtEpochMs(System.currentTimeMillis());
                    Frens.CONFIG.save();
                    for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                        if (p != null && !(p instanceof createFakePlayer)) {
                            SurvivalRecruitmentService.sendRecruitmentState(p);
                            ServerPlayNetworking.send(p, new RecruitmentPromptPayload(false, botName));
                        }
                    }
                }
                return;
            }

            if ("training".equalsIgnoreCase(normalizedSpawnMode)) {

                LOGGER.info("spawnBot: entering training branch for {}", botName);

                createFakePlayer.createFake(
                        botName,
                        server,
                        pos,
                        facing.y,
                        facing.x,
                        dimType,
                        mode,
                        false
                );

                ServerWorld spawnWorld = server.getWorld(dimType);
                if (spawnWorld != null) {
                    LOGGER.info("spawnBot: remembering spawn for training bot {}", botName);
                    BotEventHandler.rememberSpawn(spawnWorld, pos, facing.y, facing.x);
                }

                isTrainingMode = true;

                LOGGER.info("Spawned new training bot {}!", botName);

                ServerPlayerEntity bot = server.getPlayerManager().getPlayer(botName);

                if (bot != null) {

                    Objects.requireNonNull(bot.getAttributeInstance(EntityAttributes.KNOCKBACK_RESISTANCE)).setBaseValue(0.0);

                    RespawnHandler.registerRespawnListener(bot);
                    BotEventHandler.registerBot(bot);
                    ServerPlayerEntity owner = context.getSource().getEntity() instanceof ServerPlayerEntity player ? player : null;
                    if (owner != null) {
                        Frens.CONFIG.ensureOwner(botName, owner.getUuid(), owner.getName().getString());
                    }

                    AutoFaceEntity.startAutoFace(bot);

                } else {
                    LOGGER.error("spawnBot: training bot {} was not found after createFake", botName);
                    ChatUtils.sendSystemMessage(serverSource, "Error: " + botName + " cannot be spawned");
                }

                // don't initialize ollama client for training mode.

            } else if (isAdminLikeSpawnMode(normalizedSpawnMode)) {
                LOGGER.info("spawnBot: entering {} branch for {}", normalizedSpawnMode, botName);

                isTrainingMode = false;
                LOGGER.info("Training mode disabled for {} spawn.", normalizedSpawnMode);

                LOGGER.info("About to call createFakePlayer.createFake for {} bot {}", normalizedSpawnMode, botName);
                createFakePlayer.createFake(
                        botName,
                        server,
                        pos,
                        facing.y,
                        facing.x,
                        dimType,
                        mode,
                        false
                );
                    LOGGER.info("Returned from createFakePlayer.createFake for {} bot {}", normalizedSpawnMode, botName);

                ServerWorld spawnWorld = server.getWorld(dimType);
                if (spawnWorld != null) {
                    LOGGER.info("spawnBot: remembering spawn for {} bot {}", normalizedSpawnMode, botName);
                    BotEventHandler.rememberSpawn(spawnWorld, pos, facing.y, facing.x);
                }

                LOGGER.info("Spawned new bot {}!", botName);

                ServerPlayerEntity bot = server.getPlayerManager().getPlayer(botName);

                DebugToggleService.debug(LOGGER, "Preparing for connection to language model....");

                if (bot != null) {

                    Objects.requireNonNull(bot.getAttributeInstance(EntityAttributes.KNOCKBACK_RESISTANCE)).setBaseValue(0.0);

                    DebugToggleService.debug(LOGGER, "Registering respawn listener....");

                    RespawnHandler.registerRespawnListener(bot);
                    BotEventHandler.registerBot(bot);
                    ServerPlayerEntity owner = context.getSource().getEntity() instanceof ServerPlayerEntity player ? player : null;
                    if (owner != null) {
                        Frens.CONFIG.ensureOwner(botName, owner.getUuid(), owner.getName().getString());
                    }

                    ollamaClient.botName = botName; // set the bot's name.

                    DebugToggleService.debug(LOGGER, "Set bot's username to {}", botName);

                    String llmProvider = System.getProperty("frens.llmMode", System.getProperty("aiplayer.llmMode", "ollama"));

                    DebugToggleService.debug(LOGGER, "Using provider: {}", llmProvider);

                    switch (llmProvider) {
                        case "openai", "gpt", "google", "gemini", "anthropic", "claude", "xAI", "xai", "grok", "custom" -> {
                            LLMClient llmClient = LLMClientFactory.createClient(llmProvider);
                            if (llmClient == null) {
                                LOGGER.error("spawnBot: LLMClientFactory returned null for provider {}", llmProvider);
                                ChatUtils.sendSystemMessage(serverSource,
                                        "Error: Failed to initialize language model client for provider " + llmProvider + ".");
                                return;
                            }

                            ChatUtils.sendSystemMessage(serverSource,
                                    "Please wait while " + botName + " connects to " + llmClient.getProvider() + "'s servers.");
                            LLMServiceHandler.sendInitialResponse(bot.getCommandSource().withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS), llmClient);

                            new Thread(() -> {
                                try {
                                    while (!LLMServiceHandler.isInitialized) {
                                        try {
                                            Thread.sleep(500L); // Check every 500ms
                                        } catch (InterruptedException e) {
                                            LOGGER.error("LLM service initialization interrupted.", e);
                                            Thread.currentThread().interrupt();
                                            return;
                                        }
                                    }

                                    // initialization succeeded, continue:
                                    AutoFaceEntity.startAutoFace(bot);
                                } catch (Exception e) {
                                    LOGGER.error("Error in LLM initialization thread for bot {}", botName, e);
                                }
                            }, "LLM-Init-" + botName).start();
                        }

                        case "ollama" -> {
                            ChatUtils.sendSystemMessage(serverSource,
                                    "Please wait while " + botName + " connects to the language model.");
                            ollamaClient.initializeOllamaClient();

                            new Thread(() -> {
                                try {
                                    while (!ollamaClient.isInitialized) {
                                        try {
                                            Thread.sleep(500L); // Check every 500ms
                                        } catch (InterruptedException e) {
                                            LOGGER.error("Ollama client initialization interrupted.", e);
                                            Thread.currentThread().interrupt();
                                            return;
                                        }
                                    }

                                    // initialization succeeded, continue:
                                    ollamaClient.sendInitialResponse(bot.getCommandSource().withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS));
                                    AutoFaceEntity.startAutoFace(bot);
                                } catch (Exception e) {
                                    LOGGER.error("Error in Ollama initialization thread for bot {}", botName, e);
                                }
                            }, "Ollama-Init-" + botName).start();
                        }

                        default -> {
                            LOGGER.warn("Unsupported provider detected: {}. Defaulting to Ollama client", llmProvider);
                            ChatUtils.sendSystemMessage(serverSource,
                                    "Warning! Unsupported provider detected. Defaulting to Ollama client");
                            ChatUtils.sendSystemMessage(serverSource,
                                    "Please wait while " + botName + " connects to the language model.");
                            ollamaClient.initializeOllamaClient();

                            new Thread(() -> {
                                try {
                                    while (!ollamaClient.isInitialized) {
                                        try {
                                            Thread.sleep(500L); // Check every 500ms
                                        } catch (InterruptedException e) {
                                            LOGGER.error("Ollama client initialization interrupted.", e);
                                            Thread.currentThread().interrupt();
                                            return;
                                        }
                                    }

                                    // initialization succeeded, continue:
                                    ollamaClient.sendInitialResponse(bot.getCommandSource().withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS));
                                    AutoFaceEntity.startAutoFace(bot);
                                } catch (Exception e) {
                                    LOGGER.error("Error in Ollama initialization thread (default case) for bot {}", botName, e);
                                }
                            }, "Ollama-Init-" + botName).start();
                        }
                    }

                } else {
                    LOGGER.error("spawnBot: play bot {} was not found after createFake", botName);
                    ChatUtils.sendSystemMessage(serverSource, "Error: " + botName + " cannot be spawned");
                }

                // If an admin force-spawns a play bot before recruitment, treat the world as recruited to avoid UI/gating confusion.
                if (SurvivalRecruitmentService.isEnabled(server)
                        && !SurvivalRecruitmentService.isWorldRecruited(server)
                        && Frens.CONFIG != null) {
                    ManualConfig.SurvivalRecruitmentState updated = Frens.CONFIG.getOrCreateSurvivalRecruitmentState(server.getSaveProperties().getLevelName());
                    updated.setBotAlias(botName);
                    updated.setRecruited(true);
                    ServerPlayerEntity issuer = context.getSource().getEntity() instanceof ServerPlayerEntity p ? p : null;
                    if (issuer != null) {
                        updated.setRecruitedByUuid(issuer.getUuidAsString());
                        updated.setRecruitedByName(issuer.getName().getString());
                    }
                    updated.setRecruitedAtEpochMs(System.currentTimeMillis());
                    Frens.CONFIG.save();
                    for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                        if (p != null && !(p instanceof createFakePlayer)) {
                            SurvivalRecruitmentService.sendRecruitmentState(p);
                            ServerPlayNetworking.send(p, new RecruitmentPromptPayload(false, botName));
                        }
                    }
                }

            } else {
                LOGGER.warn("spawnBot: invalid spawn mode '{}' for bot {}", spawnMode, botName);
                ChatUtils.sendSystemMessage(serverSource, "Invalid spawn mode!");
                ChatUtils.sendSystemMessage(serverSource,
                    "Usage: /bot spawn <your bot's name> <spawnMode: admin|questing|training> [gamemode: survival|creative]");
            }

        } catch (Exception e) {
            LOGGER.error("❌ Fatal error inside spawnBot for /bot spawn {} {}", botName, spawnMode, e);
            context.getSource().sendError(Text.literal(
                    "Internal error during bot spawn (see server log)."
            ));
            throw e;
        }
    }



    private static void teleportForward(CommandContext<ServerCommandSource> context) {
        MinecraftServer server = context.getSource().getServer();

        ServerPlayerEntity bot = null;
        try {bot = EntityArgumentType.getPlayer(context, "bot");} catch (CommandSyntaxException ignored) {}

        if (bot == null) {

            context.getSource().sendMessage(Text.of("The requested bot could not be found on the server!"));
            server.sendMessage(Text.literal("Error! Bot not found!"));
            LOGGER.error("The requested bot could not be found on the server!");

        }

        else {
            String botName = bot.getName().getLiteralString();

            BlockPos currentPosition = bot.getBlockPos();
            BlockPos newPosition = currentPosition.add(1, 0, 0); // Move one block forward
            bot.teleport(bot.getEntityWorld(), newPosition.getX(), newPosition.getY(), newPosition.getZ(), Set.of(), bot.getYaw(), bot.getPitch(), true);

            LOGGER.info("Teleported {} 1 positive block ahead", botName);

        }

    }

    private static void botWalk(CommandContext<ServerCommandSource> context) {

        MinecraftServer server = context.getSource().getServer();

        ServerPlayerEntity bot = null;
        try {bot = EntityArgumentType.getPlayer(context, "bot");} catch (CommandSyntaxException ignored) {}

        int travelTime = IntegerArgumentType.getInteger(context, "till");


        if (bot == null) {

            context.getSource().sendMessage(Text.of("The requested bot could not be found on the server!"));
            server.sendMessage(Text.literal("Error! Bot not found!"));
            LOGGER.error("The requested bot could not be found on the server!");

        }

        else {

            String botName = bot.getName().getLiteralString();

            ServerCommandSource botSource = bot.getCommandSource().withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS);
            moveForward(server, botSource, botName);

            scheduler.schedule(new BotStopTask(server, botSource, botName), travelTime, TimeUnit.SECONDS);


        }

    }


    private static void botJump(CommandContext<ServerCommandSource> context) {

        MinecraftServer server = context.getSource().getServer();

        ServerPlayerEntity bot = null;
        try {bot = EntityArgumentType.getPlayer(context, "bot");} catch (CommandSyntaxException ignored) {}


        if (bot == null) {

            context.getSource().sendMessage(Text.of("The requested bot could not be found on the server!"));
            server.sendMessage(Text.literal("Error! Bot not found!"));
            LOGGER.error("The requested bot could not be found on the server!");

        }

        else {

            String botName = bot.getName().getLiteralString();

            BotActions.jump(bot);


            LOGGER.info("{} jumped!", botName);


        }

    }

    private static void testChatMessage(CommandContext<ServerCommandSource> context) {

        String response = "I am doing great! It feels good to be able to chat with you again after a long time. So, how have you been doing? Are you enjoying the game world and having fun playing Minecraft with me? Let's continue chatting about whatever topic comes to mind! I love hearing from you guys and seeing your creations in the game. Don't hesitate to share anything with me, whether it's an idea, a problem, or simply something that makes you laugh. Cheers!";

        MinecraftServer server = context.getSource().getServer();

        ServerPlayerEntity bot = null;
        try {bot = EntityArgumentType.getPlayer(context, "bot");} catch (CommandSyntaxException ignored) {}

        if (bot != null) {

            ServerCommandSource botSource = bot.getCommandSource().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS).withSilent();
            ChatUtils.sendChatMessages(botSource, response);

        }
        else {
            context.getSource().sendMessage(Text.of("The requested bot could not be found on the server!"));
            server.sendMessage(Text.literal("Error! Bot not found!"));
            LOGGER.error("The requested bot could not be found on the server!");

        }

    }

    private static int executeCraftGeneric(CommandContext<ServerCommandSource> context, String item, int amount, String material, ServerPlayerEntity bot) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity commander = source.getPlayer();
        if (bot == null) {
            return 0;
        }
        UUID botUuid = bot.getUuid();
        var ticketOpt = TaskService.beginSkill("craft", source, botUuid);
        if (ticketOpt.isEmpty()) {
            ChatUtils.sendSystemMessage(source, "Another task is already running.");
            return 0;
        }
        TaskService.TaskTicket ticket = ticketOpt.get();

        skillExecutor.submit(() -> {
            int crafted = 0;
            boolean success = false;
            try {
                crafted = CraftingHelper.craftGeneric(source, bot, commander, item, amount, material);
                success = crafted > 0 && !TaskService.isAbortRequested(botUuid);
                if (crafted > 0) {
                    int finalCrafted = crafted;
                    source.getServer().execute(() ->
                            ChatUtils.sendSystemMessage(source, "Crafted " + finalCrafted + " " + item + (finalCrafted == 1 ? "" : "s") + "."));
                }
            } catch (Exception e) {
                LOGGER.error("Unexpected error in /bot craft {}", item, e);
                source.getServer().execute(() -> ChatUtils.sendSystemMessage(source, "An unexpected error occurred trying to craft that."));
            } finally {
                TaskService.complete(ticket, success);
            }
        });

        return 1;
    }

    private static int executePlaceGeneric(CommandContext<ServerCommandSource> context, String item, int count, ServerPlayerEntity bot) {
        ServerPlayerEntity commander = context.getSource().getPlayer();
        if (bot == null || commander == null) {
            return 0;
        }
        PlacementTarget target = capturePlacementTarget(commander);
        if (target == null) {
            ChatUtils.sendSystemMessage(context.getSource(), "Look at the block where you want it placed, then run /bot place " + item + ".");
            return 0;
        }

        net.minecraft.item.Item placeItem = resolvePlaceable(item);
        if (placeItem == null) {
            ChatUtils.sendSystemMessage(context.getSource(), "I can't place " + item + " yet.");
            return 0;
        }

        int placed = 0;
        BlockPos lastPlaced = null;
        for (int i = 0; i < count; i++) {
            int slot = findItem(bot, placeItem);
            if (slot == -1) {
                break;
            }
            BlockPos placedPos;
            if (placeItem == Items.CHEST && lastPlaced != null) {
                placedPos = attemptAdjacentChest(bot, lastPlaced, slot, placeItem);
                if (placedPos == null) {
                    placedPos = attemptPlacement(bot, target, slot, placeItem);
                }
            } else {
                placedPos = attemptPlacement(bot, target, slot, placeItem);
            }
            if (placedPos == null && placeItem == Items.CHEST) {
                // try adjacent from commander block if first chest failed
                placedPos = attemptAdjacentChest(bot, target.hitPos.offset(target.face), slot, placeItem);
            }
            if (placedPos == null) {
                ChatUtils.sendSystemMessage(context.getSource(), "I can't reach that spot.");
                break;
            }
            placed++;
            lastPlaced = placedPos;
        }
        if (placed > 0) {
            ChatUtils.sendSystemMessage(context.getSource(), "Placed " + placed + " " + item + (placed == 1 ? "" : "s") + ".");
        } else {
            ChatUtils.sendSystemMessage(context.getSource(), "I don't have any " + item + " to place.");
        }
        return placed > 0 ? 1 : 0;
    }

    private static int executeCook(CommandContext<ServerCommandSource> context, ServerPlayerEntity bot, String itemFilter, String fuelSpec) {
        if (bot == null) {
            return 0;
        }
        boolean started = SmeltingService.startBatchCook(bot, context.getSource(), itemFilter, fuelSpec);
        return started ? 1 : 0;
    }

    private static int executeStoreDeposit(CommandContext<ServerCommandSource> context, String amountRaw, String itemRaw, ServerPlayerEntity bot) {
        return ChestStoreService.handleDeposit(context.getSource(), bot, amountRaw, itemRaw);
    }

    private static int executeStoreWithdraw(CommandContext<ServerCommandSource> context, String amountRaw, String itemRaw, ServerPlayerEntity bot) {
        return ChestStoreService.handleWithdraw(context.getSource(), bot, amountRaw, itemRaw);
    }

    private static void botGo(CommandContext<ServerCommandSource> context) {
        MinecraftServer server = context.getSource().getServer();
        BlockPos position = BlockPosArgumentType.getBlockPos(context, "pos");
        String sprintFlag = StringArgumentType.getString(context, "sprint");

        boolean sprint;

        if (sprintFlag.equalsIgnoreCase("true")) {
            sprint = true;
        }
        else if (sprintFlag.equalsIgnoreCase("false")) {
            sprint = false;
        }
        else {
            sprint = false;
            ChatUtils.sendChatMessages(server.getCommandSource(), "Wrong argument! Command is as follows: /bot go_to <botName> <xyz> <true/false (case insensitive)>");
        }

        int x_distance = position.getX();
        int y_distance = position.getY();
        int z_distance = position.getZ();

        ServerWorld world = server.getOverworld();

        ServerPlayerEntity bot = null;
        try {
            bot = EntityArgumentType.getPlayer(context, "bot");
        } catch (CommandSyntaxException ignored) {}

        if (bot == null) {
            context.getSource().sendMessage(Text.of("The requested bot could not be found on the server!"));
            server.sendMessage(Text.literal("Error! Bot not found!"));
            LOGGER.error("The requested bot could not be found on the server!");
            return;  // stop here if no bot
        }

        // Commander-directed movement should preempt idle hobbies.
        interruptAmbientHobbyIfAny(bot, "§cInterrupted by /bot go_to.");
        // If we ordered a directed move, wait longer before starting a new idle hobby.
        BotIdleHobbiesService.snoozeFor(bot, 3_600L);

        String botName = bot.getName().getLiteralString();
        ServerCommandSource botSource = bot.getCommandSource().withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS);

        server.sendMessage(Text.literal("Finding the shortest path to the target, please wait patiently if the game seems hung"));

        ServerPlayerEntity finalBot = bot;

        server.execute(() -> {
            try {
                // ✅ Calculate the path (PathNode version)
                List<PathFinder.PathNode> rawPath = PathFinder.calculatePath(finalBot.getBlockPos(), new BlockPos(x_distance, y_distance, z_distance), world);

                // ✅ Simplify + filter
                List<PathFinder.PathNode> finalPath = PathFinder.simplifyPath(rawPath, world);

                LOGGER.info("Path output: {}", finalPath);

                Queue<Segment> segments = convertPathToSegments(finalPath, sprint);

                LOGGER.info("Generated segments: {}", segments);


                // ✅ Trace the path — your tracePath now expects PathNode
                PathTracer.tracePath(server, botSource, botName, segments, sprint);
            } catch (Exception e) {
                LOGGER.error("An unexpected error occurred in /bot go_to command", e);
                ChatUtils.sendChatMessages(server.getCommandSource(), "An unexpected error occurred trying to execute that command.");
            }
        });
    }

    /**
     * Formats a single ItemStack for chat, including durability if applicable.
     */
    private static String formatItemForChat(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "EMPTY";
        }
        String name = stack.getName().getString();
        int count = stack.getCount();

        // Durability (if damageable)
        try {
            if (stack.isDamageable()) {
                int max = stack.getMaxDamage();
                int dmg = stack.getDamage();
                int remaining = Math.max(0, max - dmg);
                int pct = max > 0 ? Math.round((remaining * 100f) / max) : 100;
                return name + " ×" + count + " (" + pct + "%)";
            }
        } catch (Throwable ignored) {
            // Be defensive against mapping/version differences
        }
        return name + " ×" + count;
    }

    /**
     * Sends lines to chat in pages to avoid overflow.
     */
    private static void sendPaged(ServerCommandSource source, String header, java.util.List<String> lines) {
        final int PAGE = 12;
        if (lines == null || lines.isEmpty()) {
            ChatUtils.sendChatMessages(source, header + "\n(empty)");
            return;
        }
        int total = lines.size();
        int pages = (total + PAGE - 1) / PAGE;
        for (int p = 0; p < pages; p++) {
            int from = p * PAGE;
            int to = Math.min(from + PAGE, total);
            StringBuilder sb = new StringBuilder();
            if (p == 0) {
                sb.append(header).append("\n");
            } else {
                sb.append(header).append(" (continued)\n");
            }
            for (int i = from; i < to; i++) {
                sb.append(lines.get(i));
                if (i + 1 < to) sb.append("\n");
            }
            ChatUtils.sendChatMessages(source.withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS), sb.toString());
        }
    }



    // === Inventory summary to chat (V2, MC 1.21-safe) ===
    static int executeInventorySummaryTargetsV2(
            com.mojang.brigadier.context.CommandContext<net.minecraft.server.command.ServerCommandSource> context,
            String targetArg
    ) {
        net.minecraft.server.command.ServerCommandSource source = context.getSource();
        net.minecraft.server.network.ServerPlayerEntity bot;

        try {
            if (targetArg == null || targetArg.isBlank()) {
                bot = getActiveBotOrThrow(context);
            } else {
                bot = source.getServer().getPlayerManager().getPlayer(targetArg);
            }
        } catch (Exception e) {
            source.sendError(net.minecraft.text.Text.literal("No active bot selected."));
            return 0;
        }

        if (bot == null) {
            source.sendError(net.minecraft.text.Text.literal(
                    "Bot" + (targetArg != null ? " '" + targetArg + "'" : "") + " not found."));
            return 0;
        }

        net.minecraft.entity.player.PlayerInventory inv = bot.getInventory();
        java.util.List<String> lines = new java.util.ArrayList<>();

        // Hotbar (only non-empty, labelled 1..9)
        lines.add("§6Hotbar§r");
        boolean anyHotbar = false;
        for (int i = 0; i < 9; i++) {
            net.minecraft.item.ItemStack s = inv.getStack(i);
            if (!s.isEmpty()) {
                lines.add(" " + (i + 1) + ": " + formatItemForChat(s));
                anyHotbar = true;
            }
        }
        if (!anyHotbar) {
            lines.add(" (empty)");
        }

        // Main (non-empty only)
        java.util.List<String> main = new java.util.ArrayList<>();
        for (int i = 9; i <= 35; i++) {
            net.minecraft.item.ItemStack s = inv.getStack(i);
            if (!s.isEmpty()) {
                main.add(formatItemForChat(s));
            }
        }
        if (!main.isEmpty()) {
            lines.add("§6Main§r");
            lines.addAll(main);
        }

        // Armor (1.21-safe via getEquippedStack)
        lines.add("§6Armor§r");
        net.minecraft.item.ItemStack head  = bot.getEquippedStack(net.minecraft.entity.EquipmentSlot.HEAD);
        net.minecraft.item.ItemStack chest = bot.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST);
        net.minecraft.item.ItemStack legs  = bot.getEquippedStack(net.minecraft.entity.EquipmentSlot.LEGS);
        net.minecraft.item.ItemStack feet  = bot.getEquippedStack(net.minecraft.entity.EquipmentSlot.FEET);
        lines.add(" Head:  " + (head.isEmpty()  ? "-" : formatItemForChat(head)));
        lines.add(" Chest: " + (chest.isEmpty() ? "-" : formatItemForChat(chest)));
        lines.add(" Legs:  " + (legs.isEmpty()  ? "-" : formatItemForChat(legs)));
        lines.add(" Feet:  " + (feet.isEmpty()  ? "-" : formatItemForChat(feet)));

        // Offhand
        net.minecraft.item.ItemStack off = bot.getOffHandStack();
        lines.add("§6Offhand§r " + (off.isEmpty() ? "-" : formatItemForChat(off)));

        // Stats (basic visibility for persistence verification)
        float health = bot.getHealth();
        float maxHealth = bot.getMaxHealth();
        int food = bot.getHungerManager().getFoodLevel();
        float saturation = bot.getHungerManager().getSaturationLevel();
        int xpLevel = bot.experienceLevel;
        float xpProgress = bot.experienceProgress;
        int xpTotal = bot.totalExperience;
        lines.add("§6Stats§r");
        lines.add(String.format(Locale.ROOT, " Health: %.1f/%.1f  Food: %d  Sat: %.1f", health, maxHealth, food, saturation));
        lines.add(String.format(Locale.ROOT, " XP: level %d (%.0f%%)  TotalXP: %d", xpLevel, xpProgress * 100.0F, xpTotal));

        String header = "Inventory for " + bot.getName().getString();
        sendPaged(source, header, lines);
        return 1;
    }

    /**
     * Compatibility overload for command bindings that pass an explicit recipient.
     * Delegates to the 4-arg version (recipient = invoking player).
     */
    private static int executeGive(CommandContext<ServerCommandSource> context,
                                 ServerPlayerEntity explicitBot,
                                 ServerPlayerEntity explicitRecipient,
                                 String itemQuery,
                                 int requestedCount) {
        ServerCommandSource source = context.getSource();
        MinecraftServer server = source.getServer();
        ServerWorld world = source.getWorld();

        ServerPlayerEntity bot;
        try {
            bot = (explicitBot != null) ? explicitBot : getActiveBotOrThrow(context);
        } catch (Exception e) {
            source.sendError(Text.literal("No active bot selected."));
            return 0;
        }
        if (bot == null) {
            source.sendError(Text.literal("Bot not found."));
            return 0;
        }

        ServerPlayerEntity recipient = explicitRecipient;
        if (recipient == null) {
            try {
                recipient = source.getPlayer();
            } catch (Exception e) {
                // This can happen from console, handled by the next check
            }
        }
        if (recipient == null) {
            source.sendError(Text.literal("Specify a player when running from console/command blocks."));
            return 0;
        }


        if (itemQuery == null || itemQuery.isBlank()) {
            ChatUtils.sendChatMessages(bot.getCommandSource().withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS),
                    "You need to specify an item id, e.g., iron_ingot");
            return 0;
        }

        Item item = resolveItemFromQuery(itemQuery);
        if (item == null || item == Items.AIR) {
            ChatUtils.sendChatMessages(bot.getCommandSource().withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS),
                    "I don't recognize that item.");
            return 0;
        }

        if (requestedCount <= 0) {
            requestedCount = 1;
        }

        PlayerInventory inv = bot.getInventory();
        List<Integer> candidateSlots = new ArrayList<>();
        for (int slot = 0; slot < inv.size(); slot++) {
            if (!inv.getStack(slot).isEmpty() && inv.getStack(slot).isOf(item)) {
                candidateSlots.add(slot);
            }
        }

        if (candidateSlots.isEmpty()) {
            ChatUtils.sendChatMessages(bot.getCommandSource().withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS), "I don't have that");
            return 1;
        }

        // Sort by: main (0) -> offhand (1) -> hotbar (2), then most damaged first
        candidateSlots.sort(Comparator.comparingInt((Integer slot) -> {
            if (slot >= 9 && slot <= 35) return 0; // Main inventory
            if (slot == 40) return 1; // Offhand
            if (slot >= 0 && slot <= 8) return 2; // Hotbar
            return 3; // Armor/other
        }).thenComparing((a, b) -> {
            ItemStack sa = inv.getStack(a);
            ItemStack sb = inv.getStack(b);
            if (sa.isDamageable() && sb.isDamageable()) {
                return Integer.compare(sb.getDamage(), sa.getDamage()); // Higher damage first
            }
            return 0;
        }));

        int remaining = requestedCount;
        List<ItemStack> removed = new ArrayList<>();
        for (int slot : candidateSlots) {
            if (remaining <= 0) break;
            ItemStack cur = inv.getStack(slot);
            if (cur.isEmpty() || cur.getItem() != item) continue;

            int take = Math.min(remaining, cur.getCount());
            ItemStack part = inv.removeStack(slot, take);
            if (!part.isEmpty()) {
                removed.add(part);
                remaining -= part.getCount();
            }
        }

        if (removed.isEmpty()) {
            ChatUtils.sendChatMessages(bot.getCommandSource().withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS), "I don't have that");
            return 1;
        }

        int totalGiven = 0;
        final ServerPlayerEntity finalRecipient = recipient;
        for (ItemStack stackToDrop : removed) {
            totalGiven += stackToDrop.getCount();
            // Drop with owner set to prevent bot re-pickup
            ItemEntity itemEntity = bot.dropItem(stackToDrop, false, true);
            if (itemEntity != null) {
                itemEntity.setOwner(finalRecipient.getUuid());
                itemEntity.setPickupDelay(40); // Standard delay so player can get it
                // Throw it towards the player
                Vec3d dir = new Vec3d(finalRecipient.getX(), finalRecipient.getEyeY(), finalRecipient.getZ())
                        .subtract(bot.getX(), bot.getEyeY(), bot.getZ()).normalize();
                itemEntity.setVelocity(dir.multiply(0.35));
            } else {
                // Fallback for safety, though dropItem should rarely be null for a valid stack
                ItemEntity fallbackEntity = new ItemEntity(world, bot.getX(), bot.getEyeY() - 0.3, bot.getZ(), stackToDrop);
                fallbackEntity.setOwner(finalRecipient.getUuid());
                fallbackEntity.setPickupDelay(40);
                Vec3d dir = new Vec3d(finalRecipient.getX(), finalRecipient.getEyeY(), finalRecipient.getZ())
                        .subtract(bot.getX(), bot.getEyeY(), bot.getZ()).normalize();
                fallbackEntity.setVelocity(dir.multiply(0.35));
                world.spawnEntity(fallbackEntity);
            }
        }

        String itemName = removed.get(0).getName().getString();
        ChatUtils.sendChatMessages(bot.getCommandSource().withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS),
                "Gave " + totalGiven + " × " + itemName + " to " + recipient.getName().getString());

        return totalGiven;
    }

    /**
     * /bot give [<bot>] <item> [count]
     *
     * Spec:
     * - Target recipient is always the command sender (a real player). If run from console/command blocks, error.
     * - Item resolution:
     *     - Accepts full or short identifiers, e.g., "minecraft:iron_ingot" or "iron_ingot".
     *     - Case-insensitive; falls back to "minecraft:" when no namespace is provided.
     * - Selection policy when removing from bot's inventory:
     *     1) Prefer MAIN inventory (slots 9..35). HOTBAR (0..8) is lowest priority.
     *     2) Within the same area, prefer the most damaged stacks first (for damageable items).
     *     3) Otherwise, by slot order.
     * - Behavior:
     *     - If the bot lacks the item: bot says "I don't have that".
     *     - Otherwise remove up to [count] (default 1) and throw/drop the items toward the player.
     *     - If [count] exceeds availability, give what is available.
     */
    private static int executeGive(CommandContext<ServerCommandSource> context,
                                 ServerPlayerEntity explicitBot,
                                 String itemQuery,
                                 int requestedCount) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity recipient = null;
        try { recipient = source.getPlayer(); } catch (Exception ignored) {}
        if (recipient == null) {
            source.sendError(Text.literal("Specify a player when running from console/command blocks."));
            return 0;
        }
        return executeGive(context, explicitBot, recipient, itemQuery, requestedCount);
    }

    /**
     * Resolve a user query like "minecraft:iron_ingot" or "iron_ingot" to an Item.
     */
    private static Item resolveItemFromQuery(String query) {
        if (query == null) return null;
        String q = query.trim().toLowerCase(Locale.ROOT);
        Identifier id = q.contains(":") ? Identifier.tryParse(q) : Identifier.tryParse("minecraft:" + q);
        if (id != null && Registries.ITEM.containsId(id)) {
            return Registries.ITEM.get(id);
        }
        return null;
    }




    public static void moveForward(MinecraftServer server, ServerCommandSource source, String botName) {

        if (source.getPlayer() != null) {

            CommandUtils.run(source, "player " + botName + " move forward");

        }

    }



    public static void stopMoving(MinecraftServer server, ServerCommandSource source, String botName) {
        if (source.getPlayer() != null) {
            CommandUtils.run(source, "player " + botName + " stop");
        }
    }

    private static void equipDefaultLoadout(MinecraftServer server, ServerPlayerEntity bot, ServerPlayerEntity commander) {
        if (server == null || bot == null) {
            return;
        }

        Runnable equipTask = () -> {
            DynamicRegistryManager.Immutable registryManager = server.getRegistryManager();
            giveStack(bot, withEnchantments(registryManager, Items.NETHERITE_SWORD.getDefaultStack(),
                    new int[]{5, 3},
                    (RegistryKey<Enchantment>[]) new RegistryKey[]{Enchantments.SHARPNESS, Enchantments.UNBREAKING}), commander);

            giveStack(bot, withEnchantments(registryManager, Items.BOW.getDefaultStack(),
                    new int[]{5, 3, 1},
                    (RegistryKey<Enchantment>[]) new RegistryKey[]{Enchantments.POWER, Enchantments.UNBREAKING, Enchantments.INFINITY}), commander);
            giveStack(bot, new ItemStack(Items.ARROW, 64), commander);

            giveStack(bot, withEnchantments(registryManager, Items.SHIELD.getDefaultStack(),
                    new int[]{3},
                    (RegistryKey<Enchantment>[]) new RegistryKey[]{Enchantments.UNBREAKING}), commander);

            giveStack(bot, withEnchantments(registryManager, Items.NETHERITE_CHESTPLATE.getDefaultStack(),
                    new int[]{4, 3},
                    (RegistryKey<Enchantment>[]) new RegistryKey[]{Enchantments.PROTECTION, Enchantments.UNBREAKING}), commander);
            giveStack(bot, withEnchantments(registryManager, Items.NETHERITE_HELMET.getDefaultStack(),
                    new int[]{4, 3, 3},
                    (RegistryKey<Enchantment>[]) new RegistryKey[]{Enchantments.PROTECTION, Enchantments.RESPIRATION, Enchantments.UNBREAKING}), commander);
            giveStack(bot, withEnchantments(registryManager, Items.NETHERITE_LEGGINGS.getDefaultStack(),
                    new int[]{4, 3},
                    (RegistryKey<Enchantment>[]) new RegistryKey[]{Enchantments.PROTECTION, Enchantments.UNBREAKING}), commander);
            giveStack(bot, withEnchantments(registryManager, Items.NETHERITE_BOOTS.getDefaultStack(),
                    new int[]{4, 4, 3},
                    (RegistryKey<Enchantment>[]) new RegistryKey[]{Enchantments.PROTECTION, Enchantments.FEATHER_FALLING, Enchantments.UNBREAKING}), commander);

            giveStack(bot, withEnchantments(registryManager, Items.NETHERITE_PICKAXE.getDefaultStack(),
                    new int[]{5, 3, 1},
                    (RegistryKey<Enchantment>[]) new RegistryKey[]{Enchantments.EFFICIENCY, Enchantments.UNBREAKING, Enchantments.MENDING}), commander);
            giveStack(bot, withEnchantments(registryManager, Items.NETHERITE_AXE.getDefaultStack(),
                    new int[]{5, 3},
                    (RegistryKey<Enchantment>[]) new RegistryKey[]{Enchantments.SHARPNESS, Enchantments.UNBREAKING}), commander);
            giveStack(bot, withEnchantments(registryManager, Items.NETHERITE_SHOVEL.getDefaultStack(),
                    new int[]{5, 3},
                    (RegistryKey<Enchantment>[]) new RegistryKey[]{Enchantments.EFFICIENCY, Enchantments.UNBREAKING}), commander);
            giveStack(bot, withEnchantments(registryManager, Items.NETHERITE_HOE.getDefaultStack(),
                    new int[]{5, 3, 1},
                    (RegistryKey<Enchantment>[]) new RegistryKey[]{Enchantments.EFFICIENCY, Enchantments.UNBREAKING, Enchantments.MENDING}), commander);
            giveStack(bot, withEnchantments(registryManager, Items.FISHING_ROD.getDefaultStack(),
                    new int[]{3, 3, 3, 1},
                    (RegistryKey<Enchantment>[]) new RegistryKey[]{Enchantments.LURE, Enchantments.LUCK_OF_THE_SEA, Enchantments.UNBREAKING, Enchantments.MENDING}), commander);

            giveStack(bot, new ItemStack(Items.COOKED_BEEF, 64), commander);
            giveStack(bot, new ItemStack(Items.TORCH, 64), commander);
            giveStack(bot, new ItemStack(Items.TORCH, 64), commander);
            giveStack(bot, new ItemStack(Items.WHEAT_SEEDS, 64), commander);
            giveStack(bot, new ItemStack(Items.COMPASS, 1), commander);

            // --- Utility & building items for quick testing ---
            giveStack(bot, new ItemStack(Items.CRAFTING_TABLE), commander);
            giveStack(bot, new ItemStack(Items.FURNACE), commander);
            giveStack(bot, new ItemStack(Items.CHEST, 1), commander);
            giveStack(bot, new ItemStack(Items.WATER_BUCKET), commander);
            giveStack(bot, new ItemStack(Items.SHEARS), commander);
            giveStack(bot, new ItemStack(Items.WHITE_BED), commander);

            armorUtils.autoEquipArmor(bot);
            CombatInventoryManager.ensureCombatLoadout(bot);

            ChatUtils.sendChatMessages(bot.getCommandSource().withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS),
                    "Loadout equipped! Stay sharp out there.");
        };

        if (server.isOnThread()) {
            equipTask.run();
        } else {
            server.execute(equipTask);
        }
    }

    private static void giveStack(ServerPlayerEntity bot, ItemStack stack) {
        giveStack(bot, stack, null);
    }

    private static void giveStack(ServerPlayerEntity bot, ItemStack stack, ServerPlayerEntity owner) {
        if (stack.isEmpty()) {
            return;
        }
        ItemStack copy = stack.copy();
        boolean inserted = bot.getInventory().insertStack(copy);
        if (inserted && copy.isEmpty()) {
            return;
        }
        if (!copy.isEmpty()) {
            ItemEntity drop = bot.dropItem(copy, false, false);
            if (drop != null && owner != null) {
                drop.setOwner(owner.getUuid());
            }
        }
    }

    @SafeVarargs
    private static ItemStack withEnchantments(DynamicRegistryManager registryManager, ItemStack stack, int[] levels, RegistryKey<Enchantment>... enchantments) {
        if (stack.isEmpty()) {
            return stack;
        }
        Registry<Enchantment> registry = registryManager.getOrThrow(RegistryKeys.ENCHANTMENT);
        EnchantmentHelper.apply(stack, builder -> {
            for (int i = 0; i < enchantments.length && i < levels.length; i++) {
                RegistryEntry<Enchantment> entry = registry.getOrThrow(enchantments[i]);
                builder.set(entry, levels[i]);
            }
        });
        return stack;
    }

    private static int executeFollow(CommandContext<ServerCommandSource> context, ServerPlayerEntity bot, ServerPlayerEntity target) {
        interruptAmbientHobbyIfAny(bot, "§cInterrupted by /bot follow.");
        // Commands already emit a system summary; avoid redundant bot-authored chat acks.
        BotEventHandler.setFollowMode(bot, target, false);
        return 1;
    }

    private static int executeFollowDistance(CommandContext<ServerCommandSource> context,
                                             ServerPlayerEntity bot,
                                             ServerPlayerEntity target,
                                             double distance) {
        if (bot == null || target == null) {
            return 0;
        }
        interruptAmbientHobbyIfAny(bot, "§cInterrupted by /bot follow distance.");
        BotEventHandler.setFollowModeDistance(bot, target, distance);
        return 1;
    }

    static int executeFollowDistanceTargets(CommandContext<ServerCommandSource> context,
                                            String targetArg,
                                            ServerPlayerEntity followTarget,
                                            double distance) throws CommandSyntaxException {
        if (followTarget == null) {
            throw new SimpleCommandExceptionType(Text.literal("Specify a player for the bots to follow.")).create();
        }
        if (!Double.isFinite(distance) || distance < 1.0D) {
            throw new SimpleCommandExceptionType(Text.literal("Distance must be at least 1.0." )).create();
        }
        List<ServerPlayerEntity> bots = BotTargetingService.resolve(context.getSource(), targetArg);
        boolean isAll = targetArg != null && "all".equalsIgnoreCase(targetArg.trim());

        int successes = 0;
        for (ServerPlayerEntity bot : bots) {
            successes += executeFollowDistance(context, bot, followTarget, distance);
        }
        if (!bots.isEmpty()) {
            String summary = formatBotList(bots, isAll);
            boolean plural = isAll || bots.size() > 1;
            String verb = plural ? "are" : "is";
            ChatUtils.sendSystemMessage(context.getSource(), summary + " " + verb + " following "
                    + followTarget.getName().getString() + " (distance " + String.format(Locale.ROOT, "%.1f", distance) + ").");
        }
        return successes;
    }

    static int executeFollowDistanceResetTargets(CommandContext<ServerCommandSource> context,
                                                 String targetArg) throws CommandSyntaxException {
        List<ServerPlayerEntity> bots = BotTargetingService.resolve(context.getSource(), targetArg);
        boolean isAll = targetArg != null && "all".equalsIgnoreCase(targetArg.trim());

        int successes = 0;
        for (ServerPlayerEntity bot : bots) {
            if (bot == null) {
                continue;
            }
            BotEventHandler.setFollowStandoffRange(bot, 0.0D);
            successes++;
        }
        if (!bots.isEmpty()) {
            String summary = formatBotList(bots, isAll);
            ChatUtils.sendSystemMessage(context.getSource(), summary + " reset follow distance.");
        }
        return successes;
    }

    static int executeFollowCheck(CommandContext<ServerCommandSource> context,
                                  ServerPlayerEntity bot,
                                  String expectedModeRaw) {
        if (bot == null) {
            ChatUtils.sendSystemMessage(context.getSource(), "§cBot not found.");
            return 0;
        }
        BotCommandStateService.State state = BotCommandStateService.stateFor(bot);
        BotEventHandler.Mode mode = BotEventHandler.getCurrentMode(bot);
        UUID followTarget = BotEventHandler.getFollowTargetUuid(bot);
        BlockPos fixedGoal = state != null ? state.followFixedGoal : null;
        double stopRange = state != null ? state.followStopRange : 0.0D;
        double standoffRange = state != null ? Math.max(0.0D, state.followStandoffRange) : 0.0D;
        boolean noTeleport = state != null && state.followNoTeleport;
        boolean allowRecovery = state == null || state.comeAllowRecoverySkills;
        int rerouteAttempts = getOptionalIntField(state, "comeRerouteAttempts");
        long nextRerouteTick = getOptionalLongField(state, "comeNextRerouteTick");
        int ticksSinceBest = state != null ? Math.max(0, state.comeTicksSinceBest) : 0;
        long nextSkillTick = state != null ? state.comeNextSkillTick : 0L;
        long nowTick = context.getSource() != null && context.getSource().getServer() != null
                ? context.getSource().getServer().getTicks()
                : 0L;
        Map<String, Long> diag = net.wcfcarolina13.GameAI.services.FollowStateService.diagnosticSnapshot(bot.getUuid());
        int repeatWpStreak = diag.getOrDefault("repeat_wp_streak", 0L).intValue();
        int verticalTrapStreak = diag.getOrDefault("vertical_trap_streak", 0L).intValue();
        int waterEscapeAttempts = diag.getOrDefault("water_escape_attempts", 0L).intValue();
        long waterEscapeVerifyUntilTick = diag.getOrDefault("water_escape_verify_until_tick", 0L);
        int waypoints = net.wcfcarolina13.GameAI.services.FollowStateService.FOLLOW_WAYPOINTS
                .getOrDefault(bot.getUuid(), new ArrayDeque<>())
                .size();
        boolean planningInflight = net.wcfcarolina13.GameAI.services.FollowStateService.FOLLOW_PATH_INFLIGHT.containsKey(bot.getUuid());

        String summary = String.format(Locale.ROOT,
                "FollowCheck bot=%s mode=%s target=%s fixedGoal=%s forceWalk=%s stopRange=%.2f standoff=%.2f allowRecovery=%s ticksSinceBest=%d rerouteAttempts=%d nextRerouteTick=%d nextSkill=%d repeatWp=%d verticalTrap=%d waterEscapeAttempts=%d waterEscapeVerifyUntil=%d waypoints=%d inflight=%s",
                bot.getName().getString(),
                mode,
                followTarget != null ? followTarget.toString() : "none",
                fixedGoal != null ? fixedGoal.toShortString() : "none",
                noTeleport,
                stopRange,
                standoffRange,
                allowRecovery,
                ticksSinceBest,
                rerouteAttempts,
                nextRerouteTick,
                nextSkillTick,
                repeatWpStreak,
                verticalTrapStreak,
                waterEscapeAttempts,
                waterEscapeVerifyUntilTick,
                waypoints,
                planningInflight);
        LOGGER.info("[FollowAssert] {}", summary);
        ChatUtils.sendSystemMessage(context.getSource(), summary);

        if (expectedModeRaw == null || expectedModeRaw.isBlank()) {
            return 1;
        }
        List<String> tokens = Arrays.stream(expectedModeRaw.trim().toLowerCase(Locale.ROOT).split("[+,]"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
        if (tokens.isEmpty()) {
            ChatUtils.sendSystemMessage(context.getSource(),
                    "§cFollowCheck expected is empty. Use follow|come|idle or token combos (e.g. come+recovery_off+force_walk).");
            return 0;
        }

        List<String> failures = new ArrayList<>();
        for (String token : tokens) {
            switch (token) {
                case "follow" -> assertFollowInvariant(failures, mode, fixedGoal, followTarget);
                case "come" -> assertComeInvariant(failures, mode, fixedGoal);
                case "idle" -> assertIdleInvariant(failures, mode, followTarget, fixedGoal);
                case "come_safe", "come_norecovery", "recovery_off" -> {
                    assertComeInvariant(failures, mode, fixedGoal);
                    if (allowRecovery) {
                        failures.add("expected allowRecovery=false");
                    }
                }
                case "come_recovery", "recovery_on" -> {
                    assertComeInvariant(failures, mode, fixedGoal);
                    if (!allowRecovery) {
                        failures.add("expected allowRecovery=true");
                    }
                }
                case "fixed_goal" -> {
                    if (fixedGoal == null) {
                        failures.add("expected fixedGoal present");
                    }
                }
                case "no_fixed_goal" -> {
                    if (fixedGoal != null) {
                        failures.add("expected fixedGoal absent");
                    }
                }
                case "has_target" -> {
                    if (followTarget == null) {
                        failures.add("expected follow target present");
                    }
                }
                case "no_target" -> {
                    if (followTarget != null) {
                        failures.add("expected follow target absent");
                    }
                }
                case "force_walk" -> {
                    if (!noTeleport) {
                        failures.add("expected forceWalk=true");
                    }
                }
                case "can_teleport" -> {
                    if (noTeleport) {
                        failures.add("expected forceWalk=false");
                    }
                }
                case "standoff" -> {
                    if (standoffRange <= 0.0D) {
                        failures.add("expected standoff>0");
                    }
                }
                case "no_standoff" -> {
                    if (standoffRange > 0.0D) {
                        failures.add("expected standoff=0");
                    }
                }
                case "has_waypoints" -> {
                    if (waypoints <= 0) {
                        failures.add("expected waypoints>0");
                    }
                }
                case "no_waypoints" -> {
                    if (waypoints > 0) {
                        failures.add("expected waypoints=0");
                    }
                }
                case "planner_inflight" -> {
                    if (!planningInflight) {
                        failures.add("expected planner inflight");
                    }
                }
                case "planner_idle" -> {
                    if (planningInflight) {
                        failures.add("expected planner idle");
                    }
                }
                case "rerouted" -> {
                    if (rerouteAttempts <= 0) {
                        failures.add("expected rerouteAttempts>0");
                    }
                }
                case "no_reroute" -> {
                    if (rerouteAttempts > 0) {
                        failures.add("expected rerouteAttempts=0");
                    }
                }
                case "reroute_scheduled" -> {
                    if (!(nextRerouteTick > nowTick)) {
                        failures.add("expected nextRerouteTick>nowTick");
                    }
                }
                case "no_reroute_scheduled" -> {
                    if (nextRerouteTick > nowTick) {
                        failures.add("expected nextRerouteTick<=nowTick");
                    }
                }
                case "repeat_wp" -> {
                    if (repeatWpStreak <= 0) {
                        failures.add("expected repeatWpStreak>0");
                    }
                }
                case "no_repeat_wp" -> {
                    if (repeatWpStreak > 0) {
                        failures.add("expected repeatWpStreak=0");
                    }
                }
                case "vertical_trap" -> {
                    if (verticalTrapStreak <= 0) {
                        failures.add("expected verticalTrapStreak>0");
                    }
                }
                case "no_vertical_trap" -> {
                    if (verticalTrapStreak > 0) {
                        failures.add("expected verticalTrapStreak=0");
                    }
                }
                case "water_escape_active" -> {
                    if (!(waterEscapeAttempts > 0 || waterEscapeVerifyUntilTick > nowTick)) {
                        failures.add("expected water escape active");
                    }
                }
                case "water_escape_idle" -> {
                    if (waterEscapeAttempts > 0 || waterEscapeVerifyUntilTick > nowTick) {
                        failures.add("expected water escape idle");
                    }
                }
                default -> {
                    ChatUtils.sendSystemMessage(context.getSource(),
                            "§cUnknown follow_check token '" + token + "'.");
                    return 0;
                }
            }
        }

        if (!failures.isEmpty()) {
            String fail = "§cFollowCheck assert failed: " + String.join("; ", failures);
            LOGGER.info("[FollowAssert] assert-fail bot={} expected={} mode={} target={} fixedGoal={} reasons={}",
                    bot.getName().getString(),
                    String.join("+", tokens),
                    mode,
                    followTarget != null ? followTarget : "none",
                    fixedGoal != null ? fixedGoal.toShortString() : "none",
                    String.join("|", failures));
            ChatUtils.sendSystemMessage(context.getSource(), fail);
            return 0;
        }

        String expected = String.join("+", tokens);
        LOGGER.info("[FollowAssert] assert-ok bot={} expected={} mode={}", bot.getName().getString(), expected, mode);
        ChatUtils.sendSystemMessage(context.getSource(), "§aFollowCheck assert passed (" + expected + ").");
        return 1;
    }

    static int executeChatCheck(CommandContext<ServerCommandSource> context,
                                String rawMessage,
                                String expectedRaw) {
        if (rawMessage == null || rawMessage.isBlank()) {
            ChatUtils.sendSystemMessage(context.getSource(), "§craw_message is required.");
            return 0;
        }
        ChatRoutingCheckResult debug = resolveChatRoutingCheck(context.getSource().getServer(), rawMessage);
        String aliases = debug.aliases().isEmpty() ? "none" : String.join(",", debug.aliases());
        String summary = String.format(Locale.ROOT,
                "ChatCheck raw='%s' aliases=%s prompt='%s' broadcast=%s rawTargets=%d uniqueTargets=%d",
                rawMessage,
                aliases,
                debug.prompt,
                debug.broadcast,
                debug.rawTargetCount,
                debug.aliases().size());
        LOGGER.info("[ChatAssert] {}", summary);
        ChatUtils.sendSystemMessage(context.getSource(), summary);

        if (expectedRaw == null || expectedRaw.isBlank()) {
            return 1;
        }

        List<String> tokens = Arrays.stream(expectedRaw.trim().toLowerCase(Locale.ROOT).split("[+,]"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
        if (tokens.isEmpty()) {
            ChatUtils.sendSystemMessage(context.getSource(),
                    "§cchat_check expected is empty. Use tokens like none|single|multi|broadcast|named|prompt|empty_prompt|deduped|no_dedupe|bot:<alias>.");
            return 0;
        }

        List<String> failures = new ArrayList<>();
        for (String token : tokens) {
            switch (token) {
                case "none" -> {
                    if (debug.aliases().isEmpty()) {
                        // ok
                    } else {
                        failures.add("expected no routed bots");
                    }
                }
                case "single" -> {
                    if (debug.aliases().size() != 1) {
                        failures.add("expected exactly one routed bot");
                    }
                }
                case "multi" -> {
                    if (debug.aliases().size() < 2) {
                        failures.add("expected multiple routed bots");
                    }
                }
                case "broadcast" -> {
                    if (!debug.broadcast) {
                        failures.add("expected broadcast=true");
                    }
                }
                case "named" -> {
                    if (debug.broadcast) {
                        failures.add("expected named route, not broadcast");
                    }
                    if (debug.aliases().size() != 1) {
                        failures.add("expected exactly one named routed bot");
                    }
                }
                case "prompt" -> {
                    if (debug.prompt == null || debug.prompt.isBlank()) {
                        failures.add("expected non-empty prompt");
                    }
                }
                case "empty_prompt" -> {
                    if (debug.prompt != null && !debug.prompt.isBlank()) {
                        failures.add("expected empty prompt");
                    }
                }
                case "deduped" -> {
                    if (!(debug.rawTargetCount > debug.aliases().size())) {
                        failures.add("expected duplicate targets to be deduped");
                    }
                }
                case "no_dedupe" -> {
                    if (debug.rawTargetCount != debug.aliases().size()) {
                        failures.add("expected no duplicate targets");
                    }
                }
                default -> {
                    if (token.startsWith("bot:")) {
                        String expectedAlias = normalizeChatCheckAlias(token.substring(4));
                        boolean matched = debug.aliases().stream()
                                .map(modCommandRegistry::normalizeChatCheckAlias)
                                .anyMatch(expectedAlias::equals);
                        if (!matched) {
                            failures.add("expected bot alias '" + token.substring(4) + "'");
                        }
                    } else {
                        ChatUtils.sendSystemMessage(context.getSource(),
                                "§cUnknown chat_check token '" + token + "'.");
                        return 0;
                    }
                }
            }
        }

        if (!failures.isEmpty()) {
            String fail = "§cChatCheck assert failed: " + String.join("; ", failures);
            LOGGER.info("[ChatAssert] assert-fail expected={} reasons={}", String.join("+", tokens), String.join("|", failures));
            ChatUtils.sendSystemMessage(context.getSource(), fail);
            return 0;
        }

        String expected = String.join("+", tokens);
        LOGGER.info("[ChatAssert] assert-ok expected={}", expected);
        ChatUtils.sendSystemMessage(context.getSource(), "§aChatCheck assert passed (" + expected + ").");
        return 1;
    }

    static int executeIdentityCheck(CommandContext<ServerCommandSource> context,
                                    String alias) {
        if (alias == null || alias.isBlank()) {
            ChatUtils.sendSystemMessage(context.getSource(), "§cAlias is required: /bot identity_check <alias>");
            return 0;
        }
        MinecraftServer server = context.getSource().getServer();
        net.wcfcarolina13.GameAI.services.BotIdentityService.IdentityDebugSnapshot snapshot =
                net.wcfcarolina13.GameAI.services.BotIdentityService.inspect(server, alias);

        List<String> lines = new ArrayList<>();
        lines.add("Requested alias: " + snapshot.requestedAlias());
        lines.add("Normalized alias: " + snapshot.normalizedAlias());
        lines.add("Related alias keys: " + joinOrNone(snapshot.relatedAliasKeys()));
        lines.add("Profile alias keys: " + joinOrNone(snapshot.profileAliasKeys()));
        lines.add("Owner alias keys: " + joinOrNone(snapshot.ownerAliasKeys()));
        lines.add("Spawn alias keys: " + joinOrNone(snapshot.spawnAliasKeys()));
        lines.add("Control alias keys: " + joinOrNone(snapshot.controlAliasKeys()));
        lines.add("Quest alias keys: " + joinOrNone(snapshot.questAliasKeys()));
        lines.add("World-state alias keys: " + joinOrNone(snapshot.worldStateAliasKeys()));
        lines.add("Config profile key: " + safeString(snapshot.configAliasKey()));
        lines.add("Config UUID raw: " + safeString(snapshot.configUuidRaw()));
        lines.add("Config UUID parsed: " + safeString(snapshot.configUuid()));
        lines.add("Online alias: " + safeString(snapshot.onlineAlias()));
        lines.add("Online UUID: " + safeString(snapshot.onlineUuid()));
        boolean uuidMatch = snapshot.configUuid() != null
                && snapshot.onlineUuid() != null
                && snapshot.configUuid().equals(snapshot.onlineUuid());
        lines.add("UUID match (config vs online): " + uuidMatch);
        lines.add("World-state present (current world): " + snapshot.worldStatePresentCurrentWorld());
        lines.add("Inventory snapshots: " + joinOrNone(snapshot.inventoryFiles()));
        lines.add("Task active: " + snapshot.taskActive());
        lines.add("Resume pending skill: " + snapshot.resumeDebug().hasPendingSkill());
        lines.add("Resume awaiting decision: " + snapshot.resumeDebug().awaitingDecision());
        lines.add("Resume auto-pending: " + snapshot.resumeDebug().autoResumePending());
        lines.add("Resume intent: " + snapshot.resumeDebug().resumeIntent());
        if (server != null) {
            if (snapshot.configUuid() != null) {
                Path expected = net.wcfcarolina13.GameAI.services.BotInventoryStorageService.resolveInventoryPathForAliasUuid(
                        server,
                        snapshot.normalizedAlias(),
                        snapshot.configUuid().toString()
                );
                lines.add("Expected inventory path (config UUID): " + safeString(expected));
            }
            if (snapshot.onlineUuid() != null) {
                Path expected = net.wcfcarolina13.GameAI.services.BotInventoryStorageService.resolveInventoryPathForAliasUuid(
                        server,
                        snapshot.normalizedAlias(),
                        snapshot.onlineUuid().toString()
                );
                lines.add("Expected inventory path (online UUID): " + safeString(expected));
            }
        }

        if (!snapshot.warnings().isEmpty()) {
            lines.add("Warnings:");
            for (String warning : snapshot.warnings()) {
                lines.add(" - " + warning);
            }
        } else {
            lines.add("Warnings: none");
        }

        sendPaged(context.getSource(),
                "IdentityCheck alias='" + snapshot.requestedAlias() + "'",
                lines);

        LOGGER.info("[IdentityCheck] alias={} normalized={} configUuid={} onlineUuid={} warnings={} relatedAliases={}",
                snapshot.requestedAlias(),
                snapshot.normalizedAlias(),
                safeString(snapshot.configUuid()),
                safeString(snapshot.onlineUuid()),
                snapshot.warnings().size(),
                String.join(",", snapshot.relatedAliasKeys()));
        if (!snapshot.warnings().isEmpty()) {
            LOGGER.info("[IdentityCheck] warnings alias={} -> {}", snapshot.requestedAlias(), String.join(" | ", snapshot.warnings()));
        }
        return snapshot.warnings().isEmpty() ? 1 : 0;
    }

    private static String joinOrNone(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "none";
        }
        return String.join(", ", values);
    }

    private static String safeString(Object value) {
        return value == null ? "none" : value.toString();
    }

    private static String normalizeChatCheckAlias(String token) {
        if (token == null) {
            return "";
        }
        return token.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private static ChatRoutingCheckResult resolveChatRoutingCheck(MinecraftServer server, String raw) {
        if (server == null || raw == null) {
            return new ChatRoutingCheckResult(List.of(), "", false, 0);
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return new ChatRoutingCheckResult(List.of(), "", false, 0);
        }
        String[] tokens = trimmed.split("\\s+");
        if (tokens.length == 0) {
            return new ChatRoutingCheckResult(List.of(), "", false, 0);
        }

        List<ServerPlayerEntity> bots = BotEventHandler.getRegisteredBots(server);
        if (bots.isEmpty()) {
            return new ChatRoutingCheckResult(List.of(), "", false, 0);
        }

        int consumed = -1;
        boolean broadcast = false;
        List<ServerPlayerEntity> targets = new ArrayList<>();
        for (int i = 0; i < tokens.length; i++) {
            String current = normalizeChatCheckAlias(tokens[i]);
            if (current.isEmpty()) {
                continue;
            }
            if (current.equals("allbots") || current.equals("bots")) {
                targets.addAll(bots);
                broadcast = true;
                consumed = i + 1;
                break;
            }
            if (current.equals("all") && i + 1 < tokens.length) {
                String next = normalizeChatCheckAlias(tokens[i + 1]);
                if (next.equals("bots")) {
                    targets.addAll(bots);
                    broadcast = true;
                    consumed = i + 2;
                    break;
                }
            }
            for (ServerPlayerEntity bot : bots) {
                if (normalizeChatCheckAlias(bot.getName().getString()).equals(current)) {
                    targets.add(bot);
                    consumed = i + 1;
                    break;
                }
            }
            if (!targets.isEmpty()) {
                break;
            }
        }
        if (targets.isEmpty() || consumed < 0) {
            return new ChatRoutingCheckResult(List.of(), "", false, 0);
        }

        int rawTargetCount = targets.size();
        Map<UUID, String> deduped = new LinkedHashMap<>();
        for (ServerPlayerEntity bot : targets) {
            if (bot == null || bot.isRemoved()) {
                continue;
            }
            deduped.putIfAbsent(bot.getUuid(), bot.getName().getString());
        }
        List<String> aliases = new ArrayList<>(deduped.values());
        String prompt = consumed >= tokens.length
                ? ""
                : String.join(" ", Arrays.copyOfRange(tokens, consumed, tokens.length)).trim();
        return new ChatRoutingCheckResult(aliases, prompt, broadcast, rawTargetCount);
    }

    private record ChatRoutingCheckResult(List<String> aliases, String prompt, boolean broadcast, int rawTargetCount) {}

    private static void assertFollowInvariant(List<String> failures,
                                              BotEventHandler.Mode mode,
                                              BlockPos fixedGoal,
                                              UUID followTarget) {
        if (!(mode == BotEventHandler.Mode.FOLLOW && fixedGoal == null && followTarget != null)) {
            failures.add("expected mode=FOLLOW with player target and no fixed goal");
        }
    }

    private static void assertComeInvariant(List<String> failures,
                                            BotEventHandler.Mode mode,
                                            BlockPos fixedGoal) {
        if (!(mode == BotEventHandler.Mode.FOLLOW && fixedGoal != null)) {
            failures.add("expected mode=FOLLOW with fixed goal");
        }
    }

    private static void assertIdleInvariant(List<String> failures,
                                            BotEventHandler.Mode mode,
                                            UUID followTarget,
                                            BlockPos fixedGoal) {
        if (!(mode == BotEventHandler.Mode.IDLE && followTarget == null && fixedGoal == null)) {
            failures.add("expected mode=IDLE with no follow target and no fixed goal");
        }
    }

    static int executeSoundTestTargets(CommandContext<ServerCommandSource> context,
                                       String targetArg) throws CommandSyntaxException {
        List<ServerPlayerEntity> bots = BotTargetingService.resolve(context.getSource(), targetArg);
        boolean isAll = targetArg != null && "all".equalsIgnoreCase(targetArg.trim());

        // Pick a random test sound from available greetings/idle lines
        SoundEvent[] testSounds = {
                BotDialogueSounds.LINE_GREETING_HEY,
                BotDialogueSounds.LINE_GREETING_GOOD_TO_SEE,
                BotDialogueSounds.LINE_IDLE_ALL_QUIET,
                BotDialogueSounds.LINE_IDLE_HERE_IF_NEEDED,
                BotDialogueSounds.LINE_IDLE_STILL_STANDING
        };

        int successes = 0;
        for (ServerPlayerEntity bot : bots) {
            if (bot == null) {
                continue;
            }
            SoundEvent sound = testSounds[new java.util.Random().nextInt(testSounds.length)];
            // Use forcePlaySound to bypass config check - this is a test command
            if (BotDialoguePlayer.forcePlaySound(bot, sound)) {
                successes++;
                ChatUtils.sendSystemMessage(context.getSource(),
                        "§aPlayed sound test for " + bot.getName().getString() + " (sound: " + sound.id().getPath() + ")");
            } else {
                ChatUtils.sendSystemMessage(context.getSource(),
                        "§cCould not play sound for " + bot.getName().getString());
            }
        }
        if (bots.isEmpty()) {
            ChatUtils.sendSystemMessage(context.getSource(), "§cNo bots found to test sound.");
        }
        return successes;
    }

    /**
     * Test the ambient chatter system by triggering a chatter sound for the specified bot(s).
     * This bypasses normal timing restrictions and picks an environment-aware sound.
     */
    static int executeTestChatterTargets(CommandContext<ServerCommandSource> context,
                                         String targetArg) throws CommandSyntaxException {
        List<ServerPlayerEntity> bots = BotTargetingService.resolve(context.getSource(), targetArg);
        
        int successes = 0;
        for (ServerPlayerEntity bot : bots) {
            if (bot == null) {
                continue;
            }
            // Use the public triggerChatter method which picks an appropriate sound
            if (net.wcfcarolina13.ChatUtils.BotAmbientChatter.triggerChatter(bot)) {
                successes++;
                ChatUtils.sendSystemMessage(context.getSource(),
                        "§aTriggered ambient chatter for " + bot.getName().getString());
            } else {
                ChatUtils.sendSystemMessage(context.getSource(),
                        "§cCould not trigger chatter for " + bot.getName().getString() + " (check voiced dialogue setting)");
            }
        }
        if (bots.isEmpty()) {
            ChatUtils.sendSystemMessage(context.getSource(), "§cNo bots found to test chatter.");
        }
        return successes;
    }

    static int executeDialogueTest(CommandContext<ServerCommandSource> context,
                                   ServerPlayerEntity bot,
                                   String triggerKeyRaw,
                                   String lineIdRaw) {
        if (bot == null) {
            ChatUtils.sendSystemMessage(context.getSource(), "§cBot not found.");
            return 0;
        }
        if (!BotEventHandler.isRegisteredBot(bot)) {
            ChatUtils.sendSystemMessage(context.getSource(),
                    "§c" + bot.getName().getString() + " is not a registered bot.");
            return 0;
        }

        String triggerKey = triggerKeyRaw == null ? "" : triggerKeyRaw.trim().toLowerCase(Locale.ROOT);
        if (triggerKey.isEmpty()) {
            ChatUtils.sendSystemMessage(context.getSource(), "§cTrigger key is required.");
            return 0;
        }
        if (!DIALOGUE_TEST_SUPPORTED_TRIGGERS.contains(triggerKey)) {
            ChatUtils.sendSystemMessage(context.getSource(),
                    "§cUnknown trigger key '" + triggerKey + "'.");
            return 0;
        }

        String lineId = (lineIdRaw == null || lineIdRaw.isBlank()) ? null : lineIdRaw.trim().toLowerCase(Locale.ROOT);
        boolean played = routeDialogueTestTrigger(bot, triggerKey, lineId);
        if (played) {
            String lineSuffix = lineId == null ? "" : " line_id=" + lineId;
            ChatUtils.sendSystemMessage(context.getSource(),
                    "§aDialogue test played for " + bot.getName().getString()
                            + " trigger=" + triggerKey + lineSuffix);
            return 1;
        }

        ChatUtils.sendSystemMessage(context.getSource(),
                "§eNo dialogue played for " + bot.getName().getString()
                        + " (cooldown active or invalid line_id for trigger '" + triggerKey + "').");
        return 0;
    }

    private static boolean routeDialogueTestTrigger(ServerPlayerEntity bot, String triggerKey, String lineId) {
        return switch (triggerKey) {
            case "fighting_multiple_dangerous", "combat_multi",
                 "combat_ended", "post_combat",
                 "combat_ended_explosion", "post_explosion",
                 "combat_ended_multiple_dangerous", "post_combat_multi",
                 "combat_ended_single_weak", "post_combat_single",
                 "player_hit_bot", "ff_received",
                 "bot_hit_player", "ff_dealt" ->
                    invokeDialogueDebugTrigger("net.wcfcarolina13.GameAI.services.BotCombatCalloutService", bot, triggerKey, lineId);

            case "villager_noise_nearby", "villager",
                 "player_opens_villager_trade", "villager_negotiate" ->
                    invokeDialogueDebugTrigger("net.wcfcarolina13.GameAI.services.VillageProximityReactionService", bot, triggerKey, lineId);

            case "tamed_wolf_nearby", "wolf_nearby",
                 "wolf_takes_damage", "wolf_hurt",
                 "tamed_animal_nearby", "animal_nearby" ->
                    invokeDialogueDebugTrigger("net.wcfcarolina13.GameAI.services.PetProximityReactionService", bot, triggerKey, lineId);

            case "random_idle_not_combat", "ambient",
                 "in_high_threat_location", "high_threat",
                 "scary_sound_nearby", "scary",
                 "in_boat_not_combat", "boat",
                 "in_boat_deep_water", "boat_deep",
                 "in_boat_dolphin_nearby", "boat_dolphin",
                 "boat_breaks", "boat_break",
                 "standing_on_edge", "precipice",
                 "safe_vista", "vista",
                 "falling_or_elytra", "freefall",
                 "random_ambient", "meta",
                 "baby_zombie_on_chicken", "meme_chicken",
                 "creeper_hiss", "meme_creeper",
                 "world_start_or_milestone", "meme_steve",
                 "survive_near_death_or_totem", "meme_technoblade",
                 "lightning_at_night", "meme_herobrine",
                 "shelter_completion", "shelter" ->
                    invokeDialogueDebugTrigger("net.wcfcarolina13.GameAI.services.CompanionContextReactionService", bot, triggerKey, lineId);
            case "batch3_biomes", "topic_biomes",
                 "batch3_structures", "topic_structures",
                 "batch3_dimensions", "topic_dimensions",
                 "batch3_traders_mounts", "topic_mounts",
                 "batch3_travel", "topic_travel" ->
                    invokeDialogueDebugTrigger("net.wcfcarolina13.GameAI.services.Batch3TopicDialogueService", bot, triggerKey, lineId);
            default -> false;
        };
    }

    /**
     * CI-safe debug trigger bridge: if a dialogue debug service/method is not present in this checkout,
     * treat it as "not played" instead of failing compilation.
     */
    private static boolean invokeDialogueDebugTrigger(String className, ServerPlayerEntity bot, String triggerKey, String lineId) {
        try {
            Class<?> clazz = Class.forName(className);
            java.lang.reflect.Method method = clazz.getMethod(
                    "debugTrigger",
                    ServerPlayerEntity.class,
                    String.class,
                    String.class
            );
            Object out = method.invoke(null, bot, triggerKey, lineId);
            return out instanceof Boolean b && b;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private static int getOptionalIntField(Object target, String fieldName) {
        if (target == null || fieldName == null || fieldName.isBlank()) {
            return 0;
        }
        try {
            java.lang.reflect.Field field = target.getClass().getField(fieldName);
            return Math.max(0, field.getInt(target));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return 0;
        }
    }

    private static long getOptionalLongField(Object target, String fieldName) {
        if (target == null || fieldName == null || fieldName.isBlank()) {
            return 0L;
        }
        try {
            java.lang.reflect.Field field = target.getClass().getField(fieldName);
            return Math.max(0L, field.getLong(target));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return 0L;
        }
    }

    static int executeComeTargets(CommandContext<ServerCommandSource> context, String targetArg) throws CommandSyntaxException {
        ServerPlayerEntity commander = context.getSource().getPlayer();
        if (commander == null) {
            throw new SimpleCommandExceptionType(Text.literal("Only players can call bots to come to them.")).create();
        }
        List<ServerPlayerEntity> bots = resolveTargetBots(context, targetArg);
        boolean isAll = targetArg != null && "all".equalsIgnoreCase(targetArg.trim());
        int successes = 0;
        for (ServerPlayerEntity bot : bots) {
            successes += executeCome(context, bot, commander, true);
        }
        if (!bots.isEmpty() && successes > 0) {
            String summary = formatBotList(bots, isAll);
            String verb = (isAll || bots.size() > 1) ? "are" : "is";
            ChatUtils.sendSystemMessage(context.getSource(), summary + " " + verb + " heading to your last location.");
        }
        return successes;
    }

    static int executeRegroupTargets(CommandContext<ServerCommandSource> context, String targetArg) throws CommandSyntaxException {
        ServerPlayerEntity commander = context.getSource().getPlayer();
        if (commander == null) {
            throw new SimpleCommandExceptionType(Text.literal("Only players can regroup bots to them.")).create();
        }
        List<ServerPlayerEntity> bots = resolveTargetBots(context, targetArg);
        boolean isAll = targetArg != null && "all".equalsIgnoreCase(targetArg.trim());
        int successes = 0;
        for (ServerPlayerEntity bot : bots) {
            // Safe regroup: do not launch come-recovery digging skills (ascent/stripmine).
            successes += executeCome(context, bot, commander, false);
        }
        if (!bots.isEmpty() && successes > 0) {
            String summary = formatBotList(bots, isAll);
            String verb = (isAll || bots.size() > 1) ? "are" : "is";
            ChatUtils.sendSystemMessage(context.getSource(), summary + " " + verb + " regrouping to you.");
        }
        return successes;
    }

    static int executeGoToLookTargets(CommandContext<ServerCommandSource> context, String targetArg) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity commander = source.getPlayer();
        if (commander == null) {
            throw new SimpleCommandExceptionType(Text.literal("Only players can direct bots to a look target.")).create();
        }
        if (!(commander.getEntityWorld() instanceof ServerWorld commanderWorld)) {
            return 0;
        }

        // Raycast; if we don't hit a block within range (sky/horizon), treat as "too far".
        final double maxDistance = 64.0D;
        HitResult hit = commander.raycast(maxDistance, 1.0F, false);
        if (!(hit instanceof BlockHitResult bhr) || hit.getType() == HitResult.Type.MISS) {
            ChatUtils.sendSystemMessage(source, "that's too far");
            return 0;
        }

        BlockPos lookedPos = bhr.getBlockPos().toImmutable();
        boolean storageTarget = isStorageLookTarget(commanderWorld.getBlockState(lookedPos));

        BlockPos goal = null;
        if (!storageTarget) {
            BlockPos raw = lookedPos.offset(bhr.getSide()).toImmutable();
            goal = SafePositionService.findSafeNear(commanderWorld, raw, 8);
            if (goal == null) {
                // Fall back to trying the clicked block column.
                goal = SafePositionService.findSafeNear(commanderWorld, lookedPos, 8);
            }
            if (goal == null) {
                ChatUtils.sendSystemMessage(source, "that's too far");
                return 0;
            }
        }

        List<ServerPlayerEntity> bots;
        boolean isAll = targetArg != null && "all".equalsIgnoreCase(targetArg.trim());
        if (targetArg == null) {
            bots = new ArrayList<>();
            for (ServerPlayerEntity candidate : BotEventHandler.getRegisteredBots(source.getServer())) {
                if (candidate == null || candidate.isRemoved()) {
                    continue;
                }
                if (candidate.getEntityWorld() != commanderWorld) {
                    continue;
                }
                if (BotEventHandler.getCurrentMode(candidate) != BotEventHandler.Mode.FOLLOW) {
                    continue;
                }
                if (!commander.getUuid().equals(BotEventHandler.getFollowTargetUuid(candidate))) {
                    continue;
                }
                bots.add(candidate);
            }
        } else {
            bots = resolveTargetBots(context, targetArg);
        }

        if (bots.isEmpty()) {
            ChatUtils.sendSystemMessage(source, "No bots are following you.");
            return 0;
        }

        int successes = 0;
        for (ServerPlayerEntity bot : bots) {
            if (bot == null || bot.isRemoved()) {
                continue;
            }
            if (bot.getEntityWorld() != commanderWorld) {
                continue;
            }
            if (BotEventHandler.getCurrentMode(bot) != BotEventHandler.Mode.FOLLOW
                    || !commander.getUuid().equals(BotEventHandler.getFollowTargetUuid(bot))) {
                continue;
            }

            if (storageTarget) {
                // Player-issued override: interrupt any running skill so storage offload can take over.
                TaskService.forceAbort(bot.getUuid(), "§cInterrupted by /bot go_to_look (storage offload).");
                BotIdleHobbiesService.snoozeFor(bot, 3_600L);
                final ServerPlayerEntity offloadBot = bot;
                final BlockPos storagePos = lookedPos;
                CompletableFuture.runAsync(() -> {
                    int moved = ChestStoreService.depositMatchingWalkOnly(source, offloadBot, storagePos, modCommandRegistry::isGoToLookStorageOffloadItem);
                    source.getServer().execute(() -> {
                        if (moved > 0) {
                            ChatUtils.sendSystemMessage(source, offloadBot.getName().getString() + " dropped off " + moved + " item"
                                    + (moved == 1 ? "" : "s") + ".");
                            BotInventoryFullDialogueService.tryShowChestRelief(offloadBot, "go-to-look-storage");
                        } else {
                            ChatUtils.sendSystemMessage(source, offloadBot.getName().getString()
                                    + " couldn't drop off anything (no matching junk or storage unreachable).");
                        }
                    });
                });
                successes++;
            } else {
                // Player-issued override: interrupt any running skill so follow-walk can take over.
                TaskService.forceAbort(bot.getUuid(), "§cInterrupted by /bot go_to_look.");
                // After a commander-directed move, wait longer before starting idle hobbies.
                BotIdleHobbiesService.snoozeFor(bot, 3_600L);
                // Non-destructive move: do not trigger come-recovery digging skills while repositioning.
                BotEventHandler.setComeModeWalk(bot, commander, goal, 3.2D, false);
                successes++;
            }
        }

        if (successes > 0) {
            String summary = formatBotList(bots, isAll);
            String verb = (isAll || bots.size() > 1) ? "are" : "is";
            if (storageTarget) {
                ChatUtils.sendSystemMessage(source, summary + " " + verb + " heading to storage to drop off junk.");
            } else {
                ChatUtils.sendSystemMessage(source, summary + " " + verb + " heading to where you're looking.");
            }
        }
        return successes;
    }

    private static boolean isStorageLookTarget(BlockState state) {
        if (state == null) {
            return false;
        }
        return state.isOf(Blocks.CHEST) || state.isOf(Blocks.TRAPPED_CHEST) || state.isOf(Blocks.BARREL);
    }

    private static boolean isGoToLookStorageOffloadItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (ChestStoreService.isOffloadProtected(stack)) {
            return false;
        }
        if (isFoodItem.checkFoodItem(stack)) {
            return false;
        }
        if (GO_TO_LOOK_STORAGE_UTILITY_EXCLUSIONS.contains(stack.getItem())) {
            return false;
        }
        String lowerName = stack.getName() != null ? stack.getName().getString().toLowerCase(Locale.ROOT) : "";
        if (lowerName.contains("spell") || (lowerName.contains("wizard") && lowerName.contains("tome"))) {
            return false;
        }
        return true;
    }

    /**
     * Executes the shelter skill at the position the player is looking at.
     * For hovel: centers the build at the look target.
     * For burrow: digs in the direction the player is looking.
     */
    static int executeShelterLook(CommandContext<ServerCommandSource> context, String shelterType, String targetArg) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity commander = source.getPlayer();
        if (commander == null) {
            throw new SimpleCommandExceptionType(Text.literal("Only players can direct bots to build a shelter at a look target.")).create();
        }
        if (!(commander.getEntityWorld() instanceof ServerWorld commanderWorld)) {
            return 0;
        }

        // Raycast from player's view to find target position
        final double maxDistance = 64.0D;
        HitResult hit = commander.raycast(maxDistance, 1.0F, false);
        if (!(hit instanceof BlockHitResult bhr) || hit.getType() == HitResult.Type.MISS) {
            ChatUtils.sendSystemMessage(source, "Look at where you want the " + shelterType + " to be built.");
            return 0;
        }

        BlockPos targetPos = bhr.getBlockPos().offset(bhr.getSide()).toImmutable();
        BlockPos goal = SafePositionService.findSafeNear(commanderWorld, targetPos, 8);
        if (goal == null) {
            goal = SafePositionService.findSafeNear(commanderWorld, bhr.getBlockPos().toImmutable(), 8);
        }
        if (goal == null) {
            ChatUtils.sendSystemMessage(source, "Can't find a safe spot near there.");
            return 0;
        }

        // Resolve target bot(s)
        List<ServerPlayerEntity> bots;
        if (targetArg == null) {
            // Default: find bots following the commander
            bots = new ArrayList<>();
            for (ServerPlayerEntity candidate : BotEventHandler.getRegisteredBots(source.getServer())) {
                if (candidate == null || candidate.isRemoved()) continue;
                if (candidate.getEntityWorld() != commanderWorld) continue;
                if (BotEventHandler.getCurrentMode(candidate) != BotEventHandler.Mode.FOLLOW) continue;
                if (!commander.getUuid().equals(BotEventHandler.getFollowTargetUuid(candidate))) continue;
                bots.add(candidate);
            }
        } else {
            bots = resolveTargetBots(context, targetArg);
        }

        if (bots.isEmpty()) {
            ChatUtils.sendSystemMessage(source, "No bots are following you.");
            return 0;
        }

        // For now, just use the first bot (shelter is typically a single-bot task)
        ServerPlayerEntity bot = bots.get(0);
        MinecraftServer server = source.getServer();

        // Abort any current task
        TaskService.forceAbort(bot.getUuid(), "§cInterrupted by /bot shelter_look.");
        BotIdleHobbiesService.snoozeFor(bot, 3_600L);

        // Compute the direction from commander to target for burrow direction
        // This gives the direction the player is looking/pointing
        Direction lookDirection = computeHorizontalDirection(commander.getBlockPos(), goal);
        WorkDirectionService.setDirection(bot.getUuid(), lookDirection);

        // Move the bot to the target location first, then run shelter skill
        final BlockPos finalGoal = goal;
        final String finalShelterType = shelterType;
        final Direction finalDirection = lookDirection;
        ChatUtils.sendSystemMessage(source, bot.getGameProfile().name() + " is heading to build a " + shelterType + " where you're looking.");

        // Use async movement to the position, then run the shelter skill
        // Non-destructive move: do not trigger come-recovery digging skills while repositioning.
        BotEventHandler.setComeModeWalk(bot, commander, finalGoal, 3.2D, false);

        // Schedule the shelter skill to run after the bot arrives
        // We'll do a simple approach: run the skill after a short delay / when bot is close
        server.execute(() -> {
            // Queue up the skill execution once movement is roughly complete
            // Use TaskService to run shelter after a brief movement phase
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor().schedule(() -> {
                server.execute(() -> {
                    // Run the shelter skill
                    String skillArgs = finalShelterType;
                    try {
                        executeSkillTargets(context, "shelter", skillArgs + " " + bot.getGameProfile().name());
                    } catch (CommandSyntaxException e) {
                        LOGGER.warn("Failed to execute shelter skill: {}", e.getMessage());
                    }
                });
            }, 2, java.util.concurrent.TimeUnit.SECONDS);
        });

        return 1;
    }

    /**
     * Executes the build skill at the position the player is looking at.
     * Bot moves to the looked-at position, then builds the schematic there.
     */
    static int executeBuildLook(CommandContext<ServerCommandSource> context, String schematicName, String targetArg) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity commander = source.getPlayer();
        if (commander == null) {
            throw new SimpleCommandExceptionType(Text.literal("Only players can direct bots to build at a look target.")).create();
        }
        if (!(commander.getEntityWorld() instanceof ServerWorld commanderWorld)) {
            return 0;
        }

        // Raycast from player's view to find target position
        final double maxDistance = 64.0D;
        HitResult hit = commander.raycast(maxDistance, 1.0F, false);
        if (!(hit instanceof BlockHitResult bhr) || hit.getType() == HitResult.Type.MISS) {
            ChatUtils.sendSystemMessage(source, "Look at where you want the " + schematicName + " to be built.");
            return 0;
        }

        // Check if the hit block is replaceable (snow, carpet, grass, etc.)
        // For replaceable blocks, we build AT that position, not offset from it
        BlockPos hitBlockPos = bhr.getBlockPos();
        net.minecraft.block.BlockState hitState = commanderWorld.getBlockState(hitBlockPos);
        boolean isReplaceable = hitState.isReplaceable() 
            || hitState.isOf(net.minecraft.block.Blocks.SNOW)
            || hitState.isOf(net.minecraft.block.Blocks.SHORT_GRASS)
            || hitState.isOf(net.minecraft.block.Blocks.TALL_GRASS)
            || hitState.isOf(net.minecraft.block.Blocks.FERN);
        
        // For replaceable blocks, use the block's position directly
        // For solid blocks hit from above, offset up by 1
        BlockPos rawTargetPos;
        if (isReplaceable) {
            rawTargetPos = hitBlockPos.toImmutable();
        } else {
            rawTargetPos = hitBlockPos.offset(bhr.getSide()).toImmutable();
        }
        
        // Apply same 3-block forward offset that preview uses (centered + forward)
        float yaw = commander.getYaw();
        double yawRad = Math.toRadians(yaw);
        int forwardOffsetX = (int) Math.round(-Math.sin(yawRad) * 3.0);
        int forwardOffsetZ = (int) Math.round(Math.cos(yawRad) * 3.0);
        
        // The preview CENTER is at rawTargetPos + forwardOffset
        BlockPos targetPos = rawTargetPos.add(forwardOffsetX, 0, forwardOffsetZ);
        
        BlockPos goal = SafePositionService.findSafeNear(commanderWorld, targetPos, 8);
        if (goal == null) {
            goal = SafePositionService.findSafeNear(commanderWorld, rawTargetPos.toImmutable(), 8);
        }
        if (goal == null) {
            ChatUtils.sendSystemMessage(source, "Can't find a safe spot near there.");
            return 0;
        }

        // Resolve target bot(s)
        List<ServerPlayerEntity> bots;
        if (targetArg == null) {
            // Default: find bots following the commander
            bots = new ArrayList<>();
            for (ServerPlayerEntity candidate : BotEventHandler.getRegisteredBots(source.getServer())) {
                if (candidate == null || candidate.isRemoved()) continue;
                if (candidate.getEntityWorld() != commanderWorld) continue;
                if (BotEventHandler.getCurrentMode(candidate) != BotEventHandler.Mode.FOLLOW) continue;
                if (!commander.getUuid().equals(BotEventHandler.getFollowTargetUuid(candidate))) continue;
                bots.add(candidate);
            }
        } else {
            bots = resolveTargetBots(context, targetArg);
        }

        if (bots.isEmpty()) {
            ChatUtils.sendSystemMessage(source, "No bots are following you.");
            return 0;
        }

        // Use the first bot
        ServerPlayerEntity bot = bots.get(0);
        MinecraftServer server = source.getServer();

        // Abort any current task
        TaskService.forceAbort(bot.getUuid(), "§cInterrupted by /bot build_look.");
        BotIdleHobbiesService.snoozeFor(bot, 3_600L);

        // Move the bot to the target location first, then run build skill
        final BlockPos finalGoal = goal;
        final String finalSchematic = schematicName;
        final BlockPos finalTargetPos = targetPos; // Store exact raycast position for build centering
        ChatUtils.sendSystemMessage(source, bot.getGameProfile().name() + " is heading to build " + schematicName + " where you're looking.");

        // Use async movement to the position, then run the build skill
        // Non-destructive move: do not trigger come-recovery digging skills while repositioning.
        BotEventHandler.setComeModeWalk(bot, commander, finalGoal, 3.2D, false);

        // Schedule the build skill to run after the bot arrives
        server.execute(() -> {
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor().schedule(() -> {
                server.execute(() -> {
                    // Run the build skill with exact target position for centering
                    String skillArgs = finalSchematic + " " + bot.getGameProfile().name()
                            + " targetX=" + finalTargetPos.getX()
                            + " targetY=" + finalTargetPos.getY()
                            + " targetZ=" + finalTargetPos.getZ();
                    try {
                        executeSkillTargets(context, "build", skillArgs);
                    } catch (CommandSyntaxException e) {
                        LOGGER.warn("Failed to execute build skill: {}", e.getMessage());
                    }
                });
            }, 2, java.util.concurrent.TimeUnit.SECONDS);
        });

        return 1;
    }

    private static int executeCome(CommandContext<ServerCommandSource> context,
                                  ServerPlayerEntity bot,
                                  ServerPlayerEntity commander,
                                  boolean allowRecoverySkills) {
        if (bot == null || commander == null) {
            return 0;
        }

        // Commander-issued override: always preempt idle hobbies.
        interruptAmbientHobbyIfAny(bot, "§cInterrupted by /bot come.");
        boolean teleportAllowed = SkillPreferences.teleportDuringSkills(bot);
        if (!teleportAllowed && !bot.canSee(commander) && !hasNavigationTool(bot)) {
            ChatUtils.sendSystemMessage(context.getSource(),
                    "I don't have any navigation tools to find you.");
            return 0;
        }

        // Come should be "self-healing": keep replanning like follow-walk does, instead of relying on a single
        // direct-path attempt that can be blocked by doorway/fence/corner geometry.
        if (!teleportAllowed) {
            // Come is a player-issued override; abort any running skill so follow-walk can take over immediately.
            TaskService.forceAbort(bot.getUuid(), "§cInterrupted by /bot come.");
            BotIdleHobbiesService.snoozeFor(bot, 3_600L);
            BlockPos goal = commander.getBlockPos().toImmutable();
            // Use a nearby 2-block-headroom goal when possible; even in walk-only mode this helps avoid
            // targeting positions right on a cliff lip / cave mouth where pathing tends to oscillate.
            net.minecraft.server.world.ServerWorld commanderWorld = commander.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld sw ? sw : null;
            if (commanderWorld != null) {
                BlockPos safe = net.wcfcarolina13.GameAI.services.SafePositionService.findForwardSafeSpot(commanderWorld, commander);
                if (safe == null) {
                    safe = net.wcfcarolina13.GameAI.services.SafePositionService.findSafeNear(commanderWorld, goal, 8);
                }
                if (safe != null) {
                    goal = safe;
                }
            }
            BotEventHandler.setComeModeWalk(bot, commander, goal, 3.2D, allowRecoverySkills);
            return 1;
        }

        BlockPos rawGoal = commander.getBlockPos().toImmutable();
        BlockPos safeGoal = null;
        net.minecraft.server.world.ServerWorld commanderWorld = commander.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld sw ? sw : null;
        if (commanderWorld != null) {
            safeGoal = net.wcfcarolina13.GameAI.services.SafePositionService.findForwardSafeSpot(commanderWorld, commander);
            if (safeGoal == null) {
                safeGoal = net.wcfcarolina13.GameAI.services.SafePositionService.findSafeNear(commanderWorld, rawGoal, 8);
            }
        }
        BlockPos goal = safeGoal != null ? safeGoal : rawGoal;

        BotIdleHobbiesService.snoozeFor(bot, 3_600L);

        MovementService.MovementPlan plan = new MovementService.MovementPlan(
            MovementService.Mode.DIRECT,
            goal,
            goal,
            null,
            null,
            bot.getHorizontalFacing());
        MovementService.MovementResult result = MovementService.execute(bot.getCommandSource(), bot, plan, Boolean.TRUE);
        if (result.success()) {
            return 1;
        }

        ChatUtils.sendSystemMessage(context.getSource(),
                bot.getName().getString() + " could not reach you: " + result.detail());
        return 0;
    }

    private static boolean hasNavigationTool(ServerPlayerEntity bot) {
        if (bot == null) {
            return false;
        }
        for (int slot = 0; slot < bot.getInventory().size(); slot++) {
            if (bot.getInventory().getStack(slot).isOf(Items.COMPASS)
                    || bot.getInventory().getStack(slot).isOf(Items.RECOVERY_COMPASS)
                    || bot.getInventory().getStack(slot).isOf(Items.FILLED_MAP)
                    || bot.getInventory().getStack(slot).isOf(Items.MAP)) {
                return true;
            }
        }
        return false;
    }

    static int executeCompanionComeTargets(CommandContext<ServerCommandSource> context, String targetArg) {
        ServerCommandSource source = context.getSource();
        MinecraftServer server = source.getServer();
        ServerPlayerEntity commander;
        try {
            commander = source.getPlayer();
        } catch (Exception e) {
            commander = null;
        }
        if (server == null || commander == null) {
            ChatUtils.sendSystemMessage(source, "Only players can call a companion.");
            return 0;
        }
        boolean recruitmentMode = SurvivalRecruitmentService.isEnabled(server);
        ManualConfig.SurvivalRecruitmentState st = SurvivalRecruitmentService.getState(server);
        String alias = resolveCompanionAlias(st, targetArg);

        if (recruitmentMode) {
            if (st == null || !st.isRecruited()) {
                ChatUtils.sendSystemMessage(source, "This world hasn't recruited a companion yet. Go to a village and recruit first (HUD prompt / Dialogue → Make contact (Recruit)).");
                return 0;
            }
            if (!st.isPermanentCompanion()) {
                ChatUtils.sendSystemMessage(source, "They're not a permanent companion yet.");
                return 0;
            }
            if (!isAuthorizedCompanionCommander(commander, st)) {
                ChatUtils.sendSystemMessage(source, "You aren't the one they pledged to.");
                return 0;
            }
            String recruitedAlias = st.getBotAlias();
            if (targetArg != null && !targetArg.isBlank() && !recruitedAlias.equalsIgnoreCase(targetArg.trim())) {
                ChatUtils.sendSystemMessage(source, "This companion command only applies to '" + recruitedAlias + "'.");
                return 0;
            }
        }

        if (!canUseCompanionCome(server, commander, st)) {
            if (recruitmentMode) {
                ChatUtils.sendSystemMessage(source, "To call your companion, cast the spell at an Enchanting Table (or use your Wizard's Tome / Goat Horn)." );
            } else {
                ChatUtils.sendSystemMessage(source, "To call the bot, cast at an Enchanting Table (or use your Wizard's Tome / Goat Horn).");
            }
            return 0;
        }

        ServerPlayerEntity bot = server.getPlayerManager().getPlayer(alias);
        if (bot == null || bot.isRemoved() || !bot.isAlive()) {
            ChatUtils.sendSystemMessage(source, alias + " isn't here right now. Try: /bot companion summon");
            return 0;
        }
        if (!(bot instanceof createFakePlayer)) {
            ChatUtils.sendSystemMessage(source, "Can't control a real player as a companion.");
            return 0;
        }
        if (!(commander.getEntityWorld() instanceof ServerWorld commanderWorld)) {
            return 0;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld botWorld)
                || botWorld.getRegistryKey() != commanderWorld.getRegistryKey()) {
            ChatUtils.sendSystemMessage(source, alias + " can't come to you across dimensions.");
            return 0;
        }

        TaskService.forceAbort(bot.getUuid(), "§cInterrupted by /bot companion come.");
        BotIdleHobbiesService.snoozeFor(bot, 3_600L);

        BlockPos goal = commander.getBlockPos().toImmutable();
        BlockPos safe = net.wcfcarolina13.GameAI.services.SafePositionService.findForwardSafeSpot(commanderWorld, commander);
        if (safe == null) {
            safe = net.wcfcarolina13.GameAI.services.SafePositionService.findSafeNear(commanderWorld, goal, 8);
        }
        if (safe != null) {
            goal = safe;
        }

        // Non-destructive move: do not trigger come-recovery digging skills while coming to the commander.
        String result = BotEventHandler.setComeModeWalk(bot, commander, goal, 3.2D, false);
        ChatUtils.sendSystemMessage(source, result);
        return 1;
    }

    static int executeCompanionSummonTargets(CommandContext<ServerCommandSource> context, String targetArg) {
        ServerCommandSource source = context.getSource();
        MinecraftServer server = source.getServer();
        ServerPlayerEntity commander;
        try {
            commander = source.getPlayer();
        } catch (Exception e) {
            commander = null;
        }
        if (server == null || commander == null) {
            ChatUtils.sendSystemMessage(source, "Only players can summon a companion.");
            return 0;
        }
        boolean recruitmentMode = SurvivalRecruitmentService.isEnabled(server);
        ManualConfig.SurvivalRecruitmentState st = SurvivalRecruitmentService.getState(server);
        String alias = resolveCompanionAlias(st, targetArg);

        if (recruitmentMode) {
            if (st == null || !st.isRecruited()) {
                ChatUtils.sendSystemMessage(source, "This world hasn't recruited a companion yet. Go to a village and recruit first (HUD prompt / Dialogue → Make contact (Recruit)).");
                return 0;
            }
            if (!st.isPermanentCompanion()) {
                ChatUtils.sendSystemMessage(source, "They're not a permanent companion yet.");
                return 0;
            }
            if (!isAuthorizedCompanionCommander(commander, st)) {
                ChatUtils.sendSystemMessage(source, "You aren't the one they pledged to.");
                return 0;
            }
            String recruitedAlias = st.getBotAlias();
            if (targetArg != null && !targetArg.isBlank() && !recruitedAlias.equalsIgnoreCase(targetArg.trim())) {
                ChatUtils.sendSystemMessage(source, "This companion command only applies to '" + recruitedAlias + "'.");
                return 0;
            }
        }

        if (!canUseCompanionSummon(server, commander, st)) {
            if (recruitmentMode) {
                ChatUtils.sendSystemMessage(source, "To summon your companion, cast the spell at an Enchanting Table (or use your Wizard's Tome), or carry an Eye of Ender (cooldown)." );
            } else {
                ChatUtils.sendSystemMessage(source, "To summon the bot, cast at an Enchanting Table (or use your Wizard's Tome), or carry an Eye of Ender (cooldown).");
            }
            return 0;
        }

        boolean hasBook = hasSpellbookToken(commander);
        boolean nearTable = isNearEnchantingTable(commander, 4);
        boolean nearAnchor = recruitmentMode && isNearCompanionAnchor(server, commander, st, 12.0D);
        boolean hasEye = hasEyeOfEnderToken(commander);
        boolean usingEye = !hasBook && !nearTable && !nearAnchor && hasEye;

        ServerPlayerEntity bot = server.getPlayerManager().getPlayer(alias);
        if (bot == null || bot.isRemoved() || !bot.isAlive()) {
            // If the companion is permanent, it's reasonable to spawn them if missing.
            bot = trySpawnCompanion(server, source, alias);
        }
        if (bot == null || bot.isRemoved() || !bot.isAlive()) {
            ChatUtils.sendSystemMessage(source, "Couldn't summon " + alias + " (not spawned)." );
            return 0;
        }
        if (!(bot instanceof createFakePlayer)) {
            ChatUtils.sendSystemMessage(source, "Can't summon a real player as a companion.");
            return 0;
        }
        if (!(commander.getEntityWorld() instanceof ServerWorld commanderWorld)) {
            return 0;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld botWorld)) {
            return 0;
        }
        boolean crossDim = botWorld.getRegistryKey() != commanderWorld.getRegistryKey();
        if (crossDim) {
            boolean fullAccess = hasSpellbookToken(commander) || isNearEnchantingTable(commander, 4);
            if (!fullAccess) {
                ChatUtils.sendSystemMessage(source,
                    alias + " can't be summoned across dimensions. Use an Enchanting Table or Wizard's Tome.");
                return 0;
            }
        }

        long nowTick = server.getTicks();
        if (usingEye) {
            Long last = COMPANION_EYE_SPELL_LAST_TICK.get(commander.getUuid());
            if (last != null && (nowTick - last) < COMPANION_EYE_SPELL_COOLDOWN_TICKS) {
                long remaining = COMPANION_EYE_SPELL_COOLDOWN_TICKS - (nowTick - last);
                ChatUtils.sendSystemMessage(source, "Eye of Ender spell is on cooldown (" + (remaining / 20L) + "s)." );
                return 0;
            }
        }

        BlockPos goal = net.wcfcarolina13.GameAI.services.SafePositionService.findForwardSafeSpot(commanderWorld, commander);
        if (goal == null) {
            goal = net.wcfcarolina13.GameAI.services.SafePositionService.findSafeNear(commanderWorld, commander.getBlockPos(), 8);
        }
        if (goal == null) {
            goal = commander.getBlockPos().toImmutable();
        }

        // Stop what the bot was doing before teleporting.
        TaskService.forceAbort(bot.getUuid(), "§cSummoned by companion command.");
        BotIdleHobbiesService.snoozeFor(bot, 3_600L);
        try {
            BotEventHandler.stopFollowing(bot);
        } catch (Exception ignored) {
        }
        BotActions.stop(bot);

        // goal is a *feet* position (2-block headroom); teleport using feet Y.
        Vec3d center = new Vec3d(goal.getX() + 0.5D, goal.getY(), goal.getZ() + 0.5D);
        bot.teleport(commanderWorld,
                center.x, center.y, center.z,
            EnumSet.noneOf(net.minecraft.network.packet.s2c.play.PositionFlag.class),
                commander.getYaw(),
                commander.getPitch(),
                true);
        bot.setVelocity(Vec3d.ZERO);
        if (usingEye) {
            COMPANION_EYE_SPELL_LAST_TICK.put(commander.getUuid(), nowTick);
        }

        ChatUtils.sendSystemMessage(source, "Summoned " + alias + " to your location.");
        return 1;
    }

    static int executeCompanionOpenTargets(CommandContext<ServerCommandSource> context, String targetArg) {
        ServerCommandSource source = context.getSource();
        MinecraftServer server = source.getServer();
        ServerPlayerEntity commander;
        try {
            commander = source.getPlayer();
        } catch (Exception e) {
            commander = null;
        }
        if (server == null || commander == null) {
            ChatUtils.sendSystemMessage(source, "Only players can remotely open a companion's inventory.");
            return 0;
        }
        boolean recruitmentMode = SurvivalRecruitmentService.isEnabled(server);
        ManualConfig.SurvivalRecruitmentState st = SurvivalRecruitmentService.getState(server);
        String alias = resolveCompanionAlias(st, targetArg);

        if (recruitmentMode) {
            if (st == null || !st.isRecruited()) {
                ChatUtils.sendSystemMessage(source, "This world hasn't recruited a companion yet.");
                return 0;
            }
            if (!st.isPermanentCompanion()) {
                ChatUtils.sendSystemMessage(source, "They're not a permanent companion yet.");
                return 0;
            }
            if (!isAuthorizedCompanionCommander(commander, st)) {
                ChatUtils.sendSystemMessage(source, "You aren't the one they pledged to.");
                return 0;
            }
            String recruitedAlias = st.getBotAlias();
            if (targetArg != null && !targetArg.isBlank() && !recruitedAlias.equalsIgnoreCase(targetArg.trim())) {
                ChatUtils.sendSystemMessage(source, "This companion command only applies to '" + recruitedAlias + "'.");
                return 0;
            }
        }

        // Full access required (Wizard's Tome or Enchanting Table) — Eye of Ender is NOT sufficient.
        boolean fullAccess = hasSpellbookToken(commander) || isNearEnchantingTable(commander, 4);
        if (!fullAccess) {
            ChatUtils.sendSystemMessage(source, "Remote inventory requires an Enchanting Table or Wizard's Tome.");
            return 0;
        }

        ServerPlayerEntity bot = server.getPlayerManager().getPlayer(alias);
        if (bot == null || bot.isRemoved() || !bot.isAlive()) {
            ChatUtils.sendSystemMessage(source, "Couldn't open inventory for " + alias + " (not spawned).");
            return 0;
        }

        boolean ok = BotInventoryAccess.openBotInventoryRemote(commander, bot);
        if (!ok) {
            ChatUtils.sendSystemMessage(source, "Failed to open " + alias + "'s inventory.");
            return 0;
        }
        return 1;
    }

    static int executeCompanionHomeTargets(CommandContext<ServerCommandSource> context, String targetArg) {
        ServerCommandSource source = context.getSource();
        MinecraftServer server = source.getServer();
        ServerPlayerEntity commander;
        try {
            commander = source.getPlayer();
        } catch (Exception e) {
            commander = null;
        }
        if (server == null || commander == null) {
            ChatUtils.sendSystemMessage(source, "Only players can send a companion home.");
            return 0;
        }
        boolean recruitmentMode = SurvivalRecruitmentService.isEnabled(server);
        ManualConfig.SurvivalRecruitmentState st = SurvivalRecruitmentService.getState(server);
        String alias = resolveCompanionAlias(st, targetArg);

        if (recruitmentMode) {
            if (st == null || !st.isRecruited()) {
                ChatUtils.sendSystemMessage(source, "This world hasn't recruited a companion yet. Go to a village and recruit first (HUD prompt / Dialogue → Make contact (Recruit)).");
                return 0;
            }
            if (!st.isPermanentCompanion()) {
                ChatUtils.sendSystemMessage(source, "They're not a permanent companion yet.");
                return 0;
            }
            if (!isAuthorizedCompanionCommander(commander, st)) {
                ChatUtils.sendSystemMessage(source, "You aren't the one they pledged to.");
                return 0;
            }
            String recruitedAlias = st.getBotAlias();
            if (targetArg != null && !targetArg.isBlank() && !recruitedAlias.equalsIgnoreCase(targetArg.trim())) {
                ChatUtils.sendSystemMessage(source, "This companion command only applies to '" + recruitedAlias + "'.");
                return 0;
            }
        }

        if (!canUseCompanionHome(server, commander, st)) {
            if (recruitmentMode) {
                ChatUtils.sendSystemMessage(source, "To send your companion home, cast the spell at an Enchanting Table (or use your Wizard's Tome)." );
            } else {
                ChatUtils.sendSystemMessage(source, "To send the bot home, cast at an Enchanting Table (or use your Wizard's Tome).");
            }
            return 0;
        }

        ServerPlayerEntity bot = server.getPlayerManager().getPlayer(alias);
        if (bot == null || bot.isRemoved() || !bot.isAlive()) {
            ChatUtils.sendSystemMessage(source, alias + " isn't here right now.");
            return 0;
        }
        if (!(bot instanceof createFakePlayer)) {
            ChatUtils.sendSystemMessage(source, "Can't control a real player as a companion.");
            return 0;
        }

        String result;
        if (recruitmentMode) {
            ResolvedCompanionAnchor anchor = resolveCompanionAnchor(server, st);
            if (anchor == null || anchor.world == null || anchor.pos == null) {
                ChatUtils.sendSystemMessage(source, "No village anchor set. Use the Dialogue topic 'Set this as our home'.");
                return 0;
            }
            if (!(bot.getEntityWorld() instanceof ServerWorld botWorld)
                    || botWorld.getRegistryKey() != anchor.world.getRegistryKey()) {
                ChatUtils.sendSystemMessage(source, alias + " can't go home across dimensions.");
                return 0;
            }

            BlockPos home = net.wcfcarolina13.GameAI.services.SafePositionService.findSafeNear(anchor.world, anchor.pos, 8);
            if (home == null) {
                home = anchor.pos;
            }
            result = BotEventHandler.setReturnToBase(bot, Vec3d.ofCenter(home));
        } else {
            result = BotEventHandler.setReturnToBase(bot);
        }
        ChatUtils.sendSystemMessage(source, result);
        return 1;
    }

    private static String resolveCompanionAlias(ManualConfig.SurvivalRecruitmentState st, String targetArg) {
        if (targetArg != null && !targetArg.isBlank()) {
            return targetArg.trim();
        }
        if (st != null && st.getBotAlias() != null && !st.getBotAlias().isBlank()) {
            return st.getBotAlias();
        }
        return "Jake";
    }

    private static boolean isAuthorizedCompanionCommander(ServerPlayerEntity player, ManualConfig.SurvivalRecruitmentState st) {
        if (player == null || st == null) {
            return false;
        }
        String recruiterUuid = st.getRecruitedByUuid();
        if (recruiterUuid != null && !recruiterUuid.isBlank() && recruiterUuid.equals(player.getUuidAsString())) {
            return true;
        }
        return Frens.isOperator(player);
    }

    private static boolean canUseCompanionCome(MinecraftServer server, ServerPlayerEntity commander, ManualConfig.SurvivalRecruitmentState st) {
        if (commander == null || server == null) {
            return false;
        }
        if (hasSpellbookToken(commander)) {
            return true;
        }
        if (isNearEnchantingTable(commander, 4)) {
            return true;
        }
        // Mid-game convenience: Goat Horn can call them (come-only).
        if (hasGoatHornToken(commander)) {
            return true;
        }
        return st != null && isNearCompanionAnchor(server, commander, st, 12.0D);
    }

    private static boolean canUseCompanionSummon(MinecraftServer server, ServerPlayerEntity commander, ManualConfig.SurvivalRecruitmentState st) {
        if (commander == null || server == null) {
            return false;
        }
        if (hasSpellbookToken(commander)) {
            return true;
        }
        if (isNearEnchantingTable(commander, 4)) {
            return true;
        }
        // Emergency/limited access: Eye of Ender (cooldown handled at cast time).
        if (hasEyeOfEnderToken(commander)) {
            return true;
        }
        return st != null && isNearCompanionAnchor(server, commander, st, 12.0D);
    }

    private static boolean hasEyeOfEnderToken(ServerPlayerEntity commander) {
        if (commander == null) {
            return false;
        }
        var inv = commander.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            if (stack.isOf(Items.ENDER_EYE)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasGoatHornToken(ServerPlayerEntity commander) {
        if (commander == null) {
            return false;
        }
        var inv = commander.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            if (stack.isOf(Items.GOAT_HORN)) {
                return true;
            }
        }
        return false;
    }

    private static boolean canUseCompanionHome(MinecraftServer server, ServerPlayerEntity commander, ManualConfig.SurvivalRecruitmentState st) {
        if (commander == null || server == null) {
            return false;
        }
        if (hasSpellbookToken(commander)) {
            return true;
        }
        if (isNearEnchantingTable(commander, 4)) {
            return true;
        }
        return st != null && isNearCompanionAnchor(server, commander, st, 16.0D);
    }

    private static boolean isNearEnchantingTable(ServerPlayerEntity commander, int radius) {
        if (commander == null) {
            return false;
        }
        if (!(commander.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        BlockPos origin = commander.getBlockPos();
        int r = Math.max(1, radius);
        for (BlockPos pos : BlockPos.iterate(origin.add(-r, -2, -r), origin.add(r, 2, r))) {
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            BlockState state = world.getBlockState(pos);
            if (state.isOf(net.minecraft.block.Blocks.ENCHANTING_TABLE)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSpellbookToken(ServerPlayerEntity commander) {
        if (commander == null) {
            return false;
        }
        var inv = commander.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            if (stack.isOf(ModItems.WIZARD_TOME)) {
                return true;
            }
            if (!(stack.isOf(Items.WRITTEN_BOOK) || stack.isOf(Items.ENCHANTED_BOOK))) {
                continue;
            }
            String name = stack.getName() != null ? stack.getName().getString() : "";
            String lower = name != null ? name.toLowerCase(Locale.ROOT) : "";
            if (lower.contains("spellbook")) {
                return true;
            }
            if (lower.contains("wizard") && lower.contains("tome")) {
                return true;
            }
        }
        return false;
    }

    private static int executeGiveWizardTome(ServerCommandSource source, int amount) {
        if (source == null) {
            return 0;
        }
        if (!net.wcfcarolina13.Frens.isOperator(source)) {
            ChatUtils.sendSystemMessage(source, "Operator-only command.");
            return 0;
        }

        ServerPlayerEntity player;
        try {
            player = source.getPlayer();
        } catch (Exception e) {
            player = null;
        }
        if (player == null) {
            ChatUtils.sendSystemMessage(source, "Only players can receive items.");
            return 0;
        }

        int n = WizardTomeGrantService.grant(player, amount);
        if (n <= 0) {
            ChatUtils.sendSystemMessage(source, "Could not grant Wizard's Tome.");
            return 0;
        }
        ChatUtils.sendSystemMessage(source, "Gave Wizard's Tome x" + n + ".");
        return 1;
    }

    private static boolean isNearCompanionAnchor(MinecraftServer server, ServerPlayerEntity commander, ManualConfig.SurvivalRecruitmentState st, double maxDist) {
        if (server == null || commander == null || st == null) {
            return false;
        }
        ResolvedCompanionAnchor anchor = resolveCompanionAnchor(server, st);
        if (anchor == null || anchor.world == null || anchor.pos == null) {
            return false;
        }
        if (!(commander.getEntityWorld() instanceof ServerWorld world)
                || world.getRegistryKey() != anchor.world.getRegistryKey()) {
            return false;
        }
        double maxSq = maxDist * maxDist;
        return commander.getBlockPos().getSquaredDistance(anchor.pos) <= maxSq;
    }

    private static ServerPlayerEntity trySpawnCompanion(MinecraftServer server, ServerCommandSource source, String alias) {
        if (server == null || source == null || alias == null || alias.isBlank()) {
            return null;
        }

        // Do not bypass the Nether ritual if the companion is dead.
        try {
            if (SurvivalRecruitmentService.isEnabled(server)) {
                ManualConfig.SurvivalRecruitmentState st = SurvivalRecruitmentService.getState(server);
                if (st != null && st.isRecruited() && st.isCompanionDead()) {
                    ChatUtils.sendSystemMessage(source, alias + " is dead.");
                    ChatUtils.sendSystemMessage(source, "You need to perform the Nether ritual to bring them back.");
                    return null;
                }
            }
        } catch (Throwable ignored) {
        }

        ServerPlayerEntity commander = null;
        try {
            commander = source.getPlayer();
        } catch (Exception ignored) {
        }

        ServerWorld commanderWorld = null;
        if (commander != null && commander.getEntityWorld() instanceof ServerWorld sw) {
            commanderWorld = sw;
        }
        if (commanderWorld == null) {
            commanderWorld = server.getOverworld();
        }
        if (commanderWorld == null) {
            return null;
        }

        // Spawn near the commander.
        BlockPos goal = commander != null
                ? net.wcfcarolina13.GameAI.services.SafePositionService.findForwardSafeSpot(commanderWorld, commander)
                : null;
        if (goal == null && commander != null) {
            goal = net.wcfcarolina13.GameAI.services.SafePositionService.findSafeNear(commanderWorld, commander.getBlockPos(), 8);
        }
        if (goal == null) {
            double centerX = commanderWorld.getWorldBorder().getCenterX();
            double centerZ = commanderWorld.getWorldBorder().getCenterZ();
            int spawnX = (int) Math.round(centerX);
            int spawnZ = (int) Math.round(centerZ);
            int spawnY = commanderWorld.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, spawnX, spawnZ);
            goal = new BlockPos(spawnX, spawnY, spawnZ);
        }

        // goal is a *feet* position (2-block headroom); spawn using feet Y.
        Vec3d pos = new Vec3d(goal.getX() + 0.5D, goal.getY(), goal.getZ() + 0.5D);

        GameMode mode = GameMode.SURVIVAL;
        if (Frens.CONFIG != null) {
            ManualConfig.BotControlSettings ctrl = Frens.CONFIG.getEffectiveBotControl(alias);
            if (ctrl != null && "creative".equalsIgnoreCase(ctrl.getGameMode())) {
                mode = GameMode.CREATIVE;
            }
        }

        // Prevent immediate "resume where you were" from overriding this summon.
        try {
            net.wcfcarolina13.GameAI.services.BotWorldStateService.clearState(server, alias);
        } catch (Throwable ignored) {
        }

        try {
            createFakePlayer.createFake(alias, server, pos,
                    commander != null ? commander.getYaw() : 0.0F,
                    commander != null ? commander.getPitch() : 0.0F,
                    commanderWorld.getRegistryKey(),
                    mode,
                    false);
        } catch (Exception e) {
            LOGGER.warn("Companion summon: failed to spawn {}: {}", alias, e.getMessage());
            return null;
        }

        server.execute(() -> {
            ServerPlayerEntity bot = server.getPlayerManager().getPlayer(alias);
            if (bot != null && !bot.isRemoved() && bot instanceof createFakePlayer) {
                try {
                    BotEventHandler.registerBot(bot);
                } catch (Throwable ignored) {
                }
                try {
                    net.wcfcarolina13.Entity.AutoFaceEntity.startAutoFace(bot);
                } catch (Throwable ignored) {
                }
            }
        });

        return server.getPlayerManager().getPlayer(alias);
    }

    private static final class ResolvedCompanionAnchor {
        final ServerWorld world;
        final BlockPos pos;

        private ResolvedCompanionAnchor(ServerWorld world, BlockPos pos) {
            this.world = world;
            this.pos = pos;
        }
    }

    private static ResolvedCompanionAnchor resolveCompanionAnchor(MinecraftServer server, ManualConfig.SurvivalRecruitmentState st) {
        if (server == null || st == null) {
            return null;
        }
        if (!st.isCompanionAnchorSet()) {
            return null;
        }
        String dim = st.getCompanionAnchorDimension();
        long posLong = st.getCompanionAnchorPos();
        if (dim == null || dim.isBlank() || posLong == 0L) {
            return null;
        }
        Identifier id = Identifier.tryParse(dim.trim());
        if (id == null) {
            return null;
        }
        RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, id);
        ServerWorld world = server.getWorld(key);
        if (world == null) {
            return null;
        }
        return new ResolvedCompanionAnchor(world, BlockPos.fromLong(posLong));
    }

    private record PlacementTarget(BlockPos hitPos, Direction face, float yaw, float pitch) {}

    private static PlacementTarget capturePlacementTarget(ServerPlayerEntity commander) {
        if (commander == null) {
            return null;
        }
        var hit = commander.raycast(6.0D, 1.0F, false);
        if (!(hit instanceof net.minecraft.util.hit.BlockHitResult bhr)) {
            return null;
        }
        return new PlacementTarget(bhr.getBlockPos(), bhr.getSide(), commander.getYaw(), commander.getPitch());
    }

    private static BlockPos attemptPlacement(ServerPlayerEntity bot, PlacementTarget target, int itemSlot, net.minecraft.item.Item placeItem) {
        if (bot == null || target == null) {
            return null;
        }
        BlockPos placePos = target.hitPos.offset(target.face);
        bot.setYaw(target.yaw);
        bot.setHeadYaw(target.yaw);
        bot.setPitch(target.pitch);

        int tries = 0;
        while (tries < 10) {
            // Clear snow on target
            var state = bot.getEntityWorld().getBlockState(placePos);
            if (state.isOf(net.minecraft.block.Blocks.SNOW) || state.isOf(net.minecraft.block.Blocks.SNOW_BLOCK)) {
                if (bot.getEntityWorld() instanceof ServerWorld sw) {
                    var auth = net.wcfcarolina13.GameAI.services.BotTerritoryAuthorizationService
                            .authorizeBlockMutation(bot, sw, placePos);
                    if (auth.allowed()) {
                        bot.getEntityWorld().breakBlock(placePos, false);
                    }
                }
            }
            if (BotActions.placeBlockAt(bot, placePos, target.face, List.of(placeItem))) {
                return placePos.toImmutable();
            }
            // try moving closer and retry
            BlockPos approach = placePos.offset(target.face.getOpposite());
            MovementService.MovementPlan plan = new MovementService.MovementPlan(
                    MovementService.Mode.DIRECT,
                    approach,
                    approach,
                    null,
                    null,
                    target.face.getOpposite());
            MovementService.execute(bot.getCommandSource(), bot, plan, false);
            // Nudge sideways to avoid occupying the placement spot
            MovementService.MovementPlan sidestep = new MovementService.MovementPlan(
                    MovementService.Mode.DIRECT,
                    approach.offset(target.face.rotateYClockwise()),
                    approach.offset(target.face.rotateYClockwise()),
                    null,
                    null,
                    target.face.getOpposite());
            MovementService.execute(bot.getCommandSource(), bot, sidestep, false);
            tries++;
        }
        return null;
    }

    private static int findItem(ServerPlayerEntity bot, net.minecraft.item.Item item) {
        if (bot == null) {
            return -1;
        }
        for (int i = 0; i < bot.getInventory().size(); i++) {
            if (bot.getInventory().getStack(i).isOf(item)) {
                return i;
            }
        }
        return -1;
    }

    private static net.minecraft.item.Item resolvePlaceable(String item) {
        return switch (item.toLowerCase()) {
            case "crafting_table", "craftingtable" -> Items.CRAFTING_TABLE;
            case "furnace" -> Items.FURNACE;
            case "chest" -> Items.CHEST;
            default -> null;
        };
    }

    private static BlockPos attemptAdjacentChest(ServerPlayerEntity bot, BlockPos anchor, int slot, net.minecraft.item.Item placeItem) {
        if (anchor == null) {
            return null;
        }
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos candidate = anchor.offset(dir);
            PlacementTarget alt = new PlacementTarget(candidate, dir.getOpposite(), bot.getYaw(), bot.getPitch());
            if (bot.getEntityWorld().getBlockState(candidate).isAir()) {
                BlockPos placed = attemptPlacement(bot, alt, slot, placeItem);
                if (placed != null) {
                    return placed;
                }
            }
        }
        return null;
    }

    private static int executeFollowStop(CommandContext<ServerCommandSource> context, ServerPlayerEntity bot) {
        interruptAmbientHobbyIfAny(bot, "§cInterrupted by /bot follow stop.");
        // Commands already emit a system summary; avoid redundant bot-authored chat acks.
        BotEventHandler.stopFollowing(bot, false);
        return 1;
    }

    static int executeGuard(CommandContext<ServerCommandSource> context, ServerPlayerEntity bot, double radius) {
        rememberTarget(context.getSource(), bot);
        interruptAmbientHobbyIfAny(bot, "§cInterrupted by /bot guard.");
        String result = BotEventHandler.setGuardMode(bot, radius);
        ChatUtils.sendSystemMessage(context.getSource(), result);
        return 1;
    }

    static int executePatrol(CommandContext<ServerCommandSource> context, ServerPlayerEntity bot, double radius) {
        rememberTarget(context.getSource(), bot);
        interruptAmbientHobbyIfAny(bot, "§cInterrupted by /bot patrol.");
        String result = BotEventHandler.setPatrolMode(bot, radius);
        ChatUtils.sendSystemMessage(context.getSource(), result);
        return 1;
    }

    static int executeStay(CommandContext<ServerCommandSource> context, ServerPlayerEntity bot) {
        rememberTarget(context.getSource(), bot);
        interruptAmbientHobbyIfAny(bot, "§cInterrupted by /bot stay.");
        String result = BotEventHandler.setStayMode(bot);
        ChatUtils.sendSystemMessage(context.getSource(), result);
        return 1;
    }

    static int executeReturnToBase(CommandContext<ServerCommandSource> context, ServerPlayerEntity bot, ServerPlayerEntity commander) {
        rememberTarget(context.getSource(), bot);
        interruptAmbientHobbyIfAny(bot, "§cInterrupted by /bot return_base.");
        String result = BotEventHandler.setReturnToBase(bot, commander);
        ChatUtils.sendSystemMessage(context.getSource(), result);
        return 1;
    }

    static int executeEquip(CommandContext<ServerCommandSource> context, ServerPlayerEntity bot) {
        rememberTarget(context.getSource(), bot);
        ServerPlayerEntity commander = null;
        try {
            commander = context.getSource().getPlayer();
        } catch (Exception ignored) {
        }
        equipDefaultLoadout(context.getSource().getServer(), bot, commander);
        ChatUtils.sendSystemMessage(context.getSource(), "Equipping loadout on " + bot.getName().getString() + ".");
        return 1;
    }

    static int executeListBots(CommandContext<ServerCommandSource> context) {
        MinecraftServer server = context.getSource().getServer();
        if (server == null) {
            ChatUtils.sendSystemMessage(context.getSource(), "No server available.");
            return 0;
        }
        List<String> names = new ArrayList<>();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player instanceof net.wcfcarolina13.Entity.createFakePlayer && !player.isRemoved() && player.isAlive()) {
                names.add(player.getName().getString());
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        String remembered = BotTargetingService.getRemembered(context.getSource());
        if (names.isEmpty()) {
            ChatUtils.sendSystemMessage(context.getSource(), "No active bots.");
            return 1;
        }
        ChatUtils.sendSystemMessage(context.getSource(), "Active bots: " + String.join(", ", names));
        if (remembered != null) {
            ChatUtils.sendSystemMessage(context.getSource(), "Selected bot: " + remembered);
        } else {
            ChatUtils.sendSystemMessage(context.getSource(), "Selected bot: (none) — target one by name or use 'all'.");
        }
        return 1;
    }

    static int executeDespawnTargets(CommandContext<ServerCommandSource> context, String targetArg) throws CommandSyntaxException {
        List<ServerPlayerEntity> targets = BotTargetingService.resolve(context.getSource(), targetArg);
        boolean isAll = targetArg != null && "all".equalsIgnoreCase(targetArg.trim());
        int successes = 0;
        for (ServerPlayerEntity bot : targets) {
            BotTargetingService.forgetIfMatches(context.getSource(), bot.getName().getString());
            BotEventHandler.unregisterBot(bot);
            successes++;
        }
        if (!targets.isEmpty()) {
            String summary = formatBotList(targets, isAll);
            String verb = (isAll || targets.size() > 1) ? "have" : "has";
            ChatUtils.sendSystemMessage(context.getSource(), summary + " " + verb + " been despawned.");
        }
        return successes;
    }

    static int executeAssistToggle(CommandContext<ServerCommandSource> context, ServerPlayerEntity bot, boolean enable) {
        String result = BotEventHandler.toggleAssistAllies(bot, enable);
        ChatUtils.sendSystemMessage(context.getSource(), result);
        return 1;
    }

    static int executeDefendTargets(CommandContext<ServerCommandSource> context, String modeRaw, String targetArg) throws CommandSyntaxException {
        boolean enable = parseAssistMode(modeRaw);
        List<ServerPlayerEntity> bots = BotTargetingService.resolve(context.getSource(), targetArg);
        for (ServerPlayerEntity bot : bots) {
            BotEventHandler.setBotDefense(bot, enable);
        }
        if (!bots.isEmpty()) {
            boolean isAll = targetArg != null && "all".equalsIgnoreCase(targetArg.trim());
            String summary = formatBotList(bots, isAll);
            String action = enable ? "will defend nearby bots." : "will focus on their own fights.";
            ChatUtils.sendSystemMessage(context.getSource(), summary + " " + action);
        }
        return bots.size();
    }

    static int executeCombatStyle(CommandContext<ServerCommandSource> context, ServerPlayerEntity bot, BotEventHandler.CombatStyle style) {
        String result = BotEventHandler.setCombatStyle(bot, style);
        ChatUtils.sendSystemMessage(context.getSource(), result);
        return 1;
    }

    private static int executeInventorySummaryTargets(CommandContext<ServerCommandSource> context, String targetArg) throws CommandSyntaxException {
        List<ServerPlayerEntity> bots = BotTargetingService.resolve(context.getSource(), targetArg);
        LOGGER.info("Resolved " + bots.size() + " bots for inventory summary with targetArg: " + (targetArg != null ? targetArg : "null"));
        int successes = 0;
        for (ServerPlayerEntity bot : bots) {
            successes += executeInventorySummary(context, bot);
        }
        return successes;
    }

    static int executeInventoryCountTargets(CommandContext<ServerCommandSource> context, String targetArg, String itemId) throws CommandSyntaxException {
        Identifier id = Identifier.tryParse(itemId);
        if (id == null || !Registries.ITEM.containsId(id)) {
            ChatUtils.sendSystemMessage(context.getSource(), "Unknown item: " + itemId);
            return 0;
        }
        Item item = Registries.ITEM.get(id);
        List<ServerPlayerEntity> bots = BotTargetingService.resolve(context.getSource(), targetArg);
        int successes = 0;
        int grandTotal = 0;
        for (ServerPlayerEntity bot : bots) {
            grandTotal += emitInventoryCount(context.getSource(), bot, item);
            successes++;
        }
        if (bots.size() > 1) {
            ChatUtils.sendSystemMessage(context.getSource(),
                    "Combined total: " + grandTotal + "x " + item.getName().getString() + " across " + bots.size() + " bots.");
        }
        return successes;
    }

    static int executeInventorySaveTargets(CommandContext<ServerCommandSource> context, String targetArg) throws CommandSyntaxException {
        List<ServerPlayerEntity> bots = BotTargetingService.resolve(context.getSource(), targetArg);
        int successes = 0;
        for (ServerPlayerEntity bot : bots) {
            boolean success = BotInventoryStorageService.save(bot);
            if (success) {
                successes++;
                ChatUtils.sendSystemMessage(context.getSource(), "Saved inventory for " + bot.getName().getString() + ".");
            } else {
                ChatUtils.sendSystemMessage(context.getSource(), "Failed to save inventory for " + bot.getName().getString() + ".");
            }
        }
        return successes;
    }

    static int executeInventoryLoadTargets(CommandContext<ServerCommandSource> context, String targetArg) throws CommandSyntaxException {
        List<ServerPlayerEntity> bots = BotTargetingService.resolve(context.getSource(), targetArg);
        int successes = 0;
        for (ServerPlayerEntity bot : bots) {
            boolean success = BotInventoryStorageService.load(bot);
            if (success) {
                successes++;
                ChatUtils.sendSystemMessage(context.getSource(), "Loaded inventory for " + bot.getName().getString() + ".");
            } else {
                ChatUtils.sendSystemMessage(context.getSource(), "No saved inventory found for " + bot.getName().getString() + ".");
            }
        }
        return successes;
    }

    private static int executeInventorySummary(CommandContext<ServerCommandSource> context, ServerPlayerEntity bot) {
        PlayerInventory inventory = bot.getInventory();
        Map<Item, Integer> totals = new LinkedHashMap<>();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            totals.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        LOGGER.info("Found " + totals.size() + " unique items in " + bot.getName().getString() + "'s inventory.");
        if (totals.isEmpty()) {
            ChatUtils.sendSystemMessage(context.getSource(), bot.getName().getString() + " has an empty inventory.");
            return 1;
        }
        String summary = totals.entrySet()
                .stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(15)
                .map(entry -> entry.getValue() + "x " + entry.getKey().getName().getString())
                .reduce((a, b) -> a + ", " + b)
                .orElse("(no items)");
        ChatUtils.sendSystemMessage(context.getSource(), "Inventory summary for " + bot.getName().getString() + ": " + summary);
        return 1;
    }

    private static int emitInventoryCount(ServerCommandSource source, ServerPlayerEntity bot, Item item) {
        int total = countInventoryItems(bot, item);
        ChatUtils.sendSystemMessage(source, bot.getName().getString() + " is carrying " + total + "x " + item.getName().getString() + ".");
        return total;
    }

    private static int countInventoryItems(ServerPlayerEntity bot, Item item) {
        PlayerInventory inventory = bot.getInventory();
        int total = 0;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && stack.isOf(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int executeStop(CommandContext<ServerCommandSource> context, ServerPlayerEntity bot) {
        MinecraftServer server = context.getSource().getServer();
        if (server == null || bot == null) {
            return 0;
        }
        String alias = bot.getName().getString();
        String caller = context.getSource() != null ? context.getSource().getName() : "(unknown)";
        LOGGER.info("Stop command invoked: caller={} targetBot={} trainingMode={}", caller, alias, isTrainingMode);
        // Cancel any in-flight drop sweep so it doesn't keep driving movement after /stop.
        net.wcfcarolina13.GameAI.services.DropSweepService.requestCancel(bot, "command-stop");
        // Ensure follow state is cleared so the bot truly stops.
        net.wcfcarolina13.GameAI.BotEventHandler.stopFollowing(bot);
        stopMoving(server, context.getSource(), alias);
        TaskService.forceAbort(bot.getUuid(), "§cCurrent task aborted via /bot stop.");
        net.wcfcarolina13.PathFinding.PathTracer.flushAllMovementTasks();
        // Emergency cleanup: clear crouch locks / task flags so the bot isn't left "busy".
        net.wcfcarolina13.GameAI.services.SneakLockService.clear(bot.getUuid());
        net.wcfcarolina13.Entity.AutoFaceEntity.setBotExecutingTask(false);
        net.wcfcarolina13.GameAI.BotActions.sneak(bot, false);
        net.wcfcarolina13.GameAI.BotActions.stop(bot);
        ChatUtils.sendSystemMessage(context.getSource(), "Stopping " + alias + "...");
        SkillResumeService.clearAndNotify(bot.getUuid());
        return 1;
    }

    static int executeSkillTargets(CommandContext<ServerCommandSource> context, String skillName, String rawInput) throws CommandSyntaxException {
        skillName = normalizeSkillName(skillName);
        SkillCommandInvocation invocation = parseSkillInvocation(context.getSource(), rawInput);
        List<ServerPlayerEntity> targets;
        try {
            targets = BotTargetingService.resolve(context.getSource(), invocation.target());
        } catch (CommandSyntaxException e) {
            // If no explicit/remembered bot target exists for this sender, fall back to the "active bot"
            // selection that many non-broadcast commands use (keeps /bot skill usable without requiring an alias).
            if (invocation.target() == null) {
                ServerPlayerEntity active = getActiveBotOrThrow(context);
                BotTargetingService.remember(context.getSource(), active.getGameProfile().name());
                targets = List.of(active);
            } else {
                throw e;
            }
        }
        int successes = 0;
        String rawArgs = invocation.arguments();

        if (rawArgs == null || rawArgs.isBlank() || targets.size() <= 1) {
            for (ServerPlayerEntity bot : targets) {
                successes += executeSkill(context, bot, skillName, rawArgs);
            }
            return successes;
        }

        List<String> tokens = new ArrayList<>(Arrays.asList(rawArgs.trim().split("\\s+")));
        Integer totalCount = null;
        if (!tokens.isEmpty()) {
            try {
                totalCount = Integer.parseInt(tokens.get(0));
                tokens.remove(0);
            } catch (NumberFormatException ignored) {
            }
        }

        if (totalCount == null) {
            for (ServerPlayerEntity bot : targets) {
                successes += executeSkill(context, bot, skillName, rawArgs);
            }
            return successes;
        }

        String optionSuffix = tokens.isEmpty() ? "" : " " + String.join(" ", tokens);
        int botCount = targets.size();
        boolean eachMode = invocation.each();
        int base = eachMode ? totalCount : totalCount / botCount;
        int remainder = eachMode ? 0 : totalCount % botCount;

        for (int index = 0; index < targets.size(); index++) {
            int assigned = eachMode ? base : base + (index < remainder ? 1 : 0);
            if (!eachMode && assigned <= 0) {
                continue;
            }
            String perBotArgs = assigned + (optionSuffix.isEmpty() ? "" : optionSuffix);
            successes += executeSkill(context, targets.get(index), skillName, perBotArgs);
        }

        return successes;
    }

    private static String normalizeSkillName(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "woodcutting", "chopwood", "chop_wood", "chop-wood" -> "woodcut";
            case "woodcutcleanup", "woodcut-cleanup", "woodcut_cleanup", "cleanupwoodcut", "cleanup-woodcut", "tidywoodcut", "tidy-woodcut" -> "woodcut_cleanup";
            default -> normalized;
        };
    }

    private static String formatBotList(List<ServerPlayerEntity> bots, boolean isAll) {
        if (bots == null || bots.isEmpty()) {
            return "No bots";
        }
        if (isAll) {
            return "All bots";
        }
        List<String> names = bots.stream()
                .map(player -> player.getName().getString())
                .collect(Collectors.toCollection(ArrayList::new));
        if (names.size() == 1) {
            return names.get(0);
        }
        String last = names.remove(names.size() - 1);
        return String.join(", ", names) + " and " + last;
    }

    static int executeStopTargets(CommandContext<ServerCommandSource> context, String targetArg) throws CommandSyntaxException {
        List<ServerPlayerEntity> targets = resolveTargetBots(context, targetArg);
        boolean isAll = targetArg != null && "all".equalsIgnoreCase(targetArg.trim());
        return executeStopTargets(context, targets, isAll);
    }

    private static int executeStopTargets(CommandContext<ServerCommandSource> context, List<ServerPlayerEntity> targets) {
        return executeStopTargets(context, targets, false);
    }

    private static int executeStopTargets(CommandContext<ServerCommandSource> context, List<ServerPlayerEntity> targets, boolean isAll) {
        int successes = 0;
        for (ServerPlayerEntity bot : targets) {
            successes += executeStop(context, bot);
        }
        if (!targets.isEmpty()) {
            String summary = formatBotList(targets, isAll);
            String verb = (isAll || targets.size() > 1) ? "have" : "has";
            ChatUtils.sendSystemMessage(context.getSource(), summary + " " + verb + " stopped.");
        }
        return successes;
    }

    static int executeResumeTargets(CommandContext<ServerCommandSource> context, String targetArg) throws CommandSyntaxException {
        List<ServerPlayerEntity> bots = BotTargetingService.resolve(context.getSource(), targetArg);
        boolean isAll = targetArg != null && "all".equalsIgnoreCase(targetArg.trim());
        return executeResumeTargets(context, bots, isAll);
    }

    private static int executeResumeTargets(CommandContext<ServerCommandSource> context,
                                            List<ServerPlayerEntity> bots,
                                            boolean isAll) {
        int successes = 0;
        for (ServerPlayerEntity bot : bots) {
            successes += executeResume(context, bot);
        }
        if (successes > 0) {
            String summary = formatBotList(bots, isAll);
            String verb = (isAll || bots.size() > 1) ? "have" : "has";
            ChatUtils.sendSystemMessage(context.getSource(), summary + " " + verb + " been queued to resume.");
        }
        return successes;
    }

    private static int executeResume(CommandContext<ServerCommandSource> context, ServerPlayerEntity bot) {
        if (bot == null) {
            return 0;
        }
        boolean resumed = SkillResumeService.manualResume(context.getSource(), bot.getUuid());
        if (!resumed) {
            ChatUtils.sendSystemMessage(context.getSource(),
                    "No paused skill to resume for " + bot.getName().getString() + ".");
            return 0;
        }
        return 1;
    }

    static int executeHealTargets(CommandContext<ServerCommandSource> context, String targetArg) throws CommandSyntaxException {
        List<ServerPlayerEntity> bots = BotTargetingService.resolve(context.getSource(), targetArg);
        boolean isAll = targetArg != null && "all".equalsIgnoreCase(targetArg.trim());
        return executeHealTargets(context, bots, isAll);
    }

    private static int executeHealTargets(CommandContext<ServerCommandSource> context,
                                          List<ServerPlayerEntity> bots,
                                          boolean isAll) {
        int successes = 0;
        for (ServerPlayerEntity bot : bots) {
            if (HealingService.healBot(bot)) {
                successes++;
            }
        }
        if (successes == 0 && !bots.isEmpty()) {
            ChatUtils.sendSystemMessage(context.getSource(), "None of the targeted bots could eat.");
        }
        return successes;
    }

    static int executeSleepTargets(CommandContext<ServerCommandSource> context, String targetArg) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        List<ServerPlayerEntity> bots = resolveTargetBots(context, targetArg);
        boolean isAll = targetArg != null && "all".equalsIgnoreCase(targetArg.trim());

        int scheduled = 0;
        for (ServerPlayerEntity bot : bots) {
            if (bot == null) {
                continue;
            }
            UUID botUuid = bot.getUuid();
            var ticketOpt = TaskService.beginSkill("sleep", source, botUuid);
            if (ticketOpt.isEmpty()) {
                ChatUtils.sendSystemMessage(source, bot.getName().getString() + " is busy.");
                continue;
            }
            TaskService.TaskTicket ticket = ticketOpt.get();
            scheduled++;

            skillExecutor.submit(() -> {
                boolean success = false;
                try {
                    success = SleepService.sleep(source, bot) && !TaskService.isAbortRequested(botUuid);
                } catch (Exception e) {
                    LOGGER.error("Unexpected error in /bot sleep for {}", bot.getName().getString(), e);
                    source.getServer().execute(() ->
                            ChatUtils.sendSystemMessage(source, "An unexpected error occurred while trying to sleep."));
                } finally {
                    TaskService.complete(ticket, success);

                    // If idle hobbies are enabled, automatically resume after a short pause.
                    // (The resume helper will wait until the bot is no longer sleeping.)
                    try {
                        var srv = source.getServer();
                        srv.execute(() -> {
                            try {
                                net.wcfcarolina13.GameAI.services.BotIdleResumeService.scheduleResumeIfEnabled(
                                        srv,
                                        bot,
                                        400L,
                                        "sleep-command"
                                );
                            } catch (Throwable ignored) {
                            }
                        });
                    } catch (Throwable ignored) {
                    }
                }
            });
        }

        if (scheduled > 0) {
            String summary = formatBotList(bots, isAll);
            String verb = (isAll || bots.size() > 1) ? "are" : "is";
            ChatUtils.sendSystemMessage(source, summary + " " + verb + " trying to sleep.");
        }
        return scheduled;
    }

    static int executeAutoReturnSunsetSetTargets(CommandContext<ServerCommandSource> context,
                                                 String targetArg,
                                                 boolean enabled) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        List<ServerPlayerEntity> bots = resolveTargetBots(context, targetArg);
        boolean isAll = targetArg != null && "all".equalsIgnoreCase(targetArg.trim());

        int successes = 0;
        for (ServerPlayerEntity bot : bots) {
            if (bot == null) {
                continue;
            }
            if (BotHomeService.setAutoReturnAtSunset(bot, enabled)) {
                successes++;
            }
        }

        if (!bots.isEmpty()) {
            String summary = formatBotList(bots, isAll);
            ChatUtils.sendSystemMessage(source, summary + " auto-return at sunset " + (enabled ? "enabled" : "disabled") + ".");
        }
        return successes;
    }

    static int executeAutoReturnSunsetToggleTargets(CommandContext<ServerCommandSource> context,
                                                    String targetArg) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        List<ServerPlayerEntity> bots = resolveTargetBots(context, targetArg);
        boolean isAll = targetArg != null && "all".equalsIgnoreCase(targetArg.trim());

        int successes = 0;
        int enabledCount = 0;
        for (ServerPlayerEntity bot : bots) {
            if (bot == null) {
                continue;
            }
            if (!BotHomeService.toggleAutoReturnAtSunset(bot)) {
                continue;
            }
            successes++;
            if (BotHomeService.isAutoReturnAtSunset(bot)) {
                enabledCount++;
            }
        }

        if (!bots.isEmpty()) {
            String summary = formatBotList(bots, isAll);
            if (isAll || bots.size() > 1) {
                ChatUtils.sendSystemMessage(source, summary + " auto-return at sunset enabled for " + enabledCount + "/" + bots.size() + ".");
            } else if (bots.size() == 1) {
                boolean on = BotHomeService.isAutoReturnAtSunset(bots.getFirst());
                ChatUtils.sendSystemMessage(source, summary + " auto-return at sunset is now " + (on ? "ON" : "OFF") + ".");
            }
        }
        return successes;
    }

    static int executeAutoReturnSunsetGuardPatrolSetTargets(CommandContext<ServerCommandSource> context,
                                                            String targetArg,
                                                            boolean enabled) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        List<ServerPlayerEntity> bots = resolveTargetBots(context, targetArg);
        boolean isAll = targetArg != null && "all".equalsIgnoreCase(targetArg.trim());

        int successes = 0;
        for (ServerPlayerEntity bot : bots) {
            if (bot == null) {
                continue;
            }
            if (BotHomeService.setAutoReturnGuardPatrolEligible(bot, enabled)) {
                successes++;
            }
        }

        if (!bots.isEmpty()) {
            String summary = formatBotList(bots, isAll);
            ChatUtils.sendSystemMessage(source,
                    summary + " sunset auto-return eligibility for guard/patrol " + (enabled ? "enabled" : "disabled") + ".");
        }
        return successes;
    }

    static int executeAutoReturnSunsetGuardPatrolToggleTargets(CommandContext<ServerCommandSource> context,
                                                               String targetArg) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        List<ServerPlayerEntity> bots = resolveTargetBots(context, targetArg);
        boolean isAll = targetArg != null && "all".equalsIgnoreCase(targetArg.trim());

        int successes = 0;
        int enabledCount = 0;
        for (ServerPlayerEntity bot : bots) {
            if (bot == null) {
                continue;
            }
            if (!BotHomeService.toggleAutoReturnGuardPatrolEligible(bot)) {
                continue;
            }
            successes++;
            if (BotHomeService.isAutoReturnGuardPatrolEligible(bot)) {
                enabledCount++;
            }
        }

        if (!bots.isEmpty()) {
            String summary = formatBotList(bots, isAll);
            if (isAll || bots.size() > 1) {
                ChatUtils.sendSystemMessage(source,
                        summary + " guard/patrol eligibility enabled for " + enabledCount + "/" + bots.size() + ".");
            } else if (bots.size() == 1) {
                boolean on = BotHomeService.isAutoReturnGuardPatrolEligible(bots.getFirst());
                ChatUtils.sendSystemMessage(source,
                        summary + " guard/patrol eligibility is now " + (on ? "ON" : "OFF") + ".");
            }
        }
        return successes;
    }

    static int executeAutoReturnSunsetPreferLastBedSetTargets(CommandContext<ServerCommandSource> context,
                                                             String targetArg,
                                                             boolean enabled) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        List<ServerPlayerEntity> bots = resolveTargetBots(context, targetArg);
        boolean isAll = targetArg != null && "all".equalsIgnoreCase(targetArg.trim());

        int successes = 0;
        for (ServerPlayerEntity bot : bots) {
            if (bot == null) {
                continue;
            }
            if (BotHomeService.setAutoReturnPreferLastBedAtSunset(bot, enabled)) {
                successes++;
            }
        }

        if (!bots.isEmpty()) {
            String summary = formatBotList(bots, isAll);
            ChatUtils.sendSystemMessage(source,
                    summary + " sunset home preference set to " + (enabled ? "LAST_BED" : "DEFAULT") + ".");
        }
        return successes;
    }

    static int executeAutoReturnSunsetPreferLastBedToggleTargets(CommandContext<ServerCommandSource> context,
                                                                String targetArg) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        List<ServerPlayerEntity> bots = resolveTargetBots(context, targetArg);
        boolean isAll = targetArg != null && "all".equalsIgnoreCase(targetArg.trim());

        int successes = 0;
        int enabledCount = 0;
        for (ServerPlayerEntity bot : bots) {
            if (bot == null) {
                continue;
            }
            if (!BotHomeService.toggleAutoReturnPreferLastBedAtSunset(bot)) {
                continue;
            }
            successes++;
            if (BotHomeService.isAutoReturnPreferLastBedAtSunset(bot)) {
                enabledCount++;
            }
        }

        if (!bots.isEmpty()) {
            String summary = formatBotList(bots, isAll);
            if (isAll || bots.size() > 1) {
                ChatUtils.sendSystemMessage(source,
                        summary + " sunset home preference LAST_BED enabled for " + enabledCount + "/" + bots.size() + ".");
            } else if (bots.size() == 1) {
                boolean on = BotHomeService.isAutoReturnPreferLastBedAtSunset(bots.getFirst());
                ChatUtils.sendSystemMessage(source,
                        summary + " sunset home preference is now " + (on ? "LAST_BED" : "DEFAULT") + ".");
            }
        }
        return successes;
    }

    static int executeIdleHobbiesSetTargets(CommandContext<ServerCommandSource> context,
                                            String targetArg,
                                            boolean enabled) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        List<ServerPlayerEntity> bots = resolveTargetBots(context, targetArg);
        boolean isAll = targetArg != null && "all".equalsIgnoreCase(targetArg.trim());

        int successes = 0;
        for (ServerPlayerEntity bot : bots) {
            if (bot == null) {
                continue;
            }
            if (BotHomeService.setIdleHobbiesEnabled(bot, enabled)) {
                successes++;

                // If enabling, allow the scheduler to act immediately (useful after a recent command snoozed it).
                if (enabled) {
                    BotIdleHobbiesService.requestDecisionNow(bot);
                }
            }
        }

        if (!bots.isEmpty()) {
            String summary = formatBotList(bots, isAll);
            ChatUtils.sendSystemMessage(source,
                    summary + " idle hobbies " + (enabled ? "enabled" : "disabled") + ".");
        }
        return successes;
    }

    static int executeIdleHobbiesSetAndIdleTargets(CommandContext<ServerCommandSource> context,
                                                  String targetArg,
                                                  boolean enabled) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        List<ServerPlayerEntity> bots = resolveTargetBots(context, targetArg);
        boolean isAll = targetArg != null && "all".equalsIgnoreCase(targetArg.trim());

        int successes = 0;
        int idled = 0;
        for (ServerPlayerEntity bot : bots) {
            if (bot == null) {
                continue;
            }
            if (!BotHomeService.setIdleHobbiesEnabled(bot, enabled)) {
                continue;
            }
            successes++;

            if (!enabled) {
                continue;
            }

            // Don't override command/system tasks.
            try {
                var active = TaskService.getActiveTaskInfo(bot.getUuid());
                if (active.isPresent() && active.get().origin() != TaskService.Origin.AMBIENT) {
                    continue;
                }
                // If an ambient hobby is running, stop it so the bot can restart immediately.
                if (active.isPresent() && active.get().origin() == TaskService.Origin.AMBIENT) {
                    TaskService.forceAbort(bot.getUuid(), "§cInterrupted by /bot idle_hobbies on_and_idle.");
                }
            } catch (Throwable ignored) {
            }

            BotEventHandler.setIdleMode(bot, true);
            BotIdleHobbiesService.requestDecisionNow(bot);
            idled++;
        }

        if (!bots.isEmpty()) {
            String summary = formatBotList(bots, isAll);
            if (enabled) {
                ChatUtils.sendSystemMessage(source, summary + " idle hobbies enabled (and idling now) for " + idled + "/" + bots.size() + ".");
            } else {
                ChatUtils.sendSystemMessage(source, summary + " idle hobbies disabled.");
            }
        }
        return successes;
    }

    static int executeIdleHobbiesToggleTargets(CommandContext<ServerCommandSource> context,
                                               String targetArg) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        List<ServerPlayerEntity> bots = resolveTargetBots(context, targetArg);
        boolean isAll = targetArg != null && "all".equalsIgnoreCase(targetArg.trim());

        int successes = 0;
        int enabledCount = 0;
        for (ServerPlayerEntity bot : bots) {
            if (bot == null) {
                continue;
            }
            if (!BotHomeService.toggleIdleHobbiesEnabled(bot)) {
                continue;
            }
            successes++;
            if (BotHomeService.isIdleHobbiesEnabled(bot)) {
                enabledCount++;
                BotIdleHobbiesService.requestDecisionNow(bot);
            }
        }

        if (!bots.isEmpty()) {
            String summary = formatBotList(bots, isAll);
            if (isAll || bots.size() > 1) {
                ChatUtils.sendSystemMessage(source,
                        summary + " idle hobbies enabled for " + enabledCount + "/" + bots.size() + ".");
            } else if (bots.size() == 1) {
                boolean on = BotHomeService.isIdleHobbiesEnabled(bots.getFirst());
                ChatUtils.sendSystemMessage(source,
                        summary + " idle hobbies are now " + (on ? "ON" : "OFF") + ".");
            }
        }
        return successes;
    }

    static int executeIdleHobbiesToggleAndIdleTargets(CommandContext<ServerCommandSource> context,
                                                      String targetArg) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        List<ServerPlayerEntity> bots = resolveTargetBots(context, targetArg);
        boolean isAll = targetArg != null && "all".equalsIgnoreCase(targetArg.trim());

        int successes = 0;
        int enabledCount = 0;
        int idled = 0;

        for (ServerPlayerEntity bot : bots) {
            if (bot == null) {
                continue;
            }
            if (!BotHomeService.toggleIdleHobbiesEnabled(bot)) {
                continue;
            }
            successes++;

            boolean enabled = BotHomeService.isIdleHobbiesEnabled(bot);
            if (!enabled) {
                continue;
            }

            enabledCount++;

            // Don't override command/system tasks.
            try {
                var active = TaskService.getActiveTaskInfo(bot.getUuid());
                if (active.isPresent() && active.get().origin() != TaskService.Origin.AMBIENT) {
                    continue;
                }
                if (active.isPresent() && active.get().origin() == TaskService.Origin.AMBIENT) {
                    TaskService.forceAbort(bot.getUuid(), "§cInterrupted by /bot idle_hobbies toggle_and_idle.");
                }
            } catch (Throwable ignored) {
            }

            BotEventHandler.setIdleMode(bot, true);
            BotIdleHobbiesService.requestDecisionNow(bot);
            idled++;
        }

        if (!bots.isEmpty()) {
            String summary = formatBotList(bots, isAll);
            if (isAll || bots.size() > 1) {
                ChatUtils.sendSystemMessage(source,
                        summary + " idle hobbies enabled for " + enabledCount + "/" + bots.size() + "; idling now for " + idled + "/" + bots.size() + ".");
            } else if (bots.size() == 1) {
                boolean on = BotHomeService.isIdleHobbiesEnabled(bots.getFirst());
                ChatUtils.sendSystemMessage(source,
                        summary + " idle hobbies are now " + (on ? "ON (idling now)" : "OFF") + ".");
            }
        }

        return successes;
    }

    static int executeAutoHuntStarvingSetTargets(CommandContext<ServerCommandSource> context,
                                                 String targetArg,
                                                 boolean enabled) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        List<ServerPlayerEntity> bots = resolveTargetBots(context, targetArg);
        boolean isAll = targetArg != null && "all".equalsIgnoreCase(targetArg.trim());

        int successes = 0;
        for (ServerPlayerEntity bot : bots) {
            if (bot == null) {
                continue;
            }
            if (BotHomeService.setAutoHuntStarvingEnabled(bot, enabled)) {
                successes++;
                if (enabled) {
                    net.wcfcarolina13.GameAI.services.BotAutoHuntService.requestDecisionNow(bot);
                }
            }
        }

        if (!bots.isEmpty()) {
            String summary = formatBotList(bots, isAll);
            ChatUtils.sendSystemMessage(source,
                    summary + " auto-hunt (starving) " + (enabled ? "enabled" : "disabled") + ".");
        }
        return successes;
    }

    static int executeAutoHuntStarvingToggleTargets(CommandContext<ServerCommandSource> context,
                                                    String targetArg) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        List<ServerPlayerEntity> bots = resolveTargetBots(context, targetArg);
        boolean isAll = targetArg != null && "all".equalsIgnoreCase(targetArg.trim());

        int successes = 0;
        int enabledCount = 0;
        for (ServerPlayerEntity bot : bots) {
            if (bot == null) {
                continue;
            }
            if (!BotHomeService.toggleAutoHuntStarvingEnabled(bot)) {
                continue;
            }
            successes++;
            if (BotHomeService.isAutoHuntStarvingEnabled(bot)) {
                enabledCount++;
                net.wcfcarolina13.GameAI.services.BotAutoHuntService.requestDecisionNow(bot);
            }
        }

        if (!bots.isEmpty()) {
            String summary = formatBotList(bots, isAll);
            if (isAll || bots.size() > 1) {
                ChatUtils.sendSystemMessage(source,
                        summary + " auto-hunt (starving) enabled for " + enabledCount + "/" + bots.size() + ".");
            } else if (bots.size() == 1) {
                boolean on = BotHomeService.isAutoHuntStarvingEnabled(bots.getFirst());
                ChatUtils.sendSystemMessage(source,
                        summary + " auto-hunt (starving) is now " + (on ? "ON" : "OFF") + ".");
            }
        }
        return successes;
    }

    static int executeIdleNowTargets(CommandContext<ServerCommandSource> context,
                                    String targetArg) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        List<ServerPlayerEntity> bots = resolveTargetBots(context, targetArg);
        boolean isAll = targetArg != null && "all".equalsIgnoreCase(targetArg.trim());

        int successes = 0;
        for (ServerPlayerEntity bot : bots) {
            if (bot == null) {
                continue;
            }
            rememberTarget(source, bot);

            // If an ambient hobby is running, stop it (we're explicitly changing behavior).
            // If a command/system task is running, do not override it.
            try {
                TaskService.getActiveTaskInfo(bot.getUuid()).ifPresent(info -> {
                    if (info.origin() == TaskService.Origin.AMBIENT) {
                        TaskService.forceAbort(bot.getUuid(), "§cInterrupted by /bot idle_now.");
                    }
                });
                var active = TaskService.getActiveTaskInfo(bot.getUuid());
                if (active.isPresent() && active.get().origin() != TaskService.Origin.AMBIENT) {
                    if (bots.size() == 1) {
                        ChatUtils.sendSystemMessage(source, bot.getName().getString() + " is busy.");
                    } else {
                        ChatUtils.sendSystemMessage(source, bot.getName().getString() + " is busy; skipping.");
                    }
                    continue;
                }
            } catch (Throwable ignored) {
            }

            if (bots.size() == 1) {
                String result = BotEventHandler.setIdleMode(bot);
                ChatUtils.sendSystemMessage(source, result);
            } else {
                BotEventHandler.setIdleMode(bot, true);
            }

            // If idle hobbies are enabled, try to start one immediately.
            if (BotHomeService.isIdleHobbiesEnabled(bot)) {
                BotIdleHobbiesService.requestDecisionNow(bot);
            }

            successes++;
        }

        if (!bots.isEmpty() && (isAll || bots.size() > 1)) {
            String summary = formatBotList(bots, isAll);
            ChatUtils.sendSystemMessage(source, summary + " told to idle now (" + successes + "/" + bots.size() + ").");
        }
        return successes;
    }

    // ===== Unleash Tethered Mounts Toggle =====

    static int executeUnleashTetheredSetTargets(CommandContext<ServerCommandSource> context,
                                                String targetArg,
                                                boolean enabled) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        List<ServerPlayerEntity> bots = resolveTargetBots(context, targetArg);
        boolean isAll = targetArg != null && "all".equalsIgnoreCase(targetArg.trim());

        int successes = 0;
        for (ServerPlayerEntity bot : bots) {
            if (bot == null) {
                continue;
            }
            BotCommandStateService.State state = BotCommandStateService.stateFor(bot);
            if (state == null) {
                continue;
            }
            state.unleashTetheredMounts = enabled;
            successes++;
        }

        if (!bots.isEmpty()) {
            String summary = formatBotList(bots, isAll);
            ChatUtils.sendSystemMessage(source,
                    summary + " unleash-tethered " + (enabled ? "enabled" : "disabled") + ".");
        }
        return successes;
    }

    static int executeUnleashTetheredToggleTargets(CommandContext<ServerCommandSource> context,
                                                   String targetArg) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        List<ServerPlayerEntity> bots = resolveTargetBots(context, targetArg);
        boolean isAll = targetArg != null && "all".equalsIgnoreCase(targetArg.trim());

        int successes = 0;
        int enabledCount = 0;
        for (ServerPlayerEntity bot : bots) {
            if (bot == null) {
                continue;
            }
            BotCommandStateService.State state = BotCommandStateService.stateFor(bot);
            if (state == null) {
                continue;
            }
            state.unleashTetheredMounts = !state.unleashTetheredMounts;
            successes++;
            if (state.unleashTetheredMounts) {
                enabledCount++;
            }
        }

        if (!bots.isEmpty()) {
            String summary = formatBotList(bots, isAll);
            if (isAll || bots.size() > 1) {
                ChatUtils.sendSystemMessage(source,
                        summary + " unleash-tethered enabled for " + enabledCount + "/" + bots.size() + ".");
            } else if (bots.size() == 1) {
                BotCommandStateService.State state = BotCommandStateService.stateFor(bots.getFirst());
                boolean on = state != null && state.unleashTetheredMounts;
                ChatUtils.sendSystemMessage(source,
                        summary + " unleash-tethered is now " + (on ? "ON" : "OFF") + ".");
            }
        }
        return successes;
    }

    // ===== Leash On Dismount Toggle =====

    static int executeLeashOnDismountSetTargets(CommandContext<ServerCommandSource> context,
                                                String targetArg,
                                                boolean enabled) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        List<ServerPlayerEntity> bots = resolveTargetBots(context, targetArg);
        boolean isAll = targetArg != null && "all".equalsIgnoreCase(targetArg.trim());

        int successes = 0;
        for (ServerPlayerEntity bot : bots) {
            if (bot == null) {
                continue;
            }
            BotCommandStateService.State state = BotCommandStateService.stateFor(bot);
            if (state == null) {
                continue;
            }
            state.leashMountsOnDismount = enabled;
            successes++;
        }

        if (!bots.isEmpty()) {
            String summary = formatBotList(bots, isAll);
            ChatUtils.sendSystemMessage(source,
                    summary + " leash-on-dismount " + (enabled ? "enabled" : "disabled") + ".");
        }
        return successes;
    }

    static int executeLeashOnDismountToggleTargets(CommandContext<ServerCommandSource> context,
                                                   String targetArg) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        List<ServerPlayerEntity> bots = resolveTargetBots(context, targetArg);
        boolean isAll = targetArg != null && "all".equalsIgnoreCase(targetArg.trim());

        int successes = 0;
        int enabledCount = 0;
        for (ServerPlayerEntity bot : bots) {
            if (bot == null) {
                continue;
            }
            BotCommandStateService.State state = BotCommandStateService.stateFor(bot);
            if (state == null) {
                continue;
            }
            state.leashMountsOnDismount = !state.leashMountsOnDismount;
            successes++;
            if (state.leashMountsOnDismount) {
                enabledCount++;
            }
        }

        if (!bots.isEmpty()) {
            String summary = formatBotList(bots, isAll);
            if (isAll || bots.size() > 1) {
                ChatUtils.sendSystemMessage(source,
                        summary + " leash-on-dismount enabled for " + enabledCount + "/" + bots.size() + ".");
            } else if (bots.size() == 1) {
                BotCommandStateService.State state = BotCommandStateService.stateFor(bots.getFirst());
                boolean on = state != null && state.leashMountsOnDismount;
                ChatUtils.sendSystemMessage(source,
                        summary + " leash-on-dismount is now " + (on ? "ON" : "OFF") + ".");
            }
        }
        return successes;
    }

    static int executeBaseSet(CommandContext<ServerCommandSource> context, String label) throws CommandSyntaxException {
        if (label == null || label.isBlank()) {
            throw new SimpleCommandExceptionType(Text.literal("Provide a base label."))
                    .create();
        }
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity commander = source.getPlayer();
        if (commander == null) {
            throw new SimpleCommandExceptionType(Text.literal("Only players can set a base location."))
                    .create();
        }
        ServerWorld world = source.getWorld();
        if (world.getRegistryKey() != World.OVERWORLD) {
            ChatUtils.sendSystemMessage(source, "Bases can only be saved in the Overworld.");
            return 0;
        }
        boolean ok = BotHomeService.addBase(source.getServer(), world, label, commander.getBlockPos().toImmutable());
        if (ok) {
            ChatUtils.sendSystemMessage(source, "Saved base '" + label + "' at " + commander.getBlockPos().toShortString() + ".");
            return 1;
        }
        ChatUtils.sendSystemMessage(source, "Failed to save base.");
        return 0;
    }

    static int executeBaseRemove(CommandContext<ServerCommandSource> context, String label) {
        if (label == null || label.isBlank()) {
            ChatUtils.sendSystemMessage(context.getSource(), "Provide a base label to remove.");
            return 0;
        }
        ServerCommandSource source = context.getSource();
        ServerWorld world = source.getWorld();
        if (world.getRegistryKey() != World.OVERWORLD) {
            ChatUtils.sendSystemMessage(source, "Bases are only managed in the Overworld.");
            return 0;
        }
        boolean removed = BotHomeService.removeBase(source.getServer(), world, label);
        ChatUtils.sendSystemMessage(source, removed ? "Removed base '" + label + "'." : "No base named '" + label + "' found.");
        return removed ? 1 : 0;
    }

    static int executeBaseRename(CommandContext<ServerCommandSource> context, String oldLabel, String newLabel) {
        if (oldLabel == null || oldLabel.isBlank() || newLabel == null || newLabel.isBlank()) {
            ChatUtils.sendSystemMessage(context.getSource(), "Usage: /bot base rename <old_label> <new_label>");
            return 0;
        }
        ServerCommandSource source = context.getSource();
        ServerWorld world = source.getWorld();
        if (world.getRegistryKey() != World.OVERWORLD) {
            ChatUtils.sendSystemMessage(source, "Bases are only managed in the Overworld.");
            return 0;
        }

        boolean ok = BotHomeService.renameBase(source.getServer(), world, oldLabel, newLabel);
        if (ok) {
            ChatUtils.sendSystemMessage(source, "Renamed base '" + oldLabel + "' -> '" + newLabel + "'.");
            return 1;
        }
        ChatUtils.sendSystemMessage(source, "Rename failed (does the old base exist? is the new name already used?).");
        return 0;
    }

    static int executeBaseList(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        ServerWorld world = source.getWorld();
        if (world.getRegistryKey() != World.OVERWORLD) {
            ChatUtils.sendSystemMessage(source, "Bases are only managed in the Overworld.");
            return 0;
        }

        List<BotHomeService.BaseEntry> bases = BotHomeService.listBases(source.getServer(), world);
        List<String> lines = new ArrayList<>();
        for (BotHomeService.BaseEntry base : bases) {
            if (base == null || base.pos() == null) {
                continue;
            }
            lines.add(base.label() + " @ " + base.pos().toShortString());
        }
        sendPaged(source, "Saved bases:", lines);
        return bases.size();
    }

    static int executeDirectionReset(CommandContext<ServerCommandSource> context, String targetArg) throws CommandSyntaxException {
        List<ServerPlayerEntity> targets = BotTargetingService.resolve(context.getSource(), targetArg);
        return executeDirectionReset(context, targets, "allbots".equalsIgnoreCase(targetArg));
    }

    private static int executeDirectionReset(CommandContext<ServerCommandSource> context,
                                             List<ServerPlayerEntity> bots,
                                             boolean isAll) {
        int successes = 0;
        for (ServerPlayerEntity bot : bots) {
            if (bot == null) {
                continue;
            }
            boolean wasReset = WorkDirectionService.resetDirection(bot.getUuid());
            if (wasReset) {
                successes++;
            }
        }
        if (successes > 0) {
            String summary = formatBotList(bots, isAll);
            String verb = (isAll || bots.size() > 1) ? "have" : "has";
            ChatUtils.sendSystemMessage(context.getSource(), 
                    summary + " " + verb + " had work direction reset. Next job will use current facing.");
        } else if (!bots.isEmpty()) {
            String summary = formatBotList(bots, isAll);
            String verb = (isAll || bots.size() > 1) ? "have" : "has";
            ChatUtils.sendSystemMessage(context.getSource(),
                    summary + " " + verb + " no stored work direction.");
        }
        return successes;
    }

    static int executeLookPlayerTargets(CommandContext<ServerCommandSource> context,
                                                String targetArg,
                                                boolean stop) throws CommandSyntaxException {
        List<ServerPlayerEntity> bots = resolveTargetBots(context, targetArg);
        boolean isAll = targetArg != null && "all".equalsIgnoreCase(targetArg == null ? "" : targetArg.trim());
        ServerPlayerEntity viewer = stop ? null : context.getSource().getPlayer();
        if (!stop && viewer == null) {
            throw new SimpleCommandExceptionType(Text.literal("Only players can use /bot look_player.")).create();
        }
        int successes = 0;
        for (ServerPlayerEntity bot : bots) {
            successes += executeLookPlayer(context, bot, viewer, stop);
        }
        if (!bots.isEmpty()) {
            String summary = formatBotList(bots, isAll);
            if (stop) {
                ChatUtils.sendSystemMessage(context.getSource(), summary + " stopped watching you.");
            } else if (viewer != null) {
                ChatUtils.sendSystemMessage(context.getSource(), summary + " now looking at " + viewer.getName().getString() + ".");
            }
        }
        return successes;
    }

    private static int executeLookPlayer(CommandContext<ServerCommandSource> context,
                                         ServerPlayerEntity bot,
                                         ServerPlayerEntity viewer,
                                         boolean stop) {
        if (bot == null) {
            return 0;
        }
        if (stop) {
            LookController.faceBlock(bot, bot.getBlockPos().offset(bot.getHorizontalFacing()));
            return 1;
        }
        if (viewer == null) {
            return 0;
        }
        LookController.faceEntity(bot, viewer);
        return 1;
    }

    private static List<ServerPlayerEntity> resolveTargetBots(CommandContext<ServerCommandSource> context, String targetArg) throws CommandSyntaxException {
        if (targetArg == null) {
            return List.of(getActiveBotOrThrow(context));
        }
        return BotTargetingService.resolve(context.getSource(), targetArg);
    }

    static int executeFollowTargets(CommandContext<ServerCommandSource> context, String targetArg, ServerPlayerEntity followTarget) throws CommandSyntaxException {
        if (followTarget == null) {
            throw new SimpleCommandExceptionType(Text.literal("Specify a player for the bots to follow.")).create();
        }
        List<ServerPlayerEntity> bots = BotTargetingService.resolve(context.getSource(), targetArg);
        boolean isAll = targetArg != null && "all".equalsIgnoreCase(targetArg.trim());
        return executeFollowTargets(context, bots, followTarget, isAll);
    }

    private static int executeFollowTargets(CommandContext<ServerCommandSource> context, List<ServerPlayerEntity> bots, ServerPlayerEntity followTarget, boolean isAll) {
        int successes = 0;
        for (ServerPlayerEntity bot : bots) {
            successes += executeFollow(context, bot, followTarget);
        }
        if (!bots.isEmpty()) {
            String summary = formatBotList(bots, isAll);
            boolean plural = isAll || bots.size() > 1;
            String verb = plural ? "are" : "is";
            ChatUtils.sendSystemMessage(context.getSource(), summary + " " + verb + " following " + followTarget.getName().getString() + ".");
        }
        return successes;
    }

    static int executeFollowStopTargets(CommandContext<ServerCommandSource> context, String targetArg) throws CommandSyntaxException {
        List<ServerPlayerEntity> bots = BotTargetingService.resolve(context.getSource(), targetArg);
        return executeFollowStopTargets(context, bots, targetArg != null && "all".equalsIgnoreCase(targetArg.trim()));
    }

    private static int executeFollowStopTargets(CommandContext<ServerCommandSource> context, List<ServerPlayerEntity> bots, boolean isAll) {
        int successes = 0;
        for (ServerPlayerEntity bot : bots) {
            successes += executeFollowStop(context, bot);
        }
        if (!bots.isEmpty()) {
            String summary = formatBotList(bots, isAll);
            String verb = (isAll || bots.size() > 1) ? "have" : "has";
            ChatUtils.sendSystemMessage(context.getSource(), summary + " " + verb + " stopped following.");
        }
        return successes;
    }

    static int executeFollowToggleTargets(CommandContext<ServerCommandSource> context,
                                          String targetArg,
                                          ServerPlayerEntity followTarget) throws CommandSyntaxException {
        if (followTarget == null) {
            throw new SimpleCommandExceptionType(Text.literal("Specify a player for the bots to follow.")).create();
        }
        List<ServerPlayerEntity> bots = BotTargetingService.resolve(context.getSource(), targetArg);
        boolean isAll = targetArg != null && "all".equalsIgnoreCase(targetArg.trim());

        int successes = 0;
        int turnedOn = 0;
        for (ServerPlayerEntity bot : bots) {
            if (bot == null) {
                continue;
            }
            interruptAmbientHobbyIfAny(bot, "§cInterrupted by /bot follow toggle.");
            boolean isFollowingTarget = BotEventHandler.getCurrentMode(bot) == BotEventHandler.Mode.FOLLOW
                    && followTarget.getUuid().equals(BotEventHandler.getFollowTargetUuid(bot));
            if (isFollowingTarget) {
                BotEventHandler.stopFollowing(bot, false);
            } else {
                BotEventHandler.setFollowMode(bot, followTarget, false);
                turnedOn++;
            }
            successes++;
        }

        if (!bots.isEmpty()) {
            String summary = formatBotList(bots, isAll);
            if (isAll || bots.size() > 1) {
                ChatUtils.sendSystemMessage(context.getSource(), summary + " now following " + followTarget.getName().getString()
                        + " for " + turnedOn + "/" + bots.size() + " (others stopped)." );
            } else {
                boolean on = turnedOn > 0;
                ChatUtils.sendSystemMessage(context.getSource(), summary + " follow is now " + (on ? "ON" : "OFF") + ".");
            }
        }
        return successes;
    }

    private static int executeInlineBotCommand(CommandContext<ServerCommandSource> context, String rawInput) throws CommandSyntaxException {
        if (rawInput == null || rawInput.isBlank()) {
            throw new SimpleCommandExceptionType(Text.literal("Provide a command for the bots to run.")).create();
        }

        String normalized = rawInput.replace(",", " ").replaceAll("(?i)\\band\\b", " ").trim();
        if (normalized.isEmpty()) {
            throw new SimpleCommandExceptionType(Text.literal("Provide a command for the bots to run.")).create();
        }

        List<String> tokens = Arrays.stream(normalized.split("\\s+"))
                .filter(token -> !token.isEmpty())
                .collect(Collectors.toCollection(ArrayList::new));
        if (tokens.isEmpty()) {
            throw new SimpleCommandExceptionType(Text.literal("Provide a command for the bots to run.")).create();
        }

        List<String> lowerTokens = tokens.stream()
                .map(token -> token.toLowerCase(Locale.ROOT))
                .collect(Collectors.toList());

        int actionIndex = -1;
        for (int index = 0; index < lowerTokens.size(); index++) {
            String token = lowerTokens.get(index);
            if ("follow".equals(token) || "stop".equals(token)) {
                actionIndex = index;
                break;
            }
        }

        if (actionIndex == -1) {
            throw new SimpleCommandExceptionType(Text.literal("Unsupported inline syntax. Try explicit subcommands like /bot follow <bot>.")).create();
        }

        String action = lowerTokens.get(actionIndex);
        List<String> prefixTokens = new ArrayList<>(tokens.subList(0, actionIndex));
        List<String> suffixTokens = actionIndex + 1 < tokens.size()
                ? new ArrayList<>(tokens.subList(actionIndex + 1, tokens.size()))
                : new ArrayList<>();

        if ("follow".equals(action)) {
            return handleInlineFollow(context, prefixTokens, suffixTokens);
        }
        if ("stop".equals(action)) {
            return handleInlineStop(context, prefixTokens, suffixTokens);
        }

        throw new SimpleCommandExceptionType(Text.literal("Unsupported inline action '" + action + "'.")).create();
    }

    private static int handleInlineFollow(CommandContext<ServerCommandSource> context,
                                          List<String> prefixTokens,
                                          List<String> suffixTokens) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        MinecraftServer server = source.getServer();

        List<String> prefixAliases = parseAliasTokens(prefixTokens);
        List<String> workingSuffix = new ArrayList<>(suffixTokens);

        if (prefixAliases.isEmpty() && !workingSuffix.isEmpty() && "stop".equalsIgnoreCase(workingSuffix.get(0))) {
            workingSuffix.remove(0);
            List<ServerPlayerEntity> bots = BotTargetingService.resolve(source, null);
            return executeFollowStopTargets(context, bots, false);
        }

        List<String> aliasSelection = new ArrayList<>(prefixAliases);

        if (aliasSelection.isEmpty() && shouldTreatAsAlias(server, workingSuffix)) {
            aliasSelection.addAll(parseAliasTokens(workingSuffix));
            workingSuffix.clear();
        }

        boolean isAll = containsAllAlias(aliasSelection);

        List<ServerPlayerEntity> bots;
        if (aliasSelection.isEmpty()) {
            bots = BotTargetingService.resolve(source, null);
        } else {
            bots = BotTargetingService.resolveMany(source, aliasSelection);
        }

        if (!workingSuffix.isEmpty() && "stop".equalsIgnoreCase(workingSuffix.get(0))) {
            workingSuffix.remove(0);
            return executeFollowStopTargets(context, bots, isAll);
        }

        ServerPlayerEntity followTarget;
        if (!prefixAliases.isEmpty()) {
            followTarget = resolveFollowTarget(source, workingSuffix);
        } else if (!aliasSelection.isEmpty()) {
            followTarget = source.getPlayer();
        } else {
            followTarget = resolveFollowTarget(source, workingSuffix);
        }

        return executeFollowTargets(context, bots, followTarget, isAll);
    }

    private static int handleInlineStop(CommandContext<ServerCommandSource> context,
                                        List<String> prefixTokens,
                                        List<String> suffixTokens) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        MinecraftServer server = source.getServer();

        List<String> aliasSelection = parseAliasTokens(prefixTokens);
        List<String> workingSuffix = new ArrayList<>(suffixTokens);

        if (aliasSelection.isEmpty() && shouldTreatAsAlias(server, workingSuffix)) {
            aliasSelection.addAll(parseAliasTokens(workingSuffix));
            workingSuffix.clear();
        }

        boolean isAll = containsAllAlias(aliasSelection);

        List<ServerPlayerEntity> bots;
        if (aliasSelection.isEmpty()) {
            bots = BotTargetingService.resolve(source, null);
        } else {
            bots = BotTargetingService.resolveMany(source, aliasSelection);
        }

        return executeStopTargets(context, bots, isAll);
    }

    private static boolean shouldTreatAsAlias(MinecraftServer server, List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return false;
        }
        if (tokens.size() > 1) {
            return true;
        }
        String token = tokens.get(0);
        if ("all".equalsIgnoreCase(token)) {
            return true;
        }
        return server != null && BotTargetingService.isKnownTarget(server, token);
    }

    private static List<String> parseAliasTokens(List<String> tokens) {
        List<String> aliases = new ArrayList<>();
        if (tokens == null || tokens.isEmpty()) {
            return aliases;
        }
        for (String token : tokens) {
            if (token == null) {
                continue;
            }
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            aliases.add(trimmed);
        }
        return aliases;
    }

    private static boolean containsAllAlias(List<String> aliases) {
        if (aliases == null) {
            return false;
        }
        for (String alias : aliases) {
            if ("all".equalsIgnoreCase(alias)) {
                return true;
            }
        }
        return false;
    }

    private static ServerPlayerEntity resolveFollowTarget(ServerCommandSource source, List<String> tokens) throws CommandSyntaxException {
        if (tokens == null || tokens.isEmpty()) {
            return source.getPlayer();
        }
        String descriptor = tokens.get(0);
        if (descriptor == null || descriptor.isBlank()) {
            return source.getPlayer();
        }
        String normalized = descriptor.trim();
        if (normalized.equalsIgnoreCase("me") || normalized.equalsIgnoreCase("self") || normalized.equalsIgnoreCase("you") || normalized.equalsIgnoreCase("player")) {
            return source.getPlayer();
        }

        MinecraftServer server = source.getServer();
        if (server == null) {
            throw new SimpleCommandExceptionType(Text.literal("Server context unavailable; cannot resolve follow target.")).create();
        }

        ServerPlayerEntity direct = server.getPlayerManager().getPlayer(normalized);
        if (direct != null) {
            return direct;
        }

        try {
            List<ServerPlayerEntity> bots = BotTargetingService.resolve(source, normalized);
            if (bots.size() == 1) {
                return bots.get(0);
            }
        } catch (CommandSyntaxException ignored) {
        }

        throw new SimpleCommandExceptionType(Text.literal("Could not find follow target '" + normalized + "'.")).create();
    }

    private static SkillCommandInvocation parseSkillInvocation(ServerCommandSource source, String rawInput) throws CommandSyntaxException {
        if (rawInput == null || rawInput.isBlank()) {
            return new SkillCommandInvocation(null, null, false);
        }
        MinecraftServer server = source.getServer();
        if (server == null) {
            throw new SimpleCommandExceptionType(Text.literal("Command source is not attached to an active server.")).create();
        }
        List<String> tokens = new ArrayList<>(Arrays.asList(rawInput.trim().split("\\s+")));
        boolean each = false;
        for (Iterator<String> iterator = tokens.iterator(); iterator.hasNext(); ) {
            String token = iterator.next();
            if ("each".equalsIgnoreCase(token)) {
                each = true;
                iterator.remove();
            }
        }
        String target = null;
        if (!tokens.isEmpty()) {
            // Allow the target to appear anywhere in the skill argument list (e.g., "ascend Jake 5").
            for (int i = tokens.size() - 1; i >= 0; i--) {
                String token = tokens.get(i);
                if (BotTargetingService.isKnownTarget(server, token)) {
                    target = tokens.remove(i);
                    break;
                }
            }
        }
        String arguments = tokens.isEmpty() ? null : String.join(" ", tokens);
        return new SkillCommandInvocation(target, arguments, each);
    }

    private static int executeSkill(CommandContext<ServerCommandSource> context, ServerPlayerEntity bot, String skillName, String rawArgs) {
        Map<String, Object> params = new HashMap<>();
        Integer count = null;
        Integer ascentBlocks = null;
        Integer ascentTargetY = null;
        boolean ascentToSurface = false;
        Integer descentBlocks = null;
        Integer descentTargetY = null;
        Set<Identifier> targetBlocks = new HashSet<>();
        List<String> options = new ArrayList<>();
        
        if (rawArgs != null && !rawArgs.isBlank()) {
            String[] tokens = rawArgs.trim().split("\\s+");
            for (int i = 0; i < tokens.length; i++) {
                String token = tokens[i];
                
                // Check for ascent (relative: climb UP by N blocks)
                if ("ascent".equalsIgnoreCase(token) || "ascend".equalsIgnoreCase(token)) {
                    // Support both "ascend 5" and "ascend" (default 5 blocks).
                    if (i + 1 < tokens.length) {
                        String numStr = tokens[i + 1];
                        if ("surface".equalsIgnoreCase(numStr)) {
                            ascentToSurface = true;
                            i++;
                            LOGGER.info("Parsed ascent: climb until unobstructed sky (surface mode)");
                            continue;
                        }
                        try {
                            ascentBlocks = Math.abs(Integer.parseInt(numStr)); // Always positive
                            i++;
                            LOGGER.info("Parsed ascent: climb UP by {} blocks", ascentBlocks);
                            continue;
                        } catch (NumberFormatException ignored) {
                            // Fall through to default below if the next token isn't a number.
                        }
                    }
                    ascentBlocks = 5;
                    LOGGER.info("Parsed ascent: climb UP by {} blocks (default)", ascentBlocks);
                    continue;
                }
                
                // Check for ascent-y (absolute: climb UP to Y=N)
                if ("ascent-y".equalsIgnoreCase(token) && i + 1 < tokens.length) {
                    String numStr = tokens[++i];
                    try {
                        ascentTargetY = Integer.parseInt(numStr);
                        LOGGER.info("Parsed ascent-y: climb UP to Y={}", ascentTargetY);
                        continue;
                    } catch (NumberFormatException ignored) {
                        LOGGER.warn("Invalid ascent-y parameter '{}'", numStr);
                        continue;
                    }
                }
                
                // Check for descent (relative: dig DOWN by N blocks)
                if ("descent".equalsIgnoreCase(token) || "descend".equalsIgnoreCase(token)) {
                    if (i + 1 >= tokens.length) {
                        descentBlocks = 5;
                        LOGGER.info("Parsed descent: dig DOWN by {} blocks (default)", descentBlocks);
                        continue;
                    }
                    String numStr = tokens[++i];
                    try {
                        descentBlocks = Math.abs(Integer.parseInt(numStr)); // Always positive
                        LOGGER.info("Parsed descent: dig DOWN by {} blocks", descentBlocks);
                        continue;
                    } catch (NumberFormatException ignored) {
                        LOGGER.warn("Invalid descent parameter '{}'", numStr);
                        continue;
                    }
                }
                
                // Check for descent-y (absolute: dig DOWN to Y=N)
                if ("descent-y".equalsIgnoreCase(token) && i + 1 < tokens.length) {
                    String numStr = tokens[++i];
                    try {
                        descentTargetY = Integer.parseInt(numStr);
                        LOGGER.info("Parsed descent-y: dig DOWN to Y={}", descentTargetY);
                        continue;
                    } catch (NumberFormatException ignored) {
                        LOGGER.warn("Invalid descent-y parameter '{}'", numStr);
                        continue;
                    }
                }
                
                // Check for key=value parameter pairs (e.g., targetX=-557)
                if (token.contains("=")) {
                    int eqIdx = token.indexOf('=');
                    String key = token.substring(0, eqIdx);
                    String value = token.substring(eqIdx + 1);
                    if (!key.isEmpty() && !value.isEmpty()) {
                        // Try to parse as integer first
                        try {
                            int intVal = Integer.parseInt(value);
                            params.put(key, intVal);
                            LOGGER.info("Parsed option: {}={}", key, intVal);
                        } catch (NumberFormatException e) {
                            // Try as double
                            try {
                                double doubleVal = Double.parseDouble(value);
                                params.put(key, doubleVal);
                                LOGGER.info("Parsed option: {}={}", key, doubleVal);
                            } catch (NumberFormatException e2) {
                                // Store as string
                                params.put(key, value);
                                LOGGER.info("Parsed option: {}={}", key, value);
                            }
                        }
                        continue;
                    }
                }
                
                try {
                    count = Integer.parseInt(token);
                    LOGGER.info("Parsed count: " + count);
                    continue;
                } catch (NumberFormatException ignored) {
                }

                Identifier id = Identifier.tryParse(token);
                if (id != null && Registries.BLOCK.containsId(id)) {
                    targetBlocks.add(id);
                    LOGGER.info("Parsed target block (direct ID): " + id);
                } else {
                    id = Identifier.tryParse("minecraft:" + token);
                    if (id != null && Registries.BLOCK.containsId(id)) {
                        targetBlocks.add(id);
                        LOGGER.info("Parsed target block (minecraft: prefix): " + id);
                    } else {
                        options.add(token.toLowerCase(Locale.ROOT));
                        LOGGER.info("Parsed option: " + token.toLowerCase(Locale.ROOT));
                    }
                }
            }

            if (count != null) {
                params.put("count", count);
            }
            if (ascentBlocks != null) {
                params.put("ascentBlocks", ascentBlocks);
            }
            if (ascentTargetY != null) {
                params.put("ascentTargetY", ascentTargetY);
            }
            if (ascentToSurface) {
                params.put("ascentToSurface", true);
            }
            if (descentBlocks != null) {
                params.put("descentBlocks", descentBlocks);
            }
            if (descentTargetY != null) {
                params.put("descentTargetY", descentTargetY);
            }
            if (!targetBlocks.isEmpty()) {
                params.put("targetBlocks", targetBlocks);
            }
            if (!options.isEmpty()) {
                params.put("options", options);
            }
        }

        ServerCommandSource source = context.getSource();
        UUID botUuid = bot.getUuid();

        if (TaskService.interruptAmbientTask(botUuid, "§cInterrupted by a command.")) {
            LOGGER.info("Interrupted ambient task before starting '{}' for bot {}", skillName, bot.getGameProfile().name());
        }
        
        // Record skill execution for resume capability
        SkillResumeService.recordExecution(bot, skillName, rawArgs, source);
        
        // Capture command issuer's look direction (raycast) for directional skills.
        // IMPORTANT: Skip on resume so we don't overwrite stored/locked work directions.
        boolean isResuming = SkillResumeService.hasResumeIntent(botUuid);
        if (!isResuming && source.getPlayer() != null) {
            ServerPlayerEntity commander = source.getPlayer();
            Direction lookDir = captureLookDirection(commander, 24.0D);
            params.put("direction", lookDir);
            params.put("issuerFacing", lookDir.asString());
            params.put("issuerYaw", commander.getYaw());
        }

        ServerCommandSource botSource = bot.getCommandSource();
        Map<String, Object> sharedState = safeSharedState();
        net.wcfcarolina13.GameAI.services.DebugFileLogger.log("Command.runSkill prepared name="
                + skillName + " bot=" + bot.getGameProfile().name()
                + " thread=" + Thread.currentThread().getName());

        LOGGER.info("Queueing skill '{}' for bot {} with args '{}'", skillName, bot.getGameProfile().name(), rawArgs);
        try {
            skillExecutor.submit(() -> {
                LOGGER.info("Running skill '{}' for bot {}", skillName, bot.getGameProfile().name());
                net.wcfcarolina13.GameAI.services.DebugFileLogger.log("Command.runSkill start name="
                        + skillName + " bot=" + bot.getGameProfile().name()
                        + " thread=" + Thread.currentThread().getName());
                try {
                    net.wcfcarolina13.GameAI.services.DebugFileLogger.log("Command.runSkill preContext name="
                            + skillName + " bot=" + bot.getGameProfile().name());
                    SkillContext skillContext = new SkillContext(botSource, sharedState, params, source);
                    net.wcfcarolina13.GameAI.services.DebugFileLogger.log("Command.runSkill postContext name="
                            + skillName + " bot=" + bot.getGameProfile().name());
                    net.wcfcarolina13.GameAI.services.DebugFileLogger.log("Command.runSkill preRunSkill name="
                            + skillName + " bot=" + bot.getGameProfile().name());
                    SkillExecutionResult result = SkillManager.runSkill(skillName, skillContext);
                    LOGGER.info("Skill '{}' finished for bot {}: success={} msg='{}'",
                            skillName,
                            bot.getGameProfile().name(),
                            result != null && result.success(),
                            result != null ? result.message() : "null");
                    net.wcfcarolina13.GameAI.services.DebugFileLogger.log("Command.runSkill end name="
                            + skillName + " bot=" + bot.getGameProfile().name()
                            + " success=" + (result != null && result.success())
                            + " msg=" + (result != null ? result.message() : "null"));
                    source.getServer().execute(() -> ChatUtils.sendSystemMessage(source, result.message()));
                } catch (Exception e) {
                    LOGGER.error("An unexpected error occurred in /bot skill " + skillName, e);
                    net.wcfcarolina13.GameAI.services.DebugFileLogger.log("Command.runSkill error name="
                            + skillName + " bot=" + bot.getGameProfile().name()
                            + " err=" + e.getClass().getSimpleName());
                    source.getServer().execute(() -> ChatUtils.sendSystemMessage(source, "An unexpected error occurred trying to execute that command."));
                }
            });
        } catch (RuntimeException e) {
            LOGGER.error("Failed to queue skill '{}' for bot {} (fallback to direct run)", skillName, bot.getGameProfile().name(), e);
            SkillContext skillContext = new SkillContext(bot.getCommandSource(), FunctionCallerV2.getSharedState(), params, source);
            SkillExecutionResult result = SkillManager.runSkill(skillName, skillContext);
            source.getServer().execute(() -> ChatUtils.sendSystemMessage(source, result.message()));
        }

        return 1;
    }

    private static int executeTeleportConfig(CommandContext<ServerCommandSource> context,
                                             ServerPlayerEntity bot,
                                             boolean enabled) {
        if (bot == null) {
            ChatUtils.sendSystemMessage(context.getSource(), "No active bot found. Spawn one with /bot spawn.");
            return 0;
        }
        SkillPreferences.setTeleportDuringSkills(bot.getUuid(), enabled);
        String state = enabled ? "enabled" : "disabled";
        ChatUtils.sendSystemMessage(context.getSource(),
                "Teleport during skill tasks " + state + " for " + bot.getName().getString() + ".");
        return 1;
    }

    private static int executeTeleportConfigTargets(CommandContext<ServerCommandSource> context,
                                                    String targetArg,
                                                    boolean enabled) throws CommandSyntaxException {
        List<ServerPlayerEntity> bots = resolveTargetBots(context, targetArg);
        boolean isAll = targetArg != null && "all".equalsIgnoreCase(targetArg.trim());
        int successes = 0;
        for (ServerPlayerEntity bot : bots) {
            successes += executeTeleportConfig(context, bot, enabled);
        }
        if (!bots.isEmpty()) {
            String summary = formatBotList(bots, isAll);
            ChatUtils.sendSystemMessage(context.getSource(),
                    summary + " now " + (enabled ? "teleport" : "walk") + " during skill tasks.");
        }
        return successes;
    }

    private static int executeInventoryFullConfig(CommandContext<ServerCommandSource> context,
                                                  ServerPlayerEntity bot,
                                                  boolean enabled) {
        if (bot == null) {
            ChatUtils.sendSystemMessage(context.getSource(), "No active bot found. Spawn one with /bot spawn.");
            return 0;
        }
        SkillPreferences.setPauseOnFullInventory(bot.getUuid(), enabled);
        String state = enabled ? "enabled" : "disabled";
        ChatUtils.sendSystemMessage(context.getSource(),
                "Inventory-full pause " + state + " for " + bot.getName().getString() + ".");
        return 1;
    }

    private static int executeInventoryFullConfigTargets(CommandContext<ServerCommandSource> context,
                                                         String targetArg,
                                                         boolean enabled) throws CommandSyntaxException {
        List<ServerPlayerEntity> bots = resolveTargetBots(context, targetArg);
        boolean isAll = targetArg != null && "all".equalsIgnoreCase(targetArg == null ? "" : targetArg.trim());
        int successes = 0;
        for (ServerPlayerEntity bot : bots) {
            successes += executeInventoryFullConfig(context, bot, enabled);
        }
        if (!bots.isEmpty()) {
            String summary = formatBotList(bots, isAll);
            ChatUtils.sendSystemMessage(context.getSource(),
                    summary + " will " + (enabled ? "pause" : "continue") + " when inventories fill.");
        }
        return successes;
    }

    static boolean parseAssistMode(String raw) throws CommandSyntaxException {
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "on", "enable", "enabled", "true", "yes", "y", "fight", "assist", "start" -> true;
            case "off", "disable", "disabled", "false", "no", "n", "stop", "standdown", "standby" -> false;
            default -> throw new SimpleCommandExceptionType(Text.literal("Unknown mode '" + raw + "'. Use on/enable or off/disable.")).create();
        };
    }

    private static boolean parseToggle(String raw) throws CommandSyntaxException {
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "on", "enable", "enabled", "true", "yes", "y" -> true;
            case "off", "disable", "disabled", "false", "no", "n" -> false;
            default -> throw new SimpleCommandExceptionType(Text.literal("Unknown mode '" + raw + "'. Use on/off.")).create();
        };
    }

    static BotEventHandler.CombatStyle parseCombatStyle(String raw) throws CommandSyntaxException {
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "aggressive", "aggro", "push", "attack" -> BotEventHandler.CombatStyle.AGGRESSIVE;
            case "evasive", "defensive", "retreat", "cover" -> BotEventHandler.CombatStyle.EVASIVE;
            default -> throw new SimpleCommandExceptionType(Text.literal("Unknown stance '" + raw + "'. Use aggressive or evasive.")).create();
        };
    }

    private record SkillCommandInvocation(String target, String arguments, boolean each) {}

    static ServerPlayerEntity getActiveBotOrThrow(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        try {
            List<ServerPlayerEntity> remembered = BotTargetingService.resolve(context.getSource(), null);
            if (!remembered.isEmpty()) {
                return remembered.get(0);
            }
        } catch (CommandSyntaxException ignored) {
        }
        ServerPlayerEntity active = BotEventHandler.bot;
        if (active != null) {
            ServerPlayerEntity refreshed = context.getSource()
                    .getServer()
                    .getPlayerManager()
                    .getPlayer(active.getUuid());
            if (refreshed != null) {
                return refreshed;
            }
        }
        throw new SimpleCommandExceptionType(Text.literal("No active bot found. Specify a bot name, 'all', or spawn one with /bot spawn.")).create();
    }

    private static void rememberTarget(ServerCommandSource source, ServerPlayerEntity bot) {
        if (source == null || bot == null) {
            return;
        }
        BotTargetingService.remember(source, bot.getName().getString());
    }

    private static @NotNull BlockPos getBlockPos(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getEntity() instanceof ServerPlayerEntity sp ? sp : null;
        ServerWorld world = source.getWorld();
        if (player != null && world != null) {
            BlockPos safe = findForwardSpawn(world, player);
            if (safe != null) {
                return safe;
            }
            return new BlockPos((int) player.getX() + 5, (int) player.getY(), (int) player.getZ());
        }
        Vec3d basePos = source.getPosition();
        BlockPos target = BlockPos.ofFloored(basePos.x, basePos.y, basePos.z);
        if (world != null) {
            BlockPos safe = findSafeColumn(world, target);
            if (safe != null) {
                return safe;
            }
        }
        return target;
    }

    private static BlockPos findForwardSpawn(ServerWorld world, ServerPlayerEntity player) {
        Vec3d eye = player.getEyePos();
        Vec3d look = player.getRotationVec(1.0F).normalize();
        if (look.lengthSquared() < 1.0E-4) {
            look = new Vec3d(player.getHorizontalFacing().getOffsetX(), 0, player.getHorizontalFacing().getOffsetZ());
        }

        Direction facing = player.getHorizontalFacing();
        Direction left = facing.rotateYCounterclockwise();
        List<BlockPos> samples = new ArrayList<>();

        for (int dist = 2; dist <= 8; dist++) {
            Vec3d baseVec = eye.add(look.multiply(dist));
            BlockPos base = BlockPos.ofFloored(baseVec.x, player.getBlockY(), baseVec.z);
            samples.add(base);
            samples.add(base.offset(left));
            samples.add(base.offset(left.getOpposite()));
        }
        samples.add(player.getBlockPos());

        for (BlockPos candidate : samples) {
            BlockPos safe = findSafeColumn(world, candidate);
            if (safe != null) {
                return safe;
            }
        }
        return null;
    }

    private static BlockPos findSafeColumn(ServerWorld world, BlockPos base) {
        for (int dy = 2; dy >= -3; dy--) {
            BlockPos candidate = base.up(dy);
            if (isSpawnable(world, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isSpawnable(ServerWorld world, BlockPos feet) {
        BlockState feetState = world.getBlockState(feet);
        BlockState headState = world.getBlockState(feet.up());
        if (!feetState.getCollisionShape(world, feet).isEmpty()) {
            return false;
        }
        if (!headState.getCollisionShape(world, feet.up()).isEmpty()) {
            return false;
        }
        if (!world.getFluidState(feet).isEmpty() || !world.getFluidState(feet.up()).isEmpty()) {
            return false;
        }
        BlockState floor = world.getBlockState(feet.down());
        return !floor.getCollisionShape(world, feet.down()).isEmpty();
    }

    private static int executeSetOwner(CommandContext<ServerCommandSource> context, String alias, ServerPlayerEntity owner) throws CommandSyntaxException {
        if (alias == null || alias.isBlank()) {
            throw new SimpleCommandExceptionType(Text.literal("Alias cannot be empty")).create();
        }
        ManualConfig.BotOwnership ownership = new ManualConfig.BotOwnership(owner.getUuid().toString(), owner.getName().getString());
        Frens.CONFIG.setOwner(alias, ownership);
        Frens.CONFIG.save();
        ChatUtils.sendSystemMessage(context.getSource(), "Set owner of " + alias + " to " + owner.getName().getString());
        return 1;
    }

    static int executeZoneProtect(CommandContext<ServerCommandSource> context, int radius, String label) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayerOrThrow();
        ServerWorld world = source.getWorld();
        
        // Get the block the player is looking at using raycast
        Vec3d eyePos = player.getEyePos();
        Vec3d lookVec = player.getRotationVec(1.0F);
        Vec3d endVec = eyePos.add(lookVec.multiply(5.0));
        
        var hitResult = world.raycast(new net.minecraft.world.RaycastContext(
                eyePos,
                endVec,
                net.minecraft.world.RaycastContext.ShapeType.OUTLINE,
                net.minecraft.world.RaycastContext.FluidHandling.NONE,
                player
        ));
        
        if (hitResult.getType() != net.minecraft.util.hit.HitResult.Type.BLOCK) {
            source.sendError(Text.literal("Look at a block to mark as the zone center (within 5 blocks)."));
            return 0;
        }
        
        BlockPos targetPos = ((net.minecraft.util.hit.BlockHitResult)hitResult).getBlockPos();
        
        // Generate label if not provided
        final String zoneLabel = (label == null || label.isBlank()) 
                ? ProtectedZoneService.generateLabel(world) 
                : label;
        
        // Create the zone
        boolean success = ProtectedZoneService.createZone(world, targetPos, radius, zoneLabel, player);
        if (!success) {
            source.sendError(Text.literal("Failed to create zone. Label '" + zoneLabel + "' may already exist."));
            return 0;
        }
        
        final BlockPos finalPos = targetPos;
        source.sendFeedback(() -> Text.literal("§aCreated protected zone '" + zoneLabel + "' at " + 
                finalPos.toShortString() + " with radius " + radius + 
                "\n§7Bots will not break blocks in this area."), false);
        return 1;
    }

    static int executeZoneRemove(CommandContext<ServerCommandSource> context, String label) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayerOrThrow();
        ServerWorld world = source.getWorld();
        boolean isAdmin = Frens.isOperator(source);
        
        boolean success = ProtectedZoneService.removeZone(world, label, player, isAdmin);
        if (!success) {
            source.sendError(Text.literal("Zone '" + label + "' not found or you don't have permission to remove it."));
            return 0;
        }
        
        source.sendFeedback(() -> Text.literal("§aRemoved protected zone '" + label + "'"), false);
        return 1;
    }

    static int executeZonePermit(CommandContext<ServerCommandSource> context,
                                 String label,
                                 String ownerDescriptor) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity actor = source.getPlayerOrThrow();
        ServerWorld world = source.getWorld();
        boolean isAdmin = Frens.isOperator(source);

        if (label == null || label.isBlank()) {
            source.sendError(Text.literal("Provide a zone label."));
            return 0;
        }
        if (ownerDescriptor == null || ownerDescriptor.isBlank()) {
            source.sendError(Text.literal("Provide a player name, UUID, or bot alias owner to permit."));
            return 0;
        }

        ProtectedZoneService.ProtectedZone zone = findZoneByLabel(world, label);
        if (zone == null) {
            source.sendError(Text.literal("Zone '" + label + "' not found."));
            return 0;
        }

        if (!isAdmin && (zone.getOwnerUuid() == null || !zone.getOwnerUuid().equals(actor.getUuid()))) {
            source.sendError(Text.literal("Only the zone owner (or an operator) can grant access."));
            return 0;
        }

        ZoneOwnerSubject subject = resolveOwnerSubjectForZone(source.getServer(), ownerDescriptor);
        if (subject == null) {
            source.sendError(Text.literal("Couldn't resolve owner from '" + ownerDescriptor + "'."));
            return 0;
        }
        if (zone.getOwnerUuid() != null && zone.getOwnerUuid().equals(subject.ownerUuid())) {
            source.sendError(Text.literal("That owner already controls this zone."));
            return 0;
        }

        boolean ok = ProtectedZoneService.grantZoneAccess(world, zone.getLabel(), subject.ownerUuid());
        if (!ok) {
            source.sendError(Text.literal(subject.ownerName() + " already has access to '" + zone.getLabel() + "'."));
            return 0;
        }

        source.sendFeedback(() -> Text.literal("§aGranted zone access for '" + zone.getLabel() + "' to " + subject.ownerName() + "."), false);
        return 1;
    }

    static int executeZoneRevoke(CommandContext<ServerCommandSource> context,
                                 String label,
                                 String ownerDescriptor) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity actor = source.getPlayerOrThrow();
        ServerWorld world = source.getWorld();
        boolean isAdmin = Frens.isOperator(source);

        if (label == null || label.isBlank()) {
            source.sendError(Text.literal("Provide a zone label."));
            return 0;
        }
        if (ownerDescriptor == null || ownerDescriptor.isBlank()) {
            source.sendError(Text.literal("Provide a player name, UUID, or bot alias owner to revoke."));
            return 0;
        }

        ProtectedZoneService.ProtectedZone zone = findZoneByLabel(world, label);
        if (zone == null) {
            source.sendError(Text.literal("Zone '" + label + "' not found."));
            return 0;
        }

        if (!isAdmin && (zone.getOwnerUuid() == null || !zone.getOwnerUuid().equals(actor.getUuid()))) {
            source.sendError(Text.literal("Only the zone owner (or an operator) can revoke access."));
            return 0;
        }

        ZoneOwnerSubject subject = resolveOwnerSubjectForZone(source.getServer(), ownerDescriptor);
        if (subject == null) {
            source.sendError(Text.literal("Couldn't resolve owner from '" + ownerDescriptor + "'."));
            return 0;
        }
        if (zone.getOwnerUuid() != null && zone.getOwnerUuid().equals(subject.ownerUuid())) {
            source.sendError(Text.literal("You can't revoke the primary owner."));
            return 0;
        }

        boolean ok = ProtectedZoneService.revokeZoneAccess(world, zone.getLabel(), subject.ownerUuid());
        if (!ok) {
            source.sendError(Text.literal(subject.ownerName() + " does not have explicit access to '" + zone.getLabel() + "'."));
            return 0;
        }

        source.sendFeedback(() -> Text.literal("§aRevoked zone access for '" + zone.getLabel() + "' from " + subject.ownerName() + "."), false);
        return 1;
    }

    static int executeZoneMode(CommandContext<ServerCommandSource> context,
                               String label,
                               String modeRaw) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity actor = source.getPlayerOrThrow();
        ServerWorld world = source.getWorld();
        boolean isAdmin = Frens.isOperator(source);

        if (label == null || label.isBlank()) {
            source.sendError(Text.literal("Provide a zone label."));
            return 0;
        }

        ProtectedZoneService.ProtectedZone zone = findZoneByLabel(world, label);
        if (zone == null) {
            source.sendError(Text.literal("Zone '" + label + "' not found."));
            return 0;
        }

        if (!isAdmin && (zone.getOwnerUuid() == null || !zone.getOwnerUuid().equals(actor.getUuid()))) {
            source.sendError(Text.literal("Only the zone owner (or an operator) can change access mode."));
            return 0;
        }

        String mode = normalizeZoneMode(modeRaw);
        if (mode == null) {
            source.sendError(Text.literal("Invalid mode '" + modeRaw + "'. Use owner_only, allowlist, or public."));
            return 0;
        }

        boolean ok = ProtectedZoneService.setZoneAccessMode(world, zone.getLabel(), mode);
        if (!ok) {
            source.sendError(Text.literal("Failed to set access mode for zone '" + zone.getLabel() + "'."));
            return 0;
        }

        source.sendFeedback(() -> Text.literal("§aZone '" + zone.getLabel() + "' access mode set to §f" + mode + "§a."), false);
        return 1;
    }

    static int executeZoneList(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerWorld world = source.getWorld();
        
        List<ProtectedZoneService.ProtectedZone> zones = ProtectedZoneService.listZones(world);
        if (zones.isEmpty()) {
            source.sendFeedback(() -> Text.literal("§eNo protected zones in this world."), false);
            return 1;
        }
        
        source.sendFeedback(() -> Text.literal("§6Protected Zones in " + world.getRegistryKey().getValue() + ":"), false);
        for (ProtectedZoneService.ProtectedZone zone : zones) {
            BlockPos center = zone.getCenter();
            String mode = zone.getAccessMode();
            int permits = zone.getAllowedOwnerUuids().size();
            source.sendFeedback(() -> Text.literal(
                    "§7- §a" + zone.getLabel() + "§7: center=" + center.toShortString() + 
                    ", radius=" + zone.getRadius() + ", owner=" + zone.getOwnerName() +
                    ", mode=" + mode + ", permits=" + permits), false);
        }
        
        return 1;
    }

    private static ProtectedZoneService.ProtectedZone findZoneByLabel(ServerWorld world, String label) {
        if (world == null || label == null || label.isBlank()) {
            return null;
        }
        String needle = label.trim();
        List<ProtectedZoneService.ProtectedZone> zones = ProtectedZoneService.listZones(world);
        for (ProtectedZoneService.ProtectedZone zone : zones) {
            if (zone != null && needle.equals(zone.getLabel())) {
                return zone;
            }
        }
        for (ProtectedZoneService.ProtectedZone zone : zones) {
            if (zone != null && zone.getLabel() != null && needle.equalsIgnoreCase(zone.getLabel())) {
                return zone;
            }
        }
        return null;
    }

    private static String normalizeZoneMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String mode = raw.trim().toLowerCase(Locale.ROOT);
        if ("public".equals(mode) || "allowlist".equals(mode) || "owner_only".equals(mode)) {
            return mode;
        }
        return null;
    }

    private record ZoneOwnerSubject(UUID ownerUuid, String ownerName) {}

    private static ZoneOwnerSubject resolveOwnerSubjectForZone(MinecraftServer server, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String token = raw.trim();

        UUID parsed = tryParseUuidForZone(token);
        if (parsed != null) {
            String display = token;
            if (server != null && server.getPlayerManager() != null) {
                ServerPlayerEntity online = server.getPlayerManager().getPlayer(parsed);
                if (online != null && online.getName() != null) {
                    display = online.getName().getString();
                }
            }
            return new ZoneOwnerSubject(parsed, display);
        }

        if (server != null && server.getPlayerManager() != null) {
            ServerPlayerEntity byName = server.getPlayerManager().getPlayer(token);
            if (byName == null) {
                for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                    if (p != null && p.getName() != null && token.equalsIgnoreCase(p.getName().getString())) {
                        byName = p;
                        break;
                    }
                }
            }
            if (byName != null && byName.getUuid() != null) {
                String display = byName.getName() != null ? byName.getName().getString() : token;
                return new ZoneOwnerSubject(byName.getUuid(), display);
            }
        }

        if (Frens.CONFIG != null) {
            ManualConfig.BotOwnership owner = Frens.CONFIG.getOwner(token);
            if (owner != null && owner.ownerUuid() != null && !owner.ownerUuid().isBlank()) {
                UUID ownerUuid = tryParseUuidForZone(owner.ownerUuid());
                if (ownerUuid != null) {
                    String display = owner.ownerName() != null && !owner.ownerName().isBlank()
                            ? owner.ownerName()
                            : token;
                    return new ZoneOwnerSubject(ownerUuid, display);
                }
            }
        }

        return null;
    }

    private static UUID tryParseUuidForZone(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

            private static void interruptAmbientHobbyIfAny(ServerPlayerEntity bot, String reason) {
                if (bot == null) {
                    return;
                }
                try {
                    // Any explicit player-directed task supersedes a pending resume/stop prompt.
                    SkillResumeService.clearAndNotify(bot.getUuid());
                } catch (Throwable ignored) {
                    // Best-effort; command execution should not fail if resume service is unavailable.
                }
                try {
                    TaskService.getActiveTaskInfo(bot.getUuid()).ifPresent(info -> {
                        if (info.origin() == TaskService.Origin.AMBIENT) {
                            TaskService.forceAbort(bot.getUuid(), reason);
                        }
                    });
                } catch (Throwable ignored) {
                    // Best-effort; command execution should not fail if task system is unavailable.
                }
            }

    /**
     * Computes the primary horizontal direction from one position toward another.
     * Prefers the axis with the largest difference.
     */
    private static Direction computeHorizontalDirection(BlockPos from, BlockPos to) {
        if (from == null || to == null) {
            return Direction.NORTH;
        }
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    /**
     * Returns a cardinal horizontal direction based on where the commander is looking.
     * Uses a raycast hit when possible; falls back to look vector / horizontal facing.
     */
    private static Direction captureLookDirection(ServerPlayerEntity commander, double maxDistance) {
        if (commander == null) {
            return Direction.NORTH;
        }
        try {
            net.minecraft.util.hit.HitResult hit = commander.raycast(maxDistance, 1.0F, false);
            if (hit instanceof net.minecraft.util.hit.BlockHitResult bhr
                    && hit.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK) {
                BlockPos from = commander.getBlockPos();
                BlockPos to = bhr.getBlockPos();
                if (to != null && !to.equals(from)) {
                    return computeHorizontalDirection(from, to);
                }
            }
        } catch (Throwable ignored) {
        }

        Vec3d look = commander.getRotationVec(1.0F);
        if (look != null) {
            double ax = Math.abs(look.x);
            double az = Math.abs(look.z);
            if (ax >= az && ax > 1.0E-4) {
                return look.x >= 0 ? Direction.EAST : Direction.WEST;
            }
            if (az > 1.0E-4) {
                return look.z >= 0 ? Direction.SOUTH : Direction.NORTH;
            }
        }
        return commander.getHorizontalFacing();
    }

    private static Map<String, Object> safeSharedState() {
        try {
            return FunctionCallerV2.getSharedState();
        } catch (Throwable t) {
            net.wcfcarolina13.GameAI.services.DebugFileLogger.log("Command.safeSharedState unavailable: "
                    + t.getClass().getSimpleName());
            return new HashMap<>();
        }
    }
}
