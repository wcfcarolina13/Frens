package net.wcfcarolina13;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.command.permission.PermissionPredicate;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.wcfcarolina13.ChatUtils.BERTModel.BertModelManager;
import net.wcfcarolina13.ChatUtils.BotAmbientChatter;
import net.wcfcarolina13.ChatUtils.BotMoodManager;
import net.wcfcarolina13.ChatUtils.ChatUtils;
import net.wcfcarolina13.ChatUtils.NLPProcessor;
import net.wcfcarolina13.Commands.configCommand;
import net.wcfcarolina13.Commands.modCommandRegistry;
import net.wcfcarolina13.Database.SQLiteDB;
import net.wcfcarolina13.FilingSystem.ManualConfig;
import net.wcfcarolina13.GameAI.BotEventHandler;
import net.wcfcarolina13.GameAI.services.SkillResumeService;
import net.wcfcarolina13.FunctionCaller.FunctionCallerV2;
import net.wcfcarolina13.GameAI.services.BotPersistenceService;
import net.wcfcarolina13.GameAI.services.HuntSessionService;
import net.wcfcarolina13.GameAI.services.BotAutoReturnSunsetService;
import net.wcfcarolina13.GameAI.services.BotAmbientSocialChatService;
import net.wcfcarolina13.GameAI.services.BotCampfireAvoidanceService;
import net.wcfcarolina13.GameAI.services.BotIdleHobbiesService;
import net.wcfcarolina13.GameAI.services.BotInventoryFullDialogueService;
import net.wcfcarolina13.GameAI.llm.LLMOrchestrator;
import net.wcfcarolina13.GameAI.services.HuntCatalog;
import net.wcfcarolina13.GameAI.services.HuntHistoryService;
import net.wcfcarolina13.GameAI.services.BotQuestService;

import net.wcfcarolina13.Database.QTableStorage;
import net.wcfcarolina13.Entity.AutoFaceEntity;
import net.wcfcarolina13.network.OpenConfigPayload;
import net.wcfcarolina13.network.SaveAPIKeyPayload;
import net.wcfcarolina13.network.SaveConfigPayload;
import net.wcfcarolina13.network.SaveCustomProviderPayload;
import net.wcfcarolina13.network.UpdatePlayerPreservePayload;
import net.wcfcarolina13.network.RequestPlayerPreservePayload;
import net.wcfcarolina13.network.PlayerPreserveStatePayload;
import net.wcfcarolina13.network.configNetworkManager;
import net.wcfcarolina13.network.ConfirmRecruitmentPayload;
import net.wcfcarolina13.network.OpenRecruitmentDialoguePayload;
import net.wcfcarolina13.network.RecruitmentPromptPayload;
import net.wcfcarolina13.network.RecruitmentStatePayload;
import net.wcfcarolina13.network.RequestRecruitmentDialoguePayload;
import net.wcfcarolina13.network.SurvivalRecruitmentNetworkManager;
import net.wcfcarolina13.WebSearch.AISearchConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.util.Identifier;
import net.minecraft.registry.Registry;
import net.minecraft.registry.Registries;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.wcfcarolina13.items.ModItems;
import net.wcfcarolina13.GameAI.services.LearningModeService;
import net.wcfcarolina13.GameAI.services.NavigationArtifactService;
import net.wcfcarolina13.network.LearningInputSamplePayload;
import net.wcfcarolina13.network.LearningSessionStatusPayload;

