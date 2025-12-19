package net.shasankp000.Commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.block.BlockState;
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
import net.shasankp000.CommandUtils;
import net.shasankp000.GameAI.llm.LLMOrchestrator;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.DangerZoneDetector.DangerZoneDetector;
import net.shasankp000.Database.QTableExporter;
import net.shasankp000.Entity.AutoFaceEntity;
import net.shasankp000.Entity.LookController;
import net.shasankp000.Entity.RayCasting;
import net.shasankp000.Entity.RespawnHandler;
import net.shasankp000.Entity.createFakePlayer;
import net.shasankp000.FilingSystem.LLMClientFactory;
import net.shasankp000.FilingSystem.ManualConfig;
import net.shasankp000.AIPlayer;
import net.shasankp000.GameAI.BotEventHandler;
import net.shasankp000.GameAI.services.BotPersistenceService;
import net.shasankp000.GameAI.State;
import net.shasankp000.GameAI.StateActions;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import net.shasankp000.OllamaClient.ollamaClient;
import net.shasankp000.PathFinding.ChartPathToBlock;
import net.shasankp000.PathFinding.PathFinder;
import net.shasankp000.PathFinding.PathTracer;
import net.shasankp000.PathFinding.Segment;
import net.shasankp000.PlayerUtils.*;
import net.shasankp000.GameAI.skills.SkillManager;
import net.shasankp000.GameAI.skills.SkillPreferences;
import net.shasankp000.GameAI.services.BotInventoryStorageService;
import net.shasankp000.GameAI.services.BotTargetingService;
import net.shasankp000.GameAI.services.HealingService;
import net.shasankp000.GameAI.services.InventoryAccessPolicy;
import net.shasankp000.GameAI.services.MovementService;
import net.shasankp000.GameAI.services.CraftingHelper;
import net.shasankp000.GameAI.services.SmeltingService;
import net.shasankp000.GameAI.services.ProtectedZoneService;
import net.shasankp000.GameAI.services.SkillResumeService;
import net.shasankp000.GameAI.services.TaskService;
import net.shasankp000.GameAI.services.WorkDirectionService;
import net.shasankp000.GameAI.skills.SkillContext;
import net.shasankp000.GameAI.skills.SkillExecutionResult;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.GameAI.services.ChestStoreService;
import net.shasankp000.GameAI.services.DebugToggleService;
import net.shasankp000.ui.BotInventoryAccess;
import net.shasankp000.FunctionCaller.FunctionCallerV2;
import net.shasankp000.ServiceLLMClients.LLMClient;
import net.shasankp000.ServiceLLMClients.LLMServiceHandler;
import net.shasankp000.WorldUitls.isFoodItem;
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


import static net.shasankp000.PathFinding.PathFinder.*;
import static net.minecraft.server.command.CommandManager.literal;
import net.shasankp000.PacketHandler.InputPacketHandler;

