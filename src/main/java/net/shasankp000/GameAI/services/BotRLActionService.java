package net.shasankp000.GameAI.services;

import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.shasankp000.Entity.AutoFaceEntity;
import net.shasankp000.Entity.FaceClosestEntity;
import net.shasankp000.EntityUtil;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.GameAI.services.SneakLockService;

import java.util.List;
import java.util.function.Consumer;

/**
 * Stage-2 refactor: extract RL action execution out of {@code BotEventHandler}.
 *
 * <p>Purely a mechanical move of the action switch; behavior should remain unchanged.</p>
 */
public final class BotRLActionService {

    private BotRLActionService() {}

    public static void performAction(ServerPlayerEntity bot, String action, Consumer<String> debug) {
        if (bot == null || action == null) {
            return;
        }
        Consumer<String> debugFn = debug != null ? debug : _ignored -> {};

        switch (action) {
            case "moveForward" -> {
                debugFn.accept("Performing action: move forward");
                BotActions.moveForward(bot);
                AutoFaceEntity.isBotMoving = true;
            }
            case "moveBackward" -> {
                debugFn.accept("Performing action: move backward");
                BotActions.moveBackward(bot);
                AutoFaceEntity.isBotMoving = true;
            }
            case "turnLeft" -> {
                debugFn.accept("Performing action: turn left");
                BotActions.turnLeft(bot);
            }
            case "turnRight" -> {
                debugFn.accept("Performing action: turn right");
                BotActions.turnRight(bot);
            }
            case "jump" -> {
                debugFn.accept("Performing action: jump");
                BotActions.jump(bot);
            }
            case "jumpForward" -> {
                debugFn.accept("Performing action: jump forward");
                BotActions.jumpForward(bot);
            }
            case "sneak" -> {
                debugFn.accept("Performing action: sneak");
                BotActions.sneak(bot, true);
            }
            case "sprint" -> {
                debugFn.accept("Performing action: sprint");
                BotActions.sprint(bot, true);
            }
            case "unsneak" -> {
                debugFn.accept("Performing action: unsneak");
                if (!SneakLockService.isLocked(bot.getUuid())) {
                    BotActions.sneak(bot, false);
                }
            }
            case "unsprint" -> {
                debugFn.accept("Performing action: unsprint");
                BotActions.sprint(bot, false);
            }
            case "stopMoving" -> {
                debugFn.accept("Performing action: stop moving");
                BotActions.stop(bot);
                AutoFaceEntity.isBotMoving = false;
            }
            case "useItem" -> {
                debugFn.accept("Performing action: use currently selected item");
                BotActions.useSelectedItem(bot);
            }
            case "attack" -> {
                debugFn.accept("Performing action: attack");
                List<Entity> hostiles = AutoFaceEntity.hostileEntities;
                if (hostiles == null || hostiles.isEmpty()) {
                    hostiles = AutoFaceEntity.detectNearbyEntities(bot, 10).stream()
                            .filter(EntityUtil::isHostile)
                            .toList();
                }
                if (!hostiles.isEmpty()) {
                    FaceClosestEntity.faceClosestEntity(bot, hostiles);
                    BotActions.attackNearest(bot, hostiles);
                } else {
                    debugFn.accept("No hostile entities available to attack.");
                }
            }
            case "hotbar1" -> {
                debugFn.accept("Performing action: Select hotbar slot 1");
                BotActions.selectHotbarSlot(bot, 0);
            }
            case "hotbar2" -> {
                debugFn.accept("Performing action: Select hotbar slot 2");
                BotActions.selectHotbarSlot(bot, 1);
            }
            case "hotbar3" -> {
                debugFn.accept("Performing action: Select hotbar slot 3");
                BotActions.selectHotbarSlot(bot, 2);
            }
            case "hotbar4" -> {
                debugFn.accept("Performing action: Select hotbar slot 4");
                BotActions.selectHotbarSlot(bot, 3);
            }
            case "hotbar5" -> {
                debugFn.accept("Performing action: Select hotbar slot 5");
                BotActions.selectHotbarSlot(bot, 4);
            }
            case "hotbar6" -> {
                debugFn.accept("Performing action: Select hotbar slot 6");
                BotActions.selectHotbarSlot(bot, 5);
            }
            case "hotbar7" -> {
                debugFn.accept("Performing action: Select hotbar slot 7");
                BotActions.selectHotbarSlot(bot, 6);
            }
            case "hotbar8" -> {
                debugFn.accept("Performing action: Select hotbar slot 8");
                BotActions.selectHotbarSlot(bot, 7);
            }
            case "hotbar9" -> {
                debugFn.accept("Performing action: Select hotbar slot 9");
                BotActions.selectHotbarSlot(bot, 8);
            }
            case "breakBlock" -> {
                debugFn.accept("Performing action: break block ahead");
                boolean success = BotActions.breakBlockAhead(bot);
                if (!success) {
                    debugFn.accept("No suitable block to break ahead.");
                }
            }
            case "placeSupportBlock" -> {
                debugFn.accept("Performing action: place support block");
                boolean success = BotActions.placeSupportBlock(bot);
                if (!success) {
                    debugFn.accept("Unable to place support block (no block or blocked space).");
                }
            }
            case "escapeStairs" -> {
                debugFn.accept("Performing action: escape stairs");
                BotActions.escapeStairs(bot);
            }
            default -> debugFn.accept("Invalid action");
        }
    }
}