public class Frens implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("frens");
    public static final String MOD_ID = "frens";
    private record PermissionResolution(PermissionPredicate predicate, String mode, String className, String fieldName) {}
    private static final PermissionResolution OPERATOR_PERMISSION_RESOLUTION = resolveOperatorPermissions();
    public static final PermissionPredicate OPERATOR_PERMISSIONS = OPERATOR_PERMISSION_RESOLUTION.predicate();

    public static boolean isOperator(ServerPlayerEntity player) {
        if (player == null || serverInstance == null) {
            return false;
        }
        return serverInstance.getPlayerManager().isOperator(new PlayerConfigEntry(player.getGameProfile()));
    }

    public static boolean isOperator(ServerCommandSource source) {
        if (source == null) {
            return false;
        }
        ServerPlayerEntity player = source.getPlayer();
        if (player != null) {
            return isOperator(player);
        }
        return true;
    }

    public static boolean hasBotCommandPermission(ServerCommandSource source) {
        if (source == null) {
            return false;
        }
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return true; // console / command block
        }
        if (hasPermissionLevelCompat(source, 2) || hasPermissionLevelCompat(player, 2)) {
            return true;
        }
        return isOperator(player);
    }

    private static PermissionResolution resolveOperatorPermissions() {
        ClassLoader loader = Frens.class.getClassLoader();
        String className = "net.minecraft.command.permission.LeveledPermissionPredicate";
        String[] preferredFields = {"OWNERS", "ADMINS", "GAMEMASTERS", "MODERATORS", "ALL"};
        try {
            Class<?> clazz = Class.forName(className, false, loader);
            for (String fieldName : preferredFields) {
                PermissionPredicate candidate = readStaticPermissionPredicate(clazz, fieldName);
                if (candidate != null) {
                    return new PermissionResolution(candidate, "leveled-field", clazz.getName(), fieldName);
                }
            }
            PermissionPredicate any = findAnyStaticPermissionPredicate(clazz);
            if (any != null) {
                return new PermissionResolution(any, "leveled-any-static", clazz.getName(), "first-static");
            }
            PermissionPredicate constructed = constructPermissionPredicate(clazz);
            if (constructed != null) {
                return new PermissionResolution(constructed, "leveled-constructor", clazz.getName(), "ctor");
            }
        } catch (Throwable t) {
            LOGGER.warn("[PermCheck] leveled permission predicate lookup failed: {}", t.toString());
        }

        for (String fieldName : new String[]{"ALL", "NONE"}) {
            PermissionPredicate candidate = readStaticPermissionPredicate(PermissionPredicate.class, fieldName);
            if (candidate != null) {
                return new PermissionResolution(candidate, "permission-static-fallback", PermissionPredicate.class.getName(), fieldName);
            }
        }

        PermissionPredicate fallback = findAnyStaticPermissionPredicate(PermissionPredicate.class);
        if (fallback != null) {
            return new PermissionResolution(fallback, "permission-any-static-fallback", PermissionPredicate.class.getName(), "first-static");
        }

        fallback = constructPermissionPredicate(PermissionPredicate.class);
        if (fallback != null) {
            return new PermissionResolution(fallback, "permission-ctor-fallback", PermissionPredicate.class.getName(), "ctor");
        }

        PermissionPredicate ctorLastResort = new PermissionPredicate();
        return new PermissionResolution(ctorLastResort, "permission-ctor-last-resort", PermissionPredicate.class.getName(), "ctor-direct");
    }

    private static PermissionPredicate readStaticPermissionPredicate(Class<?> clazz, String fieldName) {
        try {
            Field field = clazz.getDeclaredField(fieldName);
            if (!Modifier.isStatic(field.getModifiers())) {
                return null;
            }
            field.setAccessible(true);
            Object value = field.get(null);
            if (value instanceof PermissionPredicate predicate) {
                return predicate;
            }
        } catch (Throwable ignored) {
        }
        try {
            Field field = clazz.getField(fieldName);
            Object value = field.get(null);
            if (value instanceof PermissionPredicate predicate) {
                return predicate;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static PermissionPredicate findAnyStaticPermissionPredicate(Class<?> clazz) {
        for (Field field : clazz.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (!PermissionPredicate.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(null);
                if (value instanceof PermissionPredicate predicate) {
                    return predicate;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static PermissionPredicate constructPermissionPredicate(Class<?> clazz) {
        try {
            var ctor = clazz.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object value = ctor.newInstance();
            if (value instanceof PermissionPredicate predicate) {
                return predicate;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean hasPermissionLevelCompat(Object target, int level) {
        if (target == null) {
            return false;
        }
        try {
            var m = target.getClass().getMethod("hasPermissionLevel", int.class);
            Object out = m.invoke(target, level);
            return out instanceof Boolean b && b;
        } catch (Throwable ignored) {
        }
        try {
            var m = target.getClass().getMethod("hasPermission", int.class);
            Object out = m.invoke(target, level);
            return out instanceof Boolean b && b;
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static ScreenHandlerType<net.wcfcarolina13.ui.BotPlayerInventoryScreenHandler> BOT_PLAYER_INV_HANDLER;
    public static ScreenHandlerType<net.wcfcarolina13.ui.BotEnchantmentScreenHandler> BOT_ENCHANT_HANDLER;
    public static ScreenHandlerType<net.wcfcarolina13.ui.BotAnvilScreenHandler> BOT_ANVIL_HANDLER;
    public static final ManualConfig CONFIG = ManualConfig.load();
    public static MinecraftServer serverInstance = null; // default for now
    public static BertModelManager modelManager;
    public static boolean loadedBERTModelIntoMemory = false;
    private static boolean djlAvailable = false;
    private static final java.util.concurrent.ConcurrentHashMap<java.util.UUID, Long> LAST_OPEN_MS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final ExecutorService MODEL_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bert-loader");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicBoolean MODEL_LOAD_ENQUEUED = new AtomicBoolean(false);

    // If the optional Ollama client library isn't present (aiEnabled=false builds), do not touch FunctionCallerV2.
    private static final boolean OLLAMA4J_AVAILABLE = isClassAvailable("io.github.amithkoujalgi.ollama4j.core.exceptions.OllamaBaseException");
    private static final AtomicBoolean WARNED_MISSING_OLLAMA4J = new AtomicBoolean(false);
    
    // Track bots that need spawn escape checks
    private static final ConcurrentHashMap<UUID, SpawnEscapeCheck> SPAWN_ESCAPE_CHECKS = new ConcurrentHashMap<>();
    
    // Track last IN_WALL damage warning to prevent log flooding
    private static final ConcurrentHashMap<UUID, Long> LAST_IN_WALL_WARNING_TICK = new ConcurrentHashMap<>();
    private static final long IN_WALL_WARNING_COOLDOWN_TICKS = 60L; // 3 seconds between warnings
    
    private static class SpawnEscapeCheck {
        int ticksWaited = 0;
        int attemptsRemaining = 5;
    }

    @Override
    public void onInitialize() {
        // Migrate any data left behind under the old "ai-player" mod-ID directories.
        net.wcfcarolina13.GameAI.services.LegacyDataMigrationService.migrateConfigDir();

        LOGGER.info("[PermCheck] operator-permission mode={} class={} field={}",
                OPERATOR_PERMISSION_RESOLUTION.mode(),
                OPERATOR_PERMISSION_RESOLUTION.className(),
                OPERATOR_PERMISSION_RESOLUTION.fieldName());

        // ScreenHandler registration for bot/player UI
        BOT_PLAYER_INV_HANDLER = Registry.register(
                Registries.SCREEN_HANDLER,
                Identifier.of(MOD_ID, "bot_player_inventory"),
                new ScreenHandlerType<>(net.wcfcarolina13.ui.BotPlayerInventoryScreenHandler::clientFactory, FeatureFlags.VANILLA_FEATURES)
        );
        BOT_ENCHANT_HANDLER = Registry.register(
                Registries.SCREEN_HANDLER,
                Identifier.of(MOD_ID, "bot_enchanting"),
                new ScreenHandlerType<>(net.wcfcarolina13.ui.BotEnchantmentScreenHandler::clientFactory, FeatureFlags.VANILLA_FEATURES)
        );
        BOT_ANVIL_HANDLER = Registry.register(
                Registries.SCREEN_HANDLER,
                Identifier.of(MOD_ID, "bot_anvil"),
                new ScreenHandlerType<>(net.wcfcarolina13.ui.BotAnvilScreenHandler::clientFactory, FeatureFlags.VANILLA_FEATURES)
        );

        // Register bot dialogue sound events
        net.wcfcarolina13.ChatUtils.BotDialogueSounds.registerAll();

        // Register mod items
        ModItems.registerAll();

        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClient()) return net.minecraft.util.ActionResult.PASS;
            // Only react once (MAIN_HAND). OFF_HAND callback will return PASS to avoid double-fire.
            if (hand != net.minecraft.util.Hand.MAIN_HAND) return net.minecraft.util.ActionResult.PASS;

            // Survival recruitment: interacting with a villager counts as a trigger.
            if (player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer
                    && entity instanceof net.minecraft.entity.passive.VillagerEntity) {
                net.wcfcarolina13.GameAI.services.SurvivalRecruitmentService.notePlayerInteraction(serverPlayer, "villager");
                net.wcfcarolina13.GameAI.services.VillageProximityReactionService.onPlayerInteractedVillager(
                        serverPlayer,
                        (net.minecraft.entity.passive.VillagerEntity) entity
                );
                return net.minecraft.util.ActionResult.PASS;
            }

            if (!(entity instanceof net.minecraft.server.network.ServerPlayerEntity bot)) return net.minecraft.util.ActionResult.PASS;

            // Only handle interactions for registered bot players.
            if (!BotEventHandler.isRegisteredBot(bot)) return net.minecraft.util.ActionResult.PASS;

            // Sneak-right-click: "touch" flavor line (opt-in via /bot idle_hobbies on).
            if (player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer && serverPlayer.isSneaking()) {
                boolean spoke = net.wcfcarolina13.GameAI.services.BotTouchChatService.trySendTouchLine(serverPlayer, bot);
                return spoke ? net.minecraft.util.ActionResult.SUCCESS : net.minecraft.util.ActionResult.PASS;
            }

            // Normal right-click: try food-giving first, then allow shared inventory.
            if (player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer) {
                // If player is holding food, try the food-giving mechanic.
                if (net.wcfcarolina13.GameAI.services.BotFoodGivingService.tryGiveFood(serverPlayer, bot)) {
                    return net.minecraft.util.ActionResult.SUCCESS;
                }
                // Otherwise emit a touch line (cooldown-gated).
                net.wcfcarolina13.GameAI.services.BotTouchChatService.trySendTouchLine(serverPlayer, bot);
            }

            // Allow the shared inventory view when not holding food.

            // Simple debounce per bot to prevent rapid re-open/close edge cases
            long now = System.currentTimeMillis();
            java.util.UUID key = bot.getUuid();
            Long last = LAST_OPEN_MS.getOrDefault(key, 0L);
            if (now - last < 200) return net.minecraft.util.ActionResult.PASS;
            LAST_OPEN_MS.put(key, now);

            if (!net.wcfcarolina13.GameAI.services.InventoryAccessPolicy
                    .canOpen((net.minecraft.server.network.ServerPlayerEntity) player, bot)) {
                return net.minecraft.util.ActionResult.PASS;
            }

            boolean ok = net.wcfcarolina13.ui.BotInventoryAccess.openBotInventory(
                    (net.minecraft.server.network.ServerPlayerEntity) player, bot
            );
            return ok ? net.minecraft.util.ActionResult.SUCCESS : net.minecraft.util.ActionResult.PASS;
        });

        // Nether ritual resurrection: sneak-right-click a Respawn Anchor with a Ghast Tear.
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world == null || world.isClient()) return net.minecraft.util.ActionResult.PASS;
            if (!(player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer)) return net.minecraft.util.ActionResult.PASS;
            if (!(world instanceof net.minecraft.server.world.ServerWorld serverWorld)) return net.minecraft.util.ActionResult.PASS;

            // Learning mode: record player block interactions (best-effort, non-consuming).
            LearningModeService.onUseBlock(serverPlayer, serverWorld, hand, hitResult);

            // Survival recruitment: interacting with a bell or a bed counts as a trigger.
            // Don't consume the action; just record it.
            if (hand == net.minecraft.util.Hand.MAIN_HAND) {
                try {
                    var pos = hitResult.getBlockPos();
                    var state = serverWorld.getBlockState(pos);
                    if (state != null) {
                        if (state.isOf(net.minecraft.block.Blocks.BELL)) {
                            net.wcfcarolina13.GameAI.services.SurvivalRecruitmentService.notePlayerInteraction(serverPlayer, "bell");
                        } else if (state.isIn(net.minecraft.registry.tag.BlockTags.BEDS)) {
                            net.wcfcarolina13.GameAI.services.SurvivalRecruitmentService.notePlayerInteraction(serverPlayer, "bed");
                        }

                        // Wizard's Tome ritual: sneak-right-click Enchanting Table in The End with Eye of Ender.
                        if (state.isOf(net.minecraft.block.Blocks.ENCHANTING_TABLE)) {
                            net.minecraft.util.ActionResult tome = net.wcfcarolina13.GameAI.services.WizardTomeCraftingService
                                    .tryHandleUseBlock(serverPlayer, serverWorld, hand, pos);
                            if (tome != net.minecraft.util.ActionResult.PASS) {
                                return tome;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    // Best effort only.
                }
            }
            // Lock mode: right-click to toggle lock on doors/gates/trapdoors (admin only, main hand only)
            if (hand == net.minecraft.util.Hand.MAIN_HAND
                    && isOperator(serverPlayer.getCommandSource())
                    && net.wcfcarolina13.GameAI.services.LockableBlockService.isLockModeActive(serverPlayer.getUuid())) {
                var lockPos = hitResult.getBlockPos();
                var lockState = serverWorld.getBlockState(lockPos);
                if (net.wcfcarolina13.GameAI.services.LockableBlockService.isLockableBlock(lockState)) {
                    net.wcfcarolina13.GameAI.services.LockableBlockService.toggleLock(serverWorld, lockPos, serverPlayer);
                    return net.minecraft.util.ActionResult.SUCCESS;
                }
            }

            // Zone wand: right-click to set corners (admin only, main hand only)
            if (hand == net.minecraft.util.Hand.MAIN_HAND
                    && net.wcfcarolina13.network.ZoneNetworkManager.isZoneWand(serverPlayer.getStackInHand(hand))
                    && isOperator(serverPlayer.getCommandSource())) {
                net.wcfcarolina13.network.ZoneNetworkManager.handleWandUse(serverPlayer, hitResult.getBlockPos());
                return net.minecraft.util.ActionResult.SUCCESS;
            }

            return net.wcfcarolina13.GameAI.services.CompanionResurrectionService
                    .tryHandleUseBlock(serverPlayer, serverWorld, hand, hitResult.getBlockPos());
        });

        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world == null || world.isClient()) return net.minecraft.util.ActionResult.PASS;
            if (player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer
                    && world instanceof net.minecraft.server.world.ServerWorld serverWorld) {
                LearningModeService.onUseItem(serverPlayer, serverWorld, hand);
            }
            return net.minecraft.util.ActionResult.PASS;
        });

        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (world == null || world.isClient()) return net.minecraft.util.ActionResult.PASS;
            if (player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer
                    && world instanceof net.minecraft.server.world.ServerWorld serverWorld) {
                LearningModeService.onAttackBlock(serverPlayer, serverWorld, hand, pos, direction);
            }
            return net.minecraft.util.ActionResult.PASS;
        });

        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (world == null || world.isClient()) return;
            if (player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer
                    && world instanceof net.minecraft.server.world.ServerWorld serverWorld) {
                LearningModeService.onBlockBreakAfter(serverPlayer, serverWorld, pos, state);
                net.wcfcarolina13.GameAI.services.CommanderActivityService.markBlockBreak(serverPlayer);
            }
        });

        LOGGER.info("Hello Fabric world!");

        LOGGER.debug("Running on environment type: {}", FabricLoader.getInstance().getEnvironmentType());



        String llmProvider = System.getProperty("frens.llmMode", System.getProperty("aiplayer.llmMode", "ollama"));
        System.out.println("Using provider: " + llmProvider);

        // Debug: Print ALL system properties to see what's available
        System.out.println("=== ALL SYSTEM PROPERTIES ===");
        System.getProperties().forEach((key, value) -> {
            if (key.toString().contains("frens") || key.toString().contains("aiplayer") || key.toString().contains("llm")) {
                System.out.println(key + " = " + value);
            }
        });
        System.out.println("=== END DEBUG ===");

        // registering the packets on the global entrypoint to recognise them
        PayloadTypeRegistry.playC2S().register(SaveConfigPayload.ID, SaveConfigPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenConfigPayload.ID, OpenConfigPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SaveAPIKeyPayload.ID, SaveAPIKeyPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SaveCustomProviderPayload.ID, SaveCustomProviderPayload.CODEC);

        // Bases manager UI payloads
        PayloadTypeRegistry.playC2S().register(net.wcfcarolina13.network.RequestBasesPayload.ID, net.wcfcarolina13.network.RequestBasesPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(net.wcfcarolina13.network.BasesListPayload.ID, net.wcfcarolina13.network.BasesListPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(net.wcfcarolina13.network.BaseSetPayload.ID, net.wcfcarolina13.network.BaseSetPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(net.wcfcarolina13.network.BaseRemovePayload.ID, net.wcfcarolina13.network.BaseRemovePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(net.wcfcarolina13.network.BaseRenamePayload.ID, net.wcfcarolina13.network.BaseRenamePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(net.wcfcarolina13.network.BaseSetRadiusPayload.ID, net.wcfcarolina13.network.BaseSetRadiusPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(net.wcfcarolina13.network.BaseSetHomePayload.ID, net.wcfcarolina13.network.BaseSetHomePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(net.wcfcarolina13.network.BaseGoToPayload.ID, net.wcfcarolina13.network.BaseGoToPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(net.wcfcarolina13.network.BaseMapVillagePayload.ID, net.wcfcarolina13.network.BaseMapVillagePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(net.wcfcarolina13.network.BaseClaimWallPayload.ID, net.wcfcarolina13.network.BaseClaimWallPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(net.wcfcarolina13.network.BaseUnclaimWallPayload.ID, net.wcfcarolina13.network.BaseUnclaimWallPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(net.wcfcarolina13.network.BaseGrantWallAccessPayload.ID, net.wcfcarolina13.network.BaseGrantWallAccessPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(net.wcfcarolina13.network.BaseRevokeWallAccessPayload.ID, net.wcfcarolina13.network.BaseRevokeWallAccessPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(net.wcfcarolina13.network.RequestCraftingHistoryPayload.ID, net.wcfcarolina13.network.RequestCraftingHistoryPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(net.wcfcarolina13.network.CraftingHistoryPayload.ID, net.wcfcarolina13.network.CraftingHistoryPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(net.wcfcarolina13.network.RequestCookablesPayload.ID, net.wcfcarolina13.network.RequestCookablesPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(net.wcfcarolina13.network.CookablesPayload.ID, net.wcfcarolina13.network.CookablesPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(net.wcfcarolina13.network.ResumeDecisionPayload.ID, net.wcfcarolina13.network.ResumeDecisionPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(net.wcfcarolina13.network.RequestHuntablesPayload.ID, net.wcfcarolina13.network.RequestHuntablesPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(net.wcfcarolina13.network.HuntablesPayload.ID, net.wcfcarolina13.network.HuntablesPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(net.wcfcarolina13.network.HuntDiscoveryPayload.ID, net.wcfcarolina13.network.HuntDiscoveryPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(net.wcfcarolina13.network.SaveHuntConfigPayload.ID, net.wcfcarolina13.network.SaveHuntConfigPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(net.wcfcarolina13.network.HuntConfigPayload.ID, net.wcfcarolina13.network.HuntConfigPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(net.wcfcarolina13.network.HuntTargetPayload.ID, net.wcfcarolina13.network.HuntTargetPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(net.wcfcarolina13.network.HuntTargetModePayload.ID, net.wcfcarolina13.network.HuntTargetModePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(net.wcfcarolina13.network.RequestChestRegistryPayload.ID, net.wcfcarolina13.network.RequestChestRegistryPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(net.wcfcarolina13.network.ChestRegistryPayload.ID, net.wcfcarolina13.network.ChestRegistryPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(net.wcfcarolina13.network.ChestCollectPayload.ID, net.wcfcarolina13.network.ChestCollectPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(net.wcfcarolina13.network.ChestDismissPayload.ID, net.wcfcarolina13.network.ChestDismissPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(net.wcfcarolina13.network.StoreTargetPayload.ID, net.wcfcarolina13.network.StoreTargetPayload.CODEC);

        // Survival recruitment (find village -> recruit bot) payloads.
        PayloadTypeRegistry.playS2C().register(RecruitmentPromptPayload.ID, RecruitmentPromptPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenRecruitmentDialoguePayload.ID, OpenRecruitmentDialoguePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(RecruitmentStatePayload.ID, RecruitmentStatePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(RequestRecruitmentDialoguePayload.ID, RequestRecruitmentDialoguePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ConfirmRecruitmentPayload.ID, ConfirmRecruitmentPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(net.wcfcarolina13.network.RequestRecruitmentReplayPayload.ID, net.wcfcarolina13.network.RequestRecruitmentReplayPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(net.wcfcarolina13.network.ModeSelectionChoicePayload.ID, net.wcfcarolina13.network.ModeSelectionChoicePayload.CODEC);

        // Companion questline (server-authoritative dialogue checks)
        PayloadTypeRegistry.playC2S().register(net.wcfcarolina13.network.CompanionQuestTopicPayload.ID, net.wcfcarolina13.network.CompanionQuestTopicPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(net.wcfcarolina13.network.CompanionQuestResponsePayload.ID, net.wcfcarolina13.network.CompanionQuestResponsePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(net.wcfcarolina13.network.CompanionQuestStateRequestPayload.ID, net.wcfcarolina13.network.CompanionQuestStateRequestPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(net.wcfcarolina13.network.CompanionQuestStatePayload.ID, net.wcfcarolina13.network.CompanionQuestStatePayload.CODEC);

        // Operator-only admin actions for survival recruitment mode.
        PayloadTypeRegistry.playC2S().register(net.wcfcarolina13.network.RecruitmentAdminActionPayload.ID, net.wcfcarolina13.network.RecruitmentAdminActionPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(net.wcfcarolina13.network.RecruitmentAdminStatusPayload.ID, net.wcfcarolina13.network.RecruitmentAdminStatusPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(net.wcfcarolina13.network.RecruitmentAdminPermissionsPayload.ID, net.wcfcarolina13.network.RecruitmentAdminPermissionsPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(net.wcfcarolina13.network.BotTaskPeekRequestPayload.ID, net.wcfcarolina13.network.BotTaskPeekRequestPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(net.wcfcarolina13.network.BotTaskPeekStatusPayload.ID, net.wcfcarolina13.network.BotTaskPeekStatusPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(LearningInputSamplePayload.ID, LearningInputSamplePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(LearningSessionStatusPayload.ID, LearningSessionStatusPayload.CODEC);

        // Bot skin change
        PayloadTypeRegistry.playC2S().register(net.wcfcarolina13.network.BotSkinPayload.ID, net.wcfcarolina13.network.BotSkinPayload.CODEC);

        // Navigation spell payloads
        PayloadTypeRegistry.playS2C().register(net.wcfcarolina13.network.BotNavTierPayload.ID, net.wcfcarolina13.network.BotNavTierPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(net.wcfcarolina13.network.NavigationRequestPayload.ID, net.wcfcarolina13.network.NavigationRequestPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(net.wcfcarolina13.network.NavigationResponsePayload.ID, net.wcfcarolina13.network.NavigationResponsePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(net.wcfcarolina13.network.SpellGuidancePayload.ID, net.wcfcarolina13.network.SpellGuidancePayload.CODEC);

        // Guide -> remote bot inventory open
        PayloadTypeRegistry.playC2S().register(net.wcfcarolina13.network.GuideOpenInventoryPayload.ID, net.wcfcarolina13.network.GuideOpenInventoryPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(net.wcfcarolina13.network.GuideInventoryAccessPayload.ID, net.wcfcarolina13.network.GuideInventoryAccessPayload.CODEC);

        // Bot enchanting screen
        PayloadTypeRegistry.playC2S().register(net.wcfcarolina13.network.BotEnchantOpenPayload.ID, net.wcfcarolina13.network.BotEnchantOpenPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(net.wcfcarolina13.network.BotAnvilOpenPayload.ID, net.wcfcarolina13.network.BotAnvilOpenPayload.CODEC);

        // Protected zone management
        PayloadTypeRegistry.playC2S().register(net.wcfcarolina13.network.ZoneWandRequestPayload.ID, net.wcfcarolina13.network.ZoneWandRequestPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(net.wcfcarolina13.network.ZoneCornerSetPayload.ID, net.wcfcarolina13.network.ZoneCornerSetPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(net.wcfcarolina13.network.ZoneConfirmPayload.ID, net.wcfcarolina13.network.ZoneConfirmPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(net.wcfcarolina13.network.ZoneToggleViewPayload.ID, net.wcfcarolina13.network.ZoneToggleViewPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(net.wcfcarolina13.network.ZoneEditPayload.ID, net.wcfcarolina13.network.ZoneEditPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(net.wcfcarolina13.network.ZoneDeletePayload.ID, net.wcfcarolina13.network.ZoneDeletePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(net.wcfcarolina13.network.ZoneListPayload.ID, net.wcfcarolina13.network.ZoneListPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(net.wcfcarolina13.network.RequestZoneListPayload.ID, net.wcfcarolina13.network.RequestZoneListPayload.CODEC);

        // Lockable blocks
        PayloadTypeRegistry.playC2S().register(net.wcfcarolina13.network.LockModeTogglePayload.ID, net.wcfcarolina13.network.LockModeTogglePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(net.wcfcarolina13.network.LockModeStatePayload.ID, net.wcfcarolina13.network.LockModeStatePayload.CODEC);

        // Player durability preservation preference
        PayloadTypeRegistry.playC2S().register(UpdatePlayerPreservePayload.ID, UpdatePlayerPreservePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(RequestPlayerPreservePayload.ID, RequestPlayerPreservePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(PlayerPreserveStatePayload.ID, PlayerPreserveStatePayload.CODEC);

        // Rescue teleport (player-pressed keybind to un-stick a nearby following bot)
        PayloadTypeRegistry.playC2S().register(
                net.wcfcarolina13.network.RescueTeleportRequestPayload.ID,
                net.wcfcarolina13.network.RescueTeleportRequestPayload.CODEC);

        net.wcfcarolina13.network.BaseNetworkManager.registerReceiversOnce();
        net.wcfcarolina13.network.CraftingHistoryNetworkManager.registerReceiversOnce();
        net.wcfcarolina13.network.CookablesNetworkManager.registerReceiversOnce();
        net.wcfcarolina13.network.HuntablesNetworkManager.registerReceiversOnce();
        net.wcfcarolina13.network.ChestRegistryNetworkManager.registerReceiversOnce();
        SurvivalRecruitmentNetworkManager.registerReceiversOnce();
        net.wcfcarolina13.network.CompanionQuestNetworkManager.registerReceiversOnce();
        net.wcfcarolina13.network.RecruitmentAdminNetworkManager.registerReceiversOnce();
        net.wcfcarolina13.network.BotTaskPeekNetworkManager.registerReceiversOnce();
        net.wcfcarolina13.network.BotSkinNetworkManager.registerReceiversOnce();
        LearningModeService.registerReceiversOnce();
        net.wcfcarolina13.network.SpellNavigationNetworkManager.registerReceiversOnce();
        net.wcfcarolina13.network.GuideInventoryNetworkManager.registerReceiversOnce();
        net.wcfcarolina13.network.BotEnchantNetworkManager.registerReceiversOnce();
        net.wcfcarolina13.network.BotAnvilNetworkManager.registerReceiversOnce();
        net.wcfcarolina13.network.ZoneNetworkManager.registerReceiversOnce();
        net.wcfcarolina13.network.RescueTeleportNetworkManager.registerReceiversOnce();

        // Lock mode C2S receiver
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(
                net.wcfcarolina13.network.LockModeTogglePayload.ID, (payload, context) ->
                        context.server().execute(() -> {
                            ServerPlayerEntity player = context.player();
                            if (player == null || !isOperator(player.getCommandSource())) return;
                            boolean active = payload.active();
                            net.wcfcarolina13.GameAI.services.LockableBlockService.setLockMode(player.getUuid(), active);
                            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                                    new net.wcfcarolina13.network.LockModeStatePayload(active));
                        }));

        // Player preserve expensive gear C2S receivers
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(UpdatePlayerPreservePayload.ID, (payload, context) -> {
            ServerPlayerEntity sender = context.player();
            if (sender == null) {
                return;
            }
            java.util.UUID senderUuid = sender.getUuid();
            if (senderUuid == null) {
                return;
            }
            context.server().execute(() -> {
                if (Frens.CONFIG != null) {
                    Frens.CONFIG.setPreserveExpensiveGear(senderUuid, payload.enabled());
                    Frens.CONFIG.save();
                }
                // If the player just flipped OFF → ON, clear any stale cooldowns for their bots
                // so the next selection call gets a fresh fallback attempt.
                if (payload.enabled()) {
                    net.wcfcarolina13.GameAI.services.DurabilityFallbackService.clearCooldownsForOwner(senderUuid);
                }
            });
        });

        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(RequestPlayerPreservePayload.ID, (payload, context) -> {
            ServerPlayerEntity sender = context.player();
            if (sender == null) {
                return;
            }
            java.util.UUID senderUuid = sender.getUuid();
            if (senderUuid == null) {
                return;
            }
            context.server().execute(() -> {
                boolean current = Frens.CONFIG != null
                        && Frens.CONFIG.getPreserveExpensiveGear(senderUuid);
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(sender, new PlayerPreserveStatePayload(current));
            });
        });

        modCommandRegistry.register();
        configCommand.register();
        try {
            SQLiteDB.createDB();
        } catch (Exception e) {
            Frens.LOGGER.error("Failed to initialize Frens database; running without DB-backed memory.", e);
        }
        QTableStorage.setupQTableStorage();

        CompletableFuture.runAsync(() -> {
            AISearchConfig.setupIfMissing();
            NLPProcessor.ensureLocalNLPModel();
            try {
                Thread.sleep(2000);
                System.out.println("NLP model deployment task complete");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        djlAvailable = isClassAvailable("ai.djl.Model");
        if (djlAvailable) {
            modelManager = BertModelManager.getInstance();
        } else {
            modelManager = null;
            LOGGER.warn("DJL not found on classpath; BERT intent model disabled.");
        }

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            net.wcfcarolina13.GameAI.services.TaskService.clearServerStopping();
            // Re-create any executors that SERVER_STOPPING shut down. Without this,
            // exit-to-title + reload within the same Minecraft session leaves every
            // mod executor permanently dead and breaks /bot skill, mining, hunting,
            // hobbies, etc. Each restartExecutors() is a no-op if the executor is
            // still alive (server starting fresh inside a new JVM).
            net.wcfcarolina13.PlayerUtils.MiningTool.restartExecutors();
            net.wcfcarolina13.GameAI.services.BotIdleHobbiesService.restartExecutors();
            net.wcfcarolina13.GameAI.BotEventHandler.restartExecutors();
            net.wcfcarolina13.Commands.modCommandRegistry.restartExecutors();
            net.wcfcarolina13.GameAI.services.BotAutoReturnSunsetService.restartExecutors();
            net.wcfcarolina13.GameAI.services.BotAutoHuntService.restartExecutors();
            net.wcfcarolina13.GameAI.services.BotUndergroundSurvivalService.restartExecutors();
            net.wcfcarolina13.GameAI.services.MovementService.restartExecutors();
            configNetworkManager.registerServerModelNameSaveReceiver(server);
            configNetworkManager.registerServerAPIKeySaveReceiver(server);
            configNetworkManager.registerServerCustomProviderSaveReceiver(server);
            serverInstance = server;
            LOGGER.info("Server instance stored!");

            System.out.println("Server instance is " + serverInstance);

            enqueueBertLoad();
            net.wcfcarolina13.GameAI.services.BotControlApplier.applyPersistentSettings(server);
            net.wcfcarolina13.PathFinding.PathFinder.USE_BARITONE_STYLE =
                    CONFIG != null && CONFIG.isBaritonePathfinderEnabled();

            if (CONFIG != null) {
                net.wcfcarolina13.GameAI.services.CompanionSafeZoneService.setFortBufferRadius(
                        CONFIG.getFortBufferRadius());
            }

            // Load protected zones for all worlds
            server.getWorlds().forEach(world -> {
                String worldId = world.getRegistryKey().getValue().toString();
                net.wcfcarolina13.GameAI.services.ProtectedZoneService.loadZones(server, worldId);
                net.wcfcarolina13.GameAI.services.LockableBlockService.loadForWorld(server, worldId);
            });

            // Restore in-flight bot travels that were persisted before shutdown.
            net.wcfcarolina13.GameAI.services.NavigationArtifactService.loadPendingTravels(server);
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            net.wcfcarolina13.GameAI.services.TaskService.markServerStopping();
            // Shut down all mod executor services BEFORE saving — prevents worker threads
            // from submitting new server.execute() tasks that keep the shutdown loop alive.
            net.wcfcarolina13.PlayerUtils.MiningTool.shutdownExecutors();
            net.wcfcarolina13.GameAI.services.BotIdleHobbiesService.shutdownExecutors();
            net.wcfcarolina13.GameAI.BotEventHandler.shutdownExecutors();
            net.wcfcarolina13.Commands.modCommandRegistry.shutdownExecutors();
            net.wcfcarolina13.GameAI.services.BotAutoReturnSunsetService.shutdownExecutors();
            net.wcfcarolina13.GameAI.services.BotAutoHuntService.shutdownExecutors();
            net.wcfcarolina13.GameAI.services.BotUndergroundSurvivalService.shutdownExecutors();
            net.wcfcarolina13.GameAI.services.MovementService.shutdownExecutors();
            AutoFaceEntity.onServerStopping(server);
            LearningModeService.onServerStopping(server);
            net.wcfcarolina13.GameAI.services.CompanionOverheadHologramService.removeAll();
            net.wcfcarolina13.GameAI.services.LockableBlockService.saveAllWorlds(server);
            net.wcfcarolina13.GameAI.services.TaskService.resetAll("§cServer stopping; aborting active tasks.");
            for (ServerPlayerEntity bot : server.getPlayerManager().getPlayerList()) {
                if (!(bot instanceof net.wcfcarolina13.Entity.createFakePlayer)) {
                    continue;
                }
                if (bot.hasVehicle()) {
                    net.minecraft.entity.Entity vehicle = bot.getVehicle();
                    // See BotPersistenceService.onBotDisconnect for rationale.
                    // Vanilla RootVehicle isn't written for fake players —
                    // always dismount + mark persistent so the horse lives in
                    // chunk data, and record wasMounted for rejoin remount.
                    if (vehicle instanceof net.minecraft.entity.mob.MobEntity mob) {
                        mob.setPersistent();
                    }
                    net.wcfcarolina13.GameAI.services.RideSyncService.SecureResult secureResult =
                            net.wcfcarolina13.GameAI.services.RideSyncService.trySecureMountBeforeDismount(bot, vehicle);
                    boolean shouldRemountOnRejoin = secureResult == net.wcfcarolina13.GameAI.services.RideSyncService.SecureResult.CANNOT_SECURE;
                    bot.stopRiding();
                    net.wcfcarolina13.GameAI.services.MountPersistenceService.recordMount(bot, vehicle, shouldRemountOnRejoin);
                } else {
                    net.wcfcarolina13.GameAI.services.RideSyncService.secureLeashedMountOnDisconnect(bot);
                }
            }
            BotPersistenceService.saveAll(server);
            net.wcfcarolina13.GameAI.services.NavigationArtifactService.clearSmokeSignalCache();
            net.wcfcarolina13.GameAI.services.DurabilityFallbackService.shutdownExecutors();
            net.wcfcarolina13.GameAI.services.BotAnimalDefenseService.reset();
            net.wcfcarolina13.GameAI.services.BotPillagerAlertService.reset();
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            BotEventHandler.resetAll();
            AutoFaceEntity.onServerStopped(server);
            // Integrated-server world reloads keep mod static state alive. Ensure task locks don't leak across reloads.
            net.wcfcarolina13.GameAI.services.TaskService.resetAll("§cServer stopped; clearing task state.");
            // Integrated-server world reloads also keep scheduler state alive; clear idle-hobby backoff so
            // "idle hobbies = on" resumes automatically when re-entering the world.
            net.wcfcarolina13.GameAI.services.BotIdleHobbiesService.resetSession();
            // Clear ambient chatter scheduler state too.
            BotAmbientChatter.resetSession();
            // Clear mood manager state.
            BotMoodManager.resetSession();
            // Clear quest runtime session state (quest memory persists in config).
            BotQuestService.resetSession();
            if (modelManager != null) {
                try {
                    if (modelManager.isModelLoaded() || loadedBERTModelIntoMemory) {
                        modelManager.unloadModel();
                        System.out.println("Unloaded BERT Model from memory");
                    } else {
                        System.out.println("BERT Model was not loaded, skipping unloading...");
                    }
                } catch (IOException e) {
                    LOGGER.error("BERT Model unloading failed!", e);
                }
            }
            MODEL_LOAD_ENQUEUED.set(false);
            net.wcfcarolina13.GameAI.services.BotControlApplier.resetSession();
            // Flush pending bot travels to disk, then clear in-memory state.
            net.wcfcarolina13.GameAI.services.NavigationArtifactService.resetSession();
            // Clear Soul of Ender timed buffs.
            net.wcfcarolina13.GameAI.services.SoulOfEnderService.resetSession();
            // Clear RideSync tick-based state. Integrated-server world reloads
            // reset server.getTicks() to 0, but static tick-based cooldown
            // maps in RideSyncService would otherwise retain huge tick values
            // from the previous session — causing cooldownReady() to return
            // false for ~12 minutes and silently blocking every /follow
            // mount attempt after reload.
            net.wcfcarolina13.GameAI.services.RideSyncService.resetSession();
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.player;
            BotPersistenceService.onBotJoin(player);

            // Sync survival recruitment state to real players on join.
            if (!(player instanceof net.wcfcarolina13.Entity.createFakePlayer)) {
                net.wcfcarolina13.GameAI.services.SurvivalRecruitmentService.sendRecruitmentState(player);
                // Deliver any travel notifications queued while this player was offline.
                net.wcfcarolina13.GameAI.services.NavigationArtifactService.drainQueuedNotifications(player);
            }
            
            // Check if bot spawned in a wall and needs to dig out
            if (BotEventHandler.isRegisteredBot(player)) {
                // Use a scheduled task that runs on the server thread (sync)
                // This avoids Thread.sleep() issues and ensures proper timing
                scheduleSpawnEscapeCheck(server, player);
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity player = handler.player;
            LearningModeService.onPlayerDisconnect(player);
            BotPersistenceService.onBotDisconnect(player);
            net.wcfcarolina13.network.ZoneNetworkManager.clearPendingCorner(player.getUuid());
            net.wcfcarolina13.GameAI.services.ZoneVisualizerService.onPlayerDisconnect(player.getUuid());
            net.wcfcarolina13.GameAI.services.LockableBlockService.clearLockMode(player.getUuid());
            if (!(player instanceof net.wcfcarolina13.Entity.createFakePlayer) && !server.isDedicated()) {
                BotPersistenceService.saveBotsBeforeShutdown(server);
            }
        });

        // Register damage event to handle suffocation immediately
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayerEntity realPlayer && !(realPlayer instanceof net.wcfcarolina13.Entity.createFakePlayer)) {
                LearningModeService.onPlayerDamage(realPlayer, source, amount);
            }
            if (entity instanceof ServerPlayerEntity serverPlayer && BotEventHandler.isRegisteredBot(serverPlayer)) {
                // Safety net: bots should never be permanently invulnerable
                if (serverPlayer.isInvulnerable()) {
                    serverPlayer.setInvulnerable(false);
                }

                // Notify mood manager of damage (triggers STRESSED state)
                BotMoodManager.noteDamage(serverPlayer);

                // Environmental damage clears shelter — bot needs to react
                if (source != null && (source.isOf(DamageTypes.EXPLOSION)
                        || source.isOf(DamageTypes.PLAYER_EXPLOSION)
                        || source.isOf(DamageTypes.DROWN)
                        || source.isOf(DamageTypes.IN_WALL)
                        || source.isOf(DamageTypes.LAVA)
                        || source.isOf(DamageTypes.ON_FIRE)
                        || source.isOf(DamageTypes.HOT_FLOOR))) {
                    net.wcfcarolina13.GameAI.services.BotFleeService.clearShelter(serverPlayer.getUuid());
                }

                // Handle damage callouts based on attacker type
                if (source != null && source.getAttacker() != null && source.getAttacker() != serverPlayer) {
                    Entity attacker = source.getAttacker();
                    if (attacker instanceof ServerPlayerEntity playerAttacker && !playerAttacker.isRemoved()) {
                        // Player hit the bot - use combat callout (not touch dialogue!)
                        net.wcfcarolina13.GameAI.services.BotCombatCalloutService.onPlayerHit(serverPlayer, playerAttacker, amount);
                    } else if (attacker instanceof net.minecraft.entity.mob.HostileEntity) {
                        // Mob hit the bot - use damage taken callout
                        net.wcfcarolina13.GameAI.services.BotCombatCalloutService.onDamageTaken(serverPlayer, attacker, amount);
                        net.wcfcarolina13.GameAI.services.BotCombatCalloutService.noteHostileDamage(
                                serverPlayer, serverPlayer.getCommandSource().getServer().getTicks());
                        // Hostile damage clears shelter — bot needs to fight back
                        net.wcfcarolina13.GameAI.services.BotFleeService.clearShelter(serverPlayer.getUuid());
                    }
                }

                // If bot is taking IN_WALL damage, try to escape immediately
                // BUT: skip aggressive escape if bot is in ascent mode (digging upward)
                if (source != null && (source.isOf(DamageTypes.IN_WALL)
                    || source.isOf(DamageTypes.FLY_INTO_WALL)
                    || source.isOf(DamageTypes.CRAMMING))) {
                    // Record recent obstruction-like damage (2s window gating)
                    BotEventHandler.noteObstructDamage(serverPlayer);

                    if (source.isOf(DamageTypes.IN_WALL)) {
                        if (net.wcfcarolina13.GameAI.services.TaskService.isInAscentMode(serverPlayer.getUuid())) {
                            LOGGER.debug("Bot {} taking IN_WALL damage during ascent - skipping aggressive escape",
                                    serverPlayer.getName().getString());
                        } else {
                            MinecraftServer server = serverPlayer.getCommandSource().getServer();
                            if (server != null) {
                                // Only log warning once every 3 seconds to prevent flooding
                                UUID botUuid = serverPlayer.getUuid();
                                long now = server.getTicks();
                                long lastWarning = LAST_IN_WALL_WARNING_TICK.getOrDefault(botUuid, Long.MIN_VALUE);
                                
                                if (now - lastWarning >= IN_WALL_WARNING_COOLDOWN_TICKS) {
                                    LOGGER.warn("Bot {} taking IN_WALL damage - attempting immediate escape",
                                            serverPlayer.getName().getString());
                                    LAST_IN_WALL_WARNING_TICK.put(botUuid, now);
                                }
                                
                                server.execute(() -> {
                                    if (!serverPlayer.isRemoved()) {
                                        BotEventHandler.checkAndEscapeSuffocation(serverPlayer);
                                    }
                                });
                            }
                        }
                    }
                }
            }
            return true; // Allow the damage
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (entity instanceof ServerPlayerEntity serverPlayer) {
                if (BotEventHandler.isRegisteredBot(serverPlayer)) {
                        LOGGER.info("Detected bot death at ({}, {}, {}) damageSource={} alive={}",
                                serverPlayer.getX(), serverPlayer.getY(), serverPlayer.getZ(),
                                damageSource.getType(), serverPlayer.isAlive());
                        net.wcfcarolina13.GameAI.services.BotCombatCalloutService.resetCombatState(serverPlayer.getUuid());
                        net.wcfcarolina13.GameAI.services.BotFleeService.reset(serverPlayer.getUuid());
                        QTableStorage.saveLastKnownState(BotEventHandler.getCurrentState(), BotEventHandler.qTableDir + "/lastKnownState.bin");
                        BotEventHandler.botDied = true; // set flag for bot's death.
                        BotEventHandler.ensureRespawnHandled(serverPlayer);
                }
                BotPersistenceService.onBotDeath(serverPlayer);
                HuntSessionService.clearSession(serverPlayer.getUuid());
            }
            if (entity instanceof net.minecraft.entity.LivingEntity dead) {
                var attacker = damageSource != null ? damageSource.getAttacker() : null;
                if (attacker instanceof ServerPlayerEntity killer
                        && HuntCatalog.isFoodMob(dead.getType())) {
                    net.minecraft.util.Identifier huntId = net.minecraft.entity.EntityType.getId(dead.getType());
                    boolean isNew = HuntHistoryService.recordHunt(killer, huntId);
                    if (isNew && !BotEventHandler.isRegisteredBot(killer)) {
                        HuntCatalog.HuntTarget target = HuntCatalog.findByName(huntId.getPath());
                        String label = target != null ? target.label() : huntId.getPath();
                        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(
                                killer, new net.wcfcarolina13.network.HuntDiscoveryPayload(label));
                    }
                }
                // Combat callout + kill position tracking: bot killed something
                if (attacker instanceof ServerPlayerEntity killer2 && BotEventHandler.isRegisteredBot(killer2)) {
                    net.minecraft.util.math.Vec3d killPos = new net.minecraft.util.math.Vec3d(dead.getX(), dead.getY(), dead.getZ());
                    net.wcfcarolina13.GameAI.services.BotCombatCalloutService.noteKillPosition(killer2.getUuid(), killPos);
                    if (dead instanceof net.minecraft.entity.mob.HostileEntity) {
                        net.wcfcarolina13.GameAI.services.BotCombatCalloutService.onKill(killer2, dead);
                    }
                }
            }
        });

        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            // Check if the respawned player is the bot
            if (oldPlayer instanceof ServerPlayerEntity && newPlayer instanceof ServerPlayerEntity && BotEventHandler.isRegisteredBot(newPlayer)) {
                System.out.println("Bot has respawned. Updating state...");
                BotEventHandler.hasRespawned = true;
                BotEventHandler.botSpawnCount++;
                LOGGER.info("AFTER_RESPAWN fired for bot {} (alive flag={})", newPlayer.getName().getString(), alive);
                AutoFaceEntity.handleBotRespawn(newPlayer);
                BotEventHandler.onBotRespawn(newPlayer);
            }
            BotPersistenceService.onBotRespawn(oldPlayer, newPlayer, alive);
            BotEventHandler.ensureBotPresence(newPlayer.getCommandSource().getServer());
        });

        ServerTickEvents.END_SERVER_TICK.register(BotPersistenceService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(net.wcfcarolina13.GameAI.services.SurvivalRecruitmentService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(BotEventHandler::tickBurialRescue);
        ServerTickEvents.END_SERVER_TICK.register(BotEventHandler::tickHunger);
        ServerTickEvents.END_SERVER_TICK.register(BotEventHandler::tickDrowningRescue);
        ServerTickEvents.END_SERVER_TICK.register(BotEventHandler::tickDurabilityArmorAudit);
        ServerTickEvents.END_SERVER_TICK.register(Frens::processSpawnEscapeChecks);
        ServerTickEvents.END_SERVER_TICK.register(BotCampfireAvoidanceService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(net.wcfcarolina13.GameAI.services.BotAnimalDefenseService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(net.wcfcarolina13.GameAI.services.BotHazardService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(net.wcfcarolina13.GameAI.services.BotFallSafetyService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(net.wcfcarolina13.GameAI.services.BotAutoHuntService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(net.wcfcarolina13.GameAI.services.BotAutoCookingService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(BotAutoReturnSunsetService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(net.wcfcarolina13.GameAI.services.BotEmergencyRescueService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(net.wcfcarolina13.GameAI.services.BotUndergroundSurvivalService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(net.wcfcarolina13.network.HuntablesNetworkManager::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(BotIdleHobbiesService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(net.wcfcarolina13.GameAI.services.BotMutualAidService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(BotInventoryFullDialogueService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(BotAmbientSocialChatService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(BotMoodManager::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(BotAmbientChatter::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(BotQuestService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(net.wcfcarolina13.GameAI.services.RideSyncService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(net.wcfcarolina13.GameAI.services.BotCombatCalloutService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(net.wcfcarolina13.GameAI.services.VillageProximityReactionService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(net.wcfcarolina13.GameAI.services.PetProximityReactionService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(net.wcfcarolina13.GameAI.services.CompanionContextReactionService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(net.wcfcarolina13.GameAI.services.SweetBerryBushReactionService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(net.wcfcarolina13.GameAI.services.BotWakeUpDialogueService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(net.wcfcarolina13.GameAI.services.EnchantingAmbientDialogueService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(net.wcfcarolina13.GameAI.services.PlayerEatingReactionService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(net.wcfcarolina13.GameAI.services.CookingReactionService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(net.wcfcarolina13.GameAI.services.ElytraFlightService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(net.wcfcarolina13.GameAI.services.GhastFireballDeflectService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(net.wcfcarolina13.GameAI.services.CompanionOverheadHologramService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(LearningModeService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(NavigationArtifactService::tickPendingTravels);
        ServerTickEvents.END_SERVER_TICK.register(net.wcfcarolina13.GameAI.services.SoulOfEnderService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(net.wcfcarolina13.GameAI.services.ZoneVisualizerService::onServerTick);

        // Lock mode: crosshair feedback + particle visualization for admin players
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            int tick = server.getTicks();
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (player instanceof net.wcfcarolina13.Entity.createFakePlayer) continue;
                if (!net.wcfcarolina13.GameAI.services.LockableBlockService.isLockModeActive(player.getUuid())) continue;
                if (!(player.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld sw)) continue;
                if (tick % 10 == 0) {
                    net.wcfcarolina13.GameAI.services.LockableBlockService.tickCrosshairFeedback(player, sw);
                }
                if (tick % 20 == 0) {
                    net.wcfcarolina13.GameAI.services.LockableBlockService.tickParticles(player, sw);
                }
            }
        });

        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            String raw = message.getContent().getString();

            // Quest usability: allow a bare "quest"/"mission" ask to target the nearest bot.
            // This keeps the feature discoverable without requiring "<botname> quest".
            tryHandleNearbyQuestAsk(sender, raw);

            boolean consumed = false;
            if (OLLAMA4J_AVAILABLE) {
                try {
                    consumed = FunctionCallerV2.tryHandleConfirmation(sender.getUuid(), raw);
                } catch (Throwable t) {
                    // Don't let optional AI wiring break chat (or quests).
                    if (WARNED_MISSING_OLLAMA4J.compareAndSet(false, true)) {
                        LOGGER.warn("FunctionCallerV2 unavailable; skipping confirmation handling ({}: {})", t.getClass().getSimpleName(), t.getMessage());
                    }
                }
            }
            if (consumed) {
                return;
            }

            SkillResumeService.handleChat(sender, raw);

            ChatTarget target = resolveChatTargets(raw);
            if (!target.bots().isEmpty()) {
                List<ServerPlayerEntity> routedBots = dedupeTargetBots(target.bots());
                // Fast-path: local quest system (no LLM).
                if (!target.prompt().isEmpty()) {
                    boolean questHandled = false;
                    for (ServerPlayerEntity bot : routedBots) {
                        if (bot == null) {
                            continue;
                        }
                        questHandled |= BotQuestService.tryHandleQuestPrompt(bot, sender, target.prompt());
                    }
                    if (questHandled) {
                        return;
                    }
                }

                boolean handled = false;
                for (ServerPlayerEntity bot : routedBots) {
                    if (target.prompt().isEmpty()) {
                        continue;
                    }
                    ServerCommandSource botSource = bot.getCommandSource().withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS);
                    ChatUtils.sendChatMessages(botSource, "Processing your message, please wait.");
                    handled |= LLMOrchestrator.handleChat(
                            bot,
                            botSource,
                            sender.getUuid(),
                            target.prompt());
                }
                if (handled) {
                    return;
                }

                // Compatibility fallback for targeted single-bot action prompts.
                // Keep this inside explicit-target routing so the raw parser doesn't process the same line again.
                if (routedBots.size() == 1 && !target.prompt().isEmpty()) {
                    handleLegacyInlineActionPrompt(routedBots.get(0), sender, target.prompt());
                }
                return;
            }

            handleLegacyInlineActionFromRaw(raw, sender);
        });
    }

    private static void tryHandleNearbyQuestAsk(ServerPlayerEntity sender, String raw) {
        if (serverInstance == null || sender == null || sender.isRemoved()) {
            return;
        }
        if (BotEventHandler.isRegisteredBot(sender)) {
            return;
        }
        if (!looksLikeQuestAsk(raw)) {
            return;
        }

        // If message already explicitly targets bots ("Jake quest", "bots quest"), normal routing will handle it.
        ChatTarget explicit = resolveChatTargets(raw);
        if (!explicit.bots().isEmpty()) {
            return;
        }

        // Route to nearest registered bot within a small radius.
        final double maxDistSq = 10.0D * 10.0D;
        ServerPlayerEntity nearest = null;
        double best = Double.POSITIVE_INFINITY;
        for (ServerPlayerEntity bot : BotEventHandler.getRegisteredBots(serverInstance)) {
            if (bot == null || bot.isRemoved()) {
                continue;
            }
            if (bot.getEntityWorld() != sender.getEntityWorld()) {
                continue;
            }
            double d = bot.squaredDistanceTo(sender);
            if (d <= maxDistSq && d < best) {
                best = d;
                nearest = bot;
            }
        }
        if (nearest == null) {
            return;
        }

        // Let the quest service decide if it should respond.
        BotQuestService.tryHandleQuestPrompt(nearest, sender, raw);
    }

    private static boolean looksLikeQuestAsk(String raw) {
        if (raw == null) {
            return false;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return false;
        }
        return normalized.equals("quest")
                || normalized.equals("sidequest")
                || normalized.equals("side quest")
                || normalized.equals("mission")
                || normalized.startsWith("quest ")
                || normalized.startsWith("sidequest ")
                || normalized.startsWith("side quest ")
                || normalized.startsWith("got a quest")
                || normalized.startsWith("any quests")
                || normalized.startsWith("give me a quest")
                || normalized.startsWith("give us a quest");
    }

    private static void enqueueBertLoad() {
        if (modelManager == null) {
            LOGGER.info("Skipping BERT load (DJL unavailable).");
            return;
        }
        if (!MODEL_LOAD_ENQUEUED.compareAndSet(false, true)) {
            return;
        }
        LOGGER.info("Queueing asynchronous BERT model load...");
        CompletableFuture.runAsync(() -> {
            LOGGER.info("Proceeding to load BERT model into memory");
            try {
                modelManager.loadModel();
                loadedBERTModelIntoMemory = true;
                LOGGER.info("BERT model loaded into memory. It will stay in memory as long as any bot stays active in game.");
            } catch (Throwable t) {
                loadedBERTModelIntoMemory = false;
                LOGGER.warn("⚠️ BERT unavailable (continuing without it): {}", t.toString());
            }
        }, MODEL_EXECUTOR).whenComplete((ignored, error) -> {
            if (error != null) {
                LOGGER.warn("Asynchronous BERT load failed: {}", error.getMessage());
                loadedBERTModelIntoMemory = false;
                MODEL_LOAD_ENQUEUED.set(false);
            }
        });
    }

    private static ChatTarget resolveChatTargets(String raw) {
        if (serverInstance == null || raw == null) {
            return new ChatTarget(List.of(), "");
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return new ChatTarget(List.of(), "");
        }
        String[] tokens = trimmed.split("\\s+");
        if (tokens.length == 0) {
            return new ChatTarget(List.of(), "");
        }
        List<ServerPlayerEntity> bots = BotEventHandler.getRegisteredBots(serverInstance);
        if (bots.isEmpty()) {
            return new ChatTarget(List.of(), "");
        }
        int consumed = -1;
        List<ServerPlayerEntity> targets = new ArrayList<>();
        for (int i = 0; i < tokens.length; i++) {
            String current = normalizeToken(tokens[i]);
            if (current.isEmpty()) {
                continue;
            }
            if (current.equals("allbots") || current.equals("bots")) {
                targets.addAll(bots);
                consumed = i + 1;
                break;
            }
            if (current.equals("all") && i + 1 < tokens.length) {
                String next = normalizeToken(tokens[i + 1]);
                if (next.equals("bots")) {
                    targets.addAll(bots);
                    consumed = i + 2;
                    break;
                }
            }
            for (ServerPlayerEntity bot : bots) {
                if (normalizeToken(bot.getName().getString()).equals(current)) {
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
            return new ChatTarget(List.of(), "");
        }
        targets = dedupeTargetBots(targets);
        if (targets.isEmpty()) {
            return new ChatTarget(List.of(), "");
        }
        if (consumed >= tokens.length) {
            return new ChatTarget(targets, "");
        }
        String prompt = String.join(" ", Arrays.copyOfRange(tokens, consumed, tokens.length)).trim();
        return new ChatTarget(targets, prompt);
    }

    private static List<ServerPlayerEntity> dedupeTargetBots(List<ServerPlayerEntity> bots) {
        if (bots == null || bots.isEmpty()) {
            return List.of();
        }
        Map<UUID, ServerPlayerEntity> deduped = new LinkedHashMap<>();
        for (ServerPlayerEntity bot : bots) {
            if (bot == null || bot.isRemoved()) {
                continue;
            }
            deduped.putIfAbsent(bot.getUuid(), bot);
        }
        return new ArrayList<>(deduped.values());
    }

    private static void handleLegacyInlineActionFromRaw(String raw, ServerPlayerEntity sender) {
        if (raw == null || raw.isBlank() || sender == null || serverInstance == null) {
            return;
        }
        String[] parts = raw.split(" ");
        if (parts.length < 2) {
            return;
        }
        String botName = parts[0];
        ServerPlayerEntity bot = serverInstance.getPlayerManager().getPlayer(botName);
        if (!BotEventHandler.isRegisteredBot(bot) || bot == null) {
            return;
        }
        String userPrompt = String.join(" ", Arrays.copyOfRange(parts, 1, parts.length));
        handleLegacyInlineActionPrompt(bot, sender, userPrompt);
    }

    private static void handleLegacyInlineActionPrompt(ServerPlayerEntity bot, ServerPlayerEntity sender, String userPrompt) {
        if (bot == null || sender == null || userPrompt == null || userPrompt.isBlank()) {
            return;
        }
        NLPProcessor.Intent intent = NLPProcessor.getIntention(userPrompt);
        if (intent != NLPProcessor.Intent.REQUEST_ACTION) {
            return;
        }
        if (!OLLAMA4J_AVAILABLE) {
            if (WARNED_MISSING_OLLAMA4J.compareAndSet(false, true)) {
                LOGGER.warn("AI action handling requested but ollama4j is not available; ignoring inline action prompt.");
            }
            return;
        }
        try {
            // Constructor sets thread-local context used by the static run(..) entrypoint.
            new FunctionCallerV2(bot.getCommandSource(), sender.getUuid());
            FunctionCallerV2.run(userPrompt);
        } catch (Throwable t) {
            if (WARNED_MISSING_OLLAMA4J.compareAndSet(false, true)) {
                LOGGER.warn("AI action handling failed ({}: {})", t.getClass().getSimpleName(), t.getMessage());
            }
        } finally {
            try {
                FunctionCallerV2.clearContext();
            } catch (Throwable ignored) {
            }
        }
    }

    public static boolean isDjlAvailable() {
        return djlAvailable;
    }

    private static boolean isClassAvailable(String className) {
        try {
            Class.forName(className, false, Frens.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String normalizeToken(String token) {
        if (token == null) {
            return "";
        }
        String cleaned = token.replaceAll("[^a-zA-Z0-9]", "");
        return cleaned.toLowerCase(Locale.ROOT);
    }

    /**
     * Schedules a tick-based escape check for bots that spawn inside walls.
     * Uses the server tick event to check periodically without blocking.
     */
    private static void scheduleSpawnEscapeCheck(MinecraftServer server, ServerPlayerEntity bot) {
        SPAWN_ESCAPE_CHECKS.put(bot.getUuid(), new SpawnEscapeCheck());
    }
    
    /**
     * Processes pending spawn escape checks. Called every server tick.
     */
    private static void processSpawnEscapeChecks(MinecraftServer server) {
        if (SPAWN_ESCAPE_CHECKS.isEmpty()) {
            return;
        }
        
        SPAWN_ESCAPE_CHECKS.entrySet().removeIf(entry -> {
            UUID botId = entry.getKey();
            SpawnEscapeCheck check = entry.getValue();
            
            ServerPlayerEntity bot = (ServerPlayerEntity) server.getPlayerManager().getPlayer(botId);
            if (bot == null || bot.isRemoved()) {
                return true; // Remove check - bot is gone
            }
            
            check.ticksWaited++;
            
            // Start checking after 20 ticks (1 second) to let spawn complete
            if (check.ticksWaited < 20) {
                return false; // Keep checking
            }
            
            // Try escape every 10 ticks (0.5 seconds)
            if ((check.ticksWaited - 20) % 10 == 0 && check.attemptsRemaining > 0) {
                check.attemptsRemaining--;
                int attemptNum = 5 - check.attemptsRemaining;
                
                if (BotEventHandler.checkAndEscapeSuffocation(bot)) {
                    LOGGER.info("Bot {} escaped suffocation on attempt {}", bot.getName().getString(), attemptNum);
                    return true; // Success - remove check
                }
                
                if (check.attemptsRemaining == 0) {
                    LOGGER.warn("Bot {} failed to escape after 5 attempts", bot.getName().getString());
                    return true; // Give up - remove check
                }
            }
            
            // Stop after 70 ticks (3.5 seconds) total
            if (check.ticksWaited > 70) {
                return true; // Timeout - remove check
            }
            
            return false; // Keep checking
        });
    }

    private record ChatTarget(List<ServerPlayerEntity> bots, String prompt) {
    }
}