public class modCommandRegistry {

    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private static final ExecutorService skillExecutor = Executors.newCachedThreadPool();
    static final double DEFAULT_GUARD_RADIUS = 6.0D;
    public static boolean isTrainingMode = false;
    public static boolean enableReinforcementLearning = false;
    public static String botName = "";
    public static final Logger LOGGER = LoggerFactory.getLogger("mod-command-registry");


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
	                        .then(literal("spawn")
                                .then(CommandManager.argument("bot_name", StringArgumentType.string())
                                        .then(CommandManager.argument("mode", StringArgumentType.string())
                                                .executes(context -> {
                                                    String botName = StringArgumentType.getString(context, "bot_name");
                                                    String spawnMode = StringArgumentType.getString(context, "mode");
                                                    try {
                                                        LOGGER.info("Executing /bot spawn {} {}", botName, spawnMode);
                                                        spawnBot(context, spawnMode);
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
	                        .then(BotSkillCommands.buildShelter())
	                        .then(BotLifecycleCommands.buildList())
	                        .then(BotLifecycleCommands.buildDespawn())
	                        .then(BotLifecycleCommands.buildStop())
	                        .then(BotLifecycleCommands.buildResume())
	                        .then(BotLifecycleCommands.buildHeal())
	                        .then(BotUtilityCommands.buildDirection())
	                        .then(BotUtilityCommands.buildZone())
	                        .then(BotUtilityCommands.buildLookPlayer())
	                        .then(BotUtilityCommands.buildFollow())
	                        .then(BotMovementCommands.buildCome())
	                        .then(BotMovementCommands.buildRegroup())
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
                                .executes(context -> executeCook(context, getActiveBotOrThrow(context), null, false))
                                .then(CommandManager.argument("item", StringArgumentType.string())
                                        .executes(context -> executeCook(context, getActiveBotOrThrow(context), StringArgumentType.getString(context, "item"), false))
                                        .then(CommandManager.argument("fuel", BoolArgumentType.bool())
                                                .executes(context -> executeCook(context, getActiveBotOrThrow(context),
                                                        StringArgumentType.getString(context, "item"),
                                                        BoolArgumentType.getBool(context, "fuel")))
                                                .then(CommandManager.argument("bot", EntityArgumentType.player())
                                                        .executes(context -> executeCook(context, EntityArgumentType.getPlayer(context, "bot"),
                                                                StringArgumentType.getString(context, "item"),
                                                                BoolArgumentType.getBool(context, "fuel"))))))
                                .then(CommandManager.argument("fuel", BoolArgumentType.bool())
                                        .executes(context -> executeCook(context, getActiveBotOrThrow(context), null,
                                                BoolArgumentType.getBool(context, "fuel")))
                                        .then(CommandManager.argument("bot", EntityArgumentType.player())
                                                .executes(context -> executeCook(context, EntityArgumentType.getPlayer(context, "bot"), null,
                                                        BoolArgumentType.getBool(context, "fuel")))))
                                .then(CommandManager.argument("bot", EntityArgumentType.player())
                                        .executes(context -> executeCook(context, EntityArgumentType.getPlayer(context, "bot"), null, false)))
                        )
                        .then(literal("store")
                                .then(literal("deposit")
                                        .then(CommandManager.argument("amount", StringArgumentType.string())
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
                                        .then(CommandManager.argument("amount", StringArgumentType.string())
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
                                        net.shasankp000.Database.QTable qTable = new net.shasankp000.Database.QTable();
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
                                                            turnTool.turn(bot.getCommandSource().withSilent().withMaxLevel(4), direction);

                                                            LOGGER.info("Now facing {} which is in {} in {} axis", direction, bot.getFacing().getId(), bot.getFacing().getAxis().getId());
                                                        }
                                                        default -> {
                                                            server.execute(() -> {
                                                                ChatUtils.sendChatMessages(bot.getCommandSource().withSilent().withMaxLevel(4), "Invalid parameters! Accepted parameters: left, right, back only!");
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
                                                ChatUtils.sendChatMessages(bot.getCommandSource().withSilent().withMaxLevel(4), "Autoface module reset complete.");
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
                                                                    ServerCommandSource botSource = bot.getCommandSource().withSilent().withMaxLevel(4);
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
                                            ServerCommandSource botSource = bot.getCommandSource().withSilent().withMaxLevel(4);

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

                                            ServerCommandSource botSource = bot.getCommandSource().withSilent().withMaxLevel(4);

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

                                            ServerCommandSource botSource = bot.getCommandSource().withSilent().withMaxLevel(4);

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

                                            ServerCommandSource botSource = bot.getCommandSource().withSilent().withMaxLevel(4);

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

                                            ServerCommandSource botSource = bot.getCommandSource().withSilent().withMaxLevel(4);

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

                                            ServerCommandSource botSource = bot.getCommandSource().withSilent().withMaxLevel(4);

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

                        .then(literal("stopAllMovementTasks")

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
                                            AIPlayer.CONFIG.removeBotEntry(alias);
                                            AIPlayer.CONFIG.save();

                                            context.getSource().sendFeedback(() -> Text.literal("§aBot '" + alias + "' has been forgotten."), false);
                                            LOGGER.info("Bot '{}' (UUID {}) has been forgotten and its data deleted.", alias, bot.getUuid());
                                            return 1;
                                        }))
                        )
                                .executes(context -> {

                                    MinecraftServer server = context.getSource().getServer(); // gets the minecraft server
                                    ServerCommandSource serverSource = server.getCommandSource();
                                    PathTracer.flushAllMovementTasks();

                                    ChatUtils.sendSystemMessage(serverSource, "Flushed all movement tasks");

                                    return 1;

                                })
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
                                .then(CommandManager.argument("alias", StringArgumentType.string())
                                        .executes(ctx -> {
                                            ServerCommandSource source = ctx.getSource();
                                            ServerPlayerEntity viewer = source.getPlayer(); // returns null if console; handle below
                                            if (viewer == null) {
                                                source.sendError(Text.literal("Run from a player, not console."));
                                                return 0;
                                            }

                                            String alias = StringArgumentType.getString(ctx, "alias");
                                            // Use existing targeting service if available:
                                            ServerPlayerEntity bot = source.getServer().getPlayerManager().getPlayer(alias);
                                            if (bot == null) {
                                                source.sendError(Text.literal("Bot not found: " + alias));
                                                return 0;
                                            }

                                            // Ownership / op check (see Section 3)
                                            if (!InventoryAccessPolicy.canOpen(viewer, bot)) {
                                                source.sendError(Text.literal("You don't have permission to open this bot's inventory."));
                                                return 0;
                                            }

                                            boolean ok = BotInventoryAccess.openBotInventory(viewer, bot);
                                            if (!ok) {
                                                source.sendError(Text.literal("Out of range or wrong dimension."));
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
        });
    }


    private static void spawnBot(CommandContext<ServerCommandSource> context, String spawnMode) {
        try {
            MinecraftServer server = context.getSource().getServer(); // gets the minecraft server
            BlockPos spawnPos = getBlockPos(context);

            RegistryKey<World> dimType = context.getSource().getWorld().getRegistryKey();

            Vec2f facing = context.getSource().getRotation();

            // Center the bot in the block space to avoid corner collisions
            Vec3d pos = new Vec3d(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);

            GameMode mode = GameMode.SURVIVAL;

            botName = StringArgumentType.getString(context, "bot_name");

            ServerCommandSource serverSource = server.getCommandSource();

            LOGGER.info("spawnBot starting: botName={}, mode={}, dimType={}, pos={}, facingYaw={}, facingPitch={}",
                    botName, spawnMode, dimType.getValue(), pos, facing.y, facing.x);

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

                isTrainingMode = "training".equalsIgnoreCase(spawnMode);

                existingBot.teleport(targetWorld, pos.x, pos.y, pos.z, java.util.Set.of(), (float) facing.y, (float) facing.x, true);
                Objects.requireNonNull(existingBot.getAttributeInstance(EntityAttributes.KNOCKBACK_RESISTANCE)).setBaseValue(0.0);
                existingBot.interactionManager.changeGameMode(mode);
                RespawnHandler.registerRespawnListener(existingBot);
                BotEventHandler.registerBot(existingBot);
                ServerPlayerEntity owner = context.getSource().getEntity() instanceof ServerPlayerEntity player ? player : null;
                if (owner != null) {
                    AIPlayer.CONFIG.ensureOwner(botName, owner.getUuid(), owner.getName().getString());
                }
                AutoFaceEntity.startAutoFace(existingBot);

                BotEventHandler.rememberSpawn(targetWorld, pos, facing.y, facing.x);
                LOGGER.info("spawnBot: repositioned existing bot {} at {} (mode={})", botName, spawnPos.toShortString(), spawnMode);
                return;
            }

            if (spawnMode.equals("training")) {

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
                        AIPlayer.CONFIG.ensureOwner(botName, owner.getUuid(), owner.getName().getString());
                    }

                    AutoFaceEntity.startAutoFace(bot);

                } else {
                    LOGGER.error("spawnBot: training bot {} was not found after createFake", botName);
                    ChatUtils.sendSystemMessage(serverSource, "Error: " + botName + " cannot be spawned");
                }

                // don't initialize ollama client for training mode.

            } else if (spawnMode.equals("play")) {
                LOGGER.info("spawnBot: entering play branch for {}", botName);

                isTrainingMode = false;
                LOGGER.info("Training mode disabled for play spawn.");

                LOGGER.info("About to call createFakePlayer.createFake for play bot {}", botName);
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
                LOGGER.info("Returned from createFakePlayer.createFake for play bot {}", botName);

                ServerWorld spawnWorld = server.getWorld(dimType);
                if (spawnWorld != null) {
                    LOGGER.info("spawnBot: remembering spawn for play bot {}", botName);
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
                        AIPlayer.CONFIG.ensureOwner(botName, owner.getUuid(), owner.getName().getString());
                    }

                    ollamaClient.botName = botName; // set the bot's name.

                    DebugToggleService.debug(LOGGER, "Set bot's username to {}", botName);

                    String llmProvider = System.getProperty("aiplayer.llmMode", "ollama");

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
                            LLMServiceHandler.sendInitialResponse(bot.getCommandSource().withSilent().withMaxLevel(4), llmClient);

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
                                    ollamaClient.sendInitialResponse(bot.getCommandSource().withSilent().withMaxLevel(4));
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
                                    ollamaClient.sendInitialResponse(bot.getCommandSource().withSilent().withMaxLevel(4));
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

            } else {
                LOGGER.warn("spawnBot: invalid spawn mode '{}' for bot {}", spawnMode, botName);
                ChatUtils.sendSystemMessage(serverSource, "Invalid spawn mode!");
                ChatUtils.sendSystemMessage(serverSource,
                        "Usage: /bot spawn <your bot's name> <spawnMode: training or play>");
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

            ServerCommandSource botSource = bot.getCommandSource().withLevel(2).withSilent().withMaxLevel(4);
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

            ServerCommandSource botSource = bot.getCommandSource().withMaxLevel(4).withSilent();
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

    private static int executeCook(CommandContext<ServerCommandSource> context, ServerPlayerEntity bot, String itemFilter, boolean useFuel) {
        if (bot == null) {
            return 0;
        }
        boolean started = SmeltingService.startBatchCook(bot, context.getSource(), itemFilter, useFuel);
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

        String botName = bot.getName().getLiteralString();
        ServerCommandSource botSource = bot.getCommandSource().withLevel(2).withSilent().withMaxLevel(4);

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
            ChatUtils.sendChatMessages(source.withSilent().withMaxLevel(4), sb.toString());
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
            ChatUtils.sendChatMessages(bot.getCommandSource().withSilent().withMaxLevel(4),
                    "You need to specify an item id, e.g., iron_ingot");
            return 0;
        }

        Item item = resolveItemFromQuery(itemQuery);
        if (item == null || item == Items.AIR) {
            ChatUtils.sendChatMessages(bot.getCommandSource().withSilent().withMaxLevel(4),
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
            ChatUtils.sendChatMessages(bot.getCommandSource().withSilent().withMaxLevel(4), "I don't have that");
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
            ChatUtils.sendChatMessages(bot.getCommandSource().withSilent().withMaxLevel(4), "I don't have that");
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
        ChatUtils.sendChatMessages(bot.getCommandSource().withSilent().withMaxLevel(4),
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

            ChatUtils.sendChatMessages(bot.getCommandSource().withSilent().withMaxLevel(4),
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
        BotEventHandler.setFollowMode(bot, target);
        return 1;
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
            successes += executeCome(context, bot, commander);
        }
        if (!bots.isEmpty() && successes > 0) {
            String summary = formatBotList(bots, isAll);
            String verb = (isAll || bots.size() > 1) ? "are" : "is";
            ChatUtils.sendSystemMessage(context.getSource(), summary + " " + verb + " heading to your last location.");
        }
        return successes;
    }

    private static int executeCome(CommandContext<ServerCommandSource> context, ServerPlayerEntity bot, ServerPlayerEntity commander) {
        if (bot == null || commander == null) {
            return 0;
        }
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
            BlockPos goal = commander.getBlockPos().toImmutable();
            BotEventHandler.setComeModeWalk(bot, commander, goal, 3.2D);
            return 1;
        }

        MovementService.MovementPlan plan = new MovementService.MovementPlan(
                MovementService.Mode.DIRECT,
                commander.getBlockPos(),
                commander.getBlockPos(),
                null,
                null,
                bot.getHorizontalFacing());
        MovementService.MovementResult result = MovementService.execute(bot.getCommandSource(), bot, plan, false);
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
                bot.getEntityWorld().breakBlock(placePos, false);
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
        BotEventHandler.stopFollowing(bot);
        return 1;
    }

    static int executeGuard(CommandContext<ServerCommandSource> context, ServerPlayerEntity bot, double radius) {
        rememberTarget(context.getSource(), bot);
        String result = BotEventHandler.setGuardMode(bot, radius);
        ChatUtils.sendSystemMessage(context.getSource(), result);
        return 1;
    }

    static int executePatrol(CommandContext<ServerCommandSource> context, ServerPlayerEntity bot, double radius) {
        rememberTarget(context.getSource(), bot);
        String result = BotEventHandler.setPatrolMode(bot, radius);
        ChatUtils.sendSystemMessage(context.getSource(), result);
        return 1;
    }

    static int executeStay(CommandContext<ServerCommandSource> context, ServerPlayerEntity bot) {
        rememberTarget(context.getSource(), bot);
        String result = BotEventHandler.setStayMode(bot);
        ChatUtils.sendSystemMessage(context.getSource(), result);
        return 1;
    }

    static int executeReturnToBase(CommandContext<ServerCommandSource> context, ServerPlayerEntity bot, ServerPlayerEntity commander) {
        rememberTarget(context.getSource(), bot);
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
            if (player instanceof net.shasankp000.Entity.createFakePlayer && !player.isRemoved() && player.isAlive()) {
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
        // Ensure follow state is cleared so the bot truly stops.
        net.shasankp000.GameAI.BotEventHandler.stopFollowing(bot);
        stopMoving(server, context.getSource(), alias);
        TaskService.forceAbort(bot.getUuid(), "§cCurrent task aborted via /bot stop.");
        net.shasankp000.PathFinding.PathTracer.flushAllMovementTasks();
        ChatUtils.sendSystemMessage(context.getSource(), "Stopping " + alias + "...");
        SkillResumeService.clear(bot.getUuid());
        return 1;
    }

    static int executeSkillTargets(CommandContext<ServerCommandSource> context, String skillName, String rawInput) throws CommandSyntaxException {
        SkillCommandInvocation invocation = parseSkillInvocation(context.getSource(), rawInput);
        List<ServerPlayerEntity> targets;
        try {
            targets = BotTargetingService.resolve(context.getSource(), invocation.target());
        } catch (CommandSyntaxException e) {
            // If no explicit/remembered bot target exists for this sender, fall back to the "active bot"
            // selection that many non-broadcast commands use (keeps /bot skill usable without requiring an alias).
            if (invocation.target() == null) {
                targets = List.of(getActiveBotOrThrow(context));
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
        
        // Record skill execution for resume capability
        SkillResumeService.recordExecution(bot, skillName, rawArgs, source);
        
        // Capture command issuer's facing direction for directional skills (stripmine, etc.)
        if (source.getPlayer() != null) {
            Direction issuerFacing = source.getPlayer().getHorizontalFacing();
            params.put("direction", issuerFacing);
        }

        skillExecutor.submit(() -> {
            try {
                SkillContext skillContext = new SkillContext(bot.getCommandSource(), FunctionCallerV2.getSharedState(), params);
                SkillExecutionResult result = SkillManager.runSkill(skillName, skillContext);
                source.getServer().execute(() -> ChatUtils.sendSystemMessage(source, result.message()));
            } catch (Exception e) {
                LOGGER.error("An unexpected error occurred in /bot skill " + skillName, e);
                source.getServer().execute(() -> ChatUtils.sendSystemMessage(source, "An unexpected error occurred trying to execute that command."));
            }
        });

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
        AIPlayer.CONFIG.setOwner(alias, ownership);
        AIPlayer.CONFIG.save();
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
        boolean isAdmin = source.hasPermissionLevel(2);
        
        boolean success = ProtectedZoneService.removeZone(world, label, player, isAdmin);
        if (!success) {
            source.sendError(Text.literal("Zone '" + label + "' not found or you don't have permission to remove it."));
            return 0;
        }
        
        source.sendFeedback(() -> Text.literal("§aRemoved protected zone '" + label + "'"), false);
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
            source.sendFeedback(() -> Text.literal(
                    "§7- §a" + zone.getLabel() + "§7: center=" + center.toShortString() + 
                    ", radius=" + zone.getRadius() + ", owner=" + zone.getOwnerName()), false);
        }
        
        return 1;
    }
}
