package net.wcfcarolina13.network;

import com.google.gson.Gson;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.Entity.createFakePlayer;
import net.wcfcarolina13.FilingSystem.ManualConfig;
import net.wcfcarolina13.GameAI.services.SurvivalRecruitmentService;
import net.wcfcarolina13.GameAI.services.WizardTomeGrantService;
import net.wcfcarolina13.GameAI.services.LearningModeService;
import net.wcfcarolina13.GameAI.services.BotSkinService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Server-side operator-only actions for survival recruitment state. */
public final class RecruitmentAdminNetworkManager {

    private static volatile boolean REGISTERED = false;
    private static final Gson GSON = new Gson();
    private static final Map<String, Long> RECRUIT_MODE_SWITCH_CONFIRM_UNTIL_MS = new ConcurrentHashMap<>();
    private static final long RECRUIT_MODE_SWITCH_CONFIRM_WINDOW_MS = 12_000L;

    private record PermissionsSnapshotData(boolean requesterOperator,
                                           Map<String, Boolean> globalDefaults,
                                           Map<String, Map<String, Boolean>> userOverrides,
                                           Map<String, String> knownUsers) {}

    private RecruitmentAdminNetworkManager() {
    }

    public static void registerReceiversOnce() {
        if (REGISTERED) {
            return;
        }
        REGISTERED = true;

        ServerPlayNetworking.registerGlobalReceiver(RecruitmentAdminActionPayload.ID, (payload, context) ->
                context.server().execute(() -> handle(context.server(), context.player(), payload))
        );
    }

    private static void handle(MinecraftServer server, ServerPlayerEntity player, RecruitmentAdminActionPayload payload) {
        if (server == null || player == null || player.isRemoved()) {
            return;
        }
        // Only real players can use admin actions.
        if (player instanceof createFakePlayer) {
            return;
        }

        String botAlias = payload != null && payload.botAlias() != null && !payload.botAlias().isBlank()
                ? payload.botAlias().trim()
                : "Jake";

        if (Frens.CONFIG == null) {
            ServerPlayNetworking.send(player, new RecruitmentAdminStatusPayload(botAlias, "Config not ready."));
            return;
        }

        String action = payload != null && payload.action() != null ? payload.action().trim().toLowerCase(Locale.ROOT) : "";
        int payloadIntArg = payload != null ? payload.intArg() : 0;
        String worldKey = worldKey(server);
        ManualConfig.SurvivalRecruitmentState st = SurvivalRecruitmentService.getState(server);

        if (action.equals("permissions_snapshot")) {
            if (!Frens.isOperator(player)) {
                ServerPlayNetworking.send(player, new RecruitmentAdminStatusPayload(botAlias, "Not authorized."));
                return;
            }
            sendPermissionsSnapshot(server, player, botAlias);
            ServerPlayNetworking.send(player, new RecruitmentAdminStatusPayload(botAlias, "Permissions snapshot synced."));
            return;
        }

        if (action.startsWith("perm_global:")) {
            if (!Frens.isOperator(player)) {
                ServerPlayNetworking.send(player, new RecruitmentAdminStatusPayload(botAlias, "Not authorized."));
                return;
            }
            String permissionKey = action.substring("perm_global:".length()).trim();
            if (permissionKey.isBlank()) {
                ServerPlayNetworking.send(player, new RecruitmentAdminStatusPayload(botAlias, "Missing permission key."));
                return;
            }
            SurvivalRecruitmentService.setAdminPermissionGlobal(server, permissionKey, payload != null && payload.boolArg());
            broadcastPermissionsSnapshot(server, botAlias);
            ServerPlayNetworking.send(player, new RecruitmentAdminStatusPayload(botAlias,
                    "Global permission '" + permissionKey + "' set to " + (payload != null && payload.boolArg()) + "."));
            return;
        }

        if (action.startsWith("perm_user:")) {
            if (!Frens.isOperator(player)) {
                ServerPlayNetworking.send(player, new RecruitmentAdminStatusPayload(botAlias, "Not authorized."));
                return;
            }
            String encoded = action.substring("perm_user:".length()).trim();
            String[] parts = encoded.split(":", 2);
            if (parts.length < 2) {
                ServerPlayNetworking.send(player, new RecruitmentAdminStatusPayload(botAlias, "Expected perm_user:<uuid>:<permissionKey>."));
                return;
            }
            String targetUuid = parts[0].trim().toLowerCase(Locale.ROOT);
            String permissionKey = parts[1].trim().toLowerCase(Locale.ROOT);
            if (targetUuid.isBlank() || permissionKey.isBlank()) {
                ServerPlayNetworking.send(player, new RecruitmentAdminStatusPayload(botAlias, "Missing uuid or permission key."));
                return;
            }
            SurvivalRecruitmentService.setAdminPermissionUserOverride(server, targetUuid, permissionKey,
                    payload != null && payload.boolArg());
            broadcastPermissionsSnapshot(server, botAlias);
            ServerPlayNetworking.send(player, new RecruitmentAdminStatusPayload(botAlias,
                    "User permission '" + permissionKey + "' for " + targetUuid + " set to "
                            + (payload != null && payload.boolArg()) + "."));
            return;
        }

        String permissionKey = permissionKeyForAction(action);
        if (permissionKey != null && !permissionKey.isBlank()
                && !SurvivalRecruitmentService.isAdminPermissionAllowed(player, permissionKey)) {
            ServerPlayNetworking.send(player, new RecruitmentAdminStatusPayload(botAlias,
                    "Not authorized for action '" + action + "'."));
            return;
        }

        List<String> out = new ArrayList<>();

        switch (action) {
            case "give_wizard_tome" -> {
                int granted = WizardTomeGrantService.grant(player, Math.max(1, payloadIntArg));
                if (granted <= 0) {
                    out.add("Could not grant Wizard's Tome (player unavailable).");
                } else {
                    out.add("Gave Wizard's Tome x" + granted + ".");
                }
            }
            case "status" -> {
                out.addAll(buildStatusLines(server, worldKey, st));
            }
            case "setanchor_here" -> {
                if (!SurvivalRecruitmentService.isEnabled(server)) {
                    out.add("Survival recruitment mode is disabled.");
                    out.add("Enable it first if you want the questline to use an anchor.");
                    break;
                }

                st.setCompanionAnchorSet(true);
                st.setCompanionAnchorDimension(player.getCommandSource().getWorld().getRegistryKey().getValue().toString());
                st.setCompanionAnchorPos(player.getBlockPos().asLong());
                Frens.CONFIG.save();

                BlockPos p = player.getBlockPos();
                out.add("Anchor set here: " + p.getX() + "," + p.getY() + "," + p.getZ());
                out.add("Dim: " + st.getCompanionAnchorDimension());
            }
            case "clearanchor" -> {
                st.setCompanionAnchorSet(false);
                st.setCompanionAnchorDimension(null);
                st.setCompanionAnchorPos(0L);
                Frens.CONFIG.save();
                out.add("Anchor cleared.");
            }
            case "reset" -> {
                st.setRecruited(false);
                st.setRecruitedByUuid(null);
                st.setRecruitedByName(null);
                st.setRecruitedAtEpochMs(0L);

                st.setCompanionQuestStage(0);
                st.setPermanentCompanion(false);
                st.setCompanionAnchorSet(false);
                st.setCompanionAnchorDimension(null);
                st.setCompanionAnchorPos(0L);

                Frens.CONFIG.save();

                // Push updated recruitment state + quest state to all real players.
                for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                    if (p == null || p.isRemoved() || (p instanceof createFakePlayer)) {
                        continue;
                    }
                    SurvivalRecruitmentService.sendRecruitmentState(p);
                    ServerPlayNetworking.send(p, new RecruitmentPromptPayload(false, st.getBotAlias()));
                    ServerPlayNetworking.send(p, new CompanionQuestStatePayload(st.getBotAlias(), 0, false));
                }

                out.add("Recruitment reset for world '" + (worldKey == null ? "default" : worldKey) + "'.");
                out.add("Recruited=false; stage=0; permanent=false; anchor cleared.");
            }
            case "enable", "disable" -> {
                boolean enabled = "enable".equals(action);
                String targetWorldMode = enabled ? "questing" : "admin";
                String currentWorldMode = st != null ? st.getSelectedWorldMode() : null;
                boolean shouldWarnAboutSwitch = st != null
                        && st.isModeSelectionDone()
                        && currentWorldMode != null
                        && !currentWorldMode.isBlank()
                        && !currentWorldMode.equalsIgnoreCase(targetWorldMode);
                if (shouldWarnAboutSwitch) {
                    String confirmKey = (worldKey + ":" + player.getUuidAsString() + "->" + targetWorldMode)
                            .toLowerCase(Locale.ROOT);
                    long now = System.currentTimeMillis();
                    Long confirmUntil = RECRUIT_MODE_SWITCH_CONFIRM_UNTIL_MS.get(confirmKey);
                    if (confirmUntil == null || confirmUntil < now) {
                        RECRUIT_MODE_SWITCH_CONFIRM_UNTIL_MS.put(confirmKey, now + RECRUIT_MODE_SWITCH_CONFIRM_WINDOW_MS);
                        out.add("Warning: switching world mode from '" + currentWorldMode + "' to '" + targetWorldMode
                                + "' may cause future questline/build progression conflicts.");
                        out.add("Press '" + (enabled ? "Enable recruit" : "Disable recruit")
                                + "' again within 12 seconds to confirm.");
                        break;
                    }
                    RECRUIT_MODE_SWITCH_CONFIRM_UNTIL_MS.remove(confirmKey);
                }

                SurvivalRecruitmentService.setWorldMode(server, enabled, player.getName().getString());
                ManualConfig.SurvivalRecruitmentState updated = SurvivalRecruitmentService.getState(server);
                String currentAlias = updated != null ? updated.getBotAlias() : botAlias;
                for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                    if (p == null || p.isRemoved() || (p instanceof createFakePlayer)) {
                        continue;
                    }
                    SurvivalRecruitmentService.sendRecruitmentState(p);
                    if (!enabled) {
                        ServerPlayNetworking.send(p, new RecruitmentPromptPayload(false, currentAlias));
                    }
                }
                out.add("Survival recruitment mode: " + (enabled ? "enabled" : "disabled"));
            }
            case "setstage" -> {
                if (!SurvivalRecruitmentService.isEnabled(server)) {
                    out.add("Survival recruitment mode is disabled.");
                    out.add("Use 'Enable' first.");
                    break;
                }
                if (st == null || !st.isRecruited()) {
                    out.add("World is not recruited yet.");
                    break;
                }

                int stage = payloadIntArg;
                st.setCompanionQuestStage(stage);
                // Keep consistent with SurvivalCompanionQuestService:
                // stage >= 4 means the companion becomes permanent.
                if (stage >= 4) {
                    st.setPermanentCompanion(true);
                    st.setCompanionQuestStage(Math.max(st.getCompanionQuestStage(), 4));
                } else {
                    st.setPermanentCompanion(false);
                }
                Frens.CONFIG.save();

                int updatedStage = st.getCompanionQuestStage();
                boolean perm = st.isPermanentCompanion();
                for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                    if (p == null || p.isRemoved() || (p instanceof createFakePlayer)) {
                        continue;
                    }
                    ServerPlayNetworking.send(p, new CompanionQuestStatePayload(st.getBotAlias(), updatedStage, perm));
                }

                out.add("Companion quest stage set to " + updatedStage + " (permanent=" + perm + ").");
                if (perm) {
                    out.add("Permanent companion unlocked (required for companion spells/commands).");
                }
            }
            case "skin_everyone_toggle" -> {
                boolean updated = !SurvivalRecruitmentService.isAllowEveryoneSkinChange(server);
                SurvivalRecruitmentService.setAllowEveryoneSkinChange(server, updated);
                for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                    if (p == null || p.isRemoved() || (p instanceof createFakePlayer)) {
                        continue;
                    }
                    SurvivalRecruitmentService.sendRecruitmentState(p);
                }
                out.add("Skin policy: allowEveryoneSkinChange=" + updated);
            }
            case "skin_custom_toggle" -> {
                boolean updated = !SurvivalRecruitmentService.isAllowCustomSkins(server);
                SurvivalRecruitmentService.setAllowCustomSkins(server, updated);
                int reverted = 0;
                if (!updated) {
                    reverted = BotSkinService.reconcileNonAdminCustomSkinsToSafe(server);
                }
                for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                    if (p == null || p.isRemoved() || (p instanceof createFakePlayer)) {
                        continue;
                    }
                    SurvivalRecruitmentService.sendRecruitmentState(p);
                }
                out.add("Skin policy: allowCustomSkins=" + updated);
                if (!updated) {
                    out.add("Reverted non-admin custom skins to safe preset: " + reverted);
                }
            }
            case "learning_status", "learning_start", "learning_stop_success", "learning_stop_fail", "learning_stop_abort" -> {
                out.addAll(LearningModeService.handleAdminAction(server, player, action, botAlias));
            }
            default -> {
                out.add("Unknown action: '" + action + "'.");
            }
        }

        if (out.isEmpty()) {
            out.add("OK.");
        }

        ServerPlayNetworking.send(player, new RecruitmentAdminStatusPayload(botAlias, String.join("\n", out)));
    }

    private static String permissionKeyForAction(String action) {
        if (action == null || action.isBlank()) {
            return null;
        }
        return switch (action) {
            case "give_wizard_tome" -> "wizard_tome";
            case "status", "recruit_status", "enable", "disable" -> "recruit_manage";
            case "reset", "recruit_reset" -> "recruit_reset";
            case "setanchor_here", "clearanchor", "anchor_set", "anchor_clear" -> "village_anchor";
            case "setstage" -> "stage_debug";
            case "skin_everyone_toggle" -> "skin_policy_everyone";
            case "skin_custom_toggle" -> "skin_policy_custom";
            case "learning_status", "learning_start", "learning_stop_success", "learning_stop_fail", "learning_stop_abort" -> "learning_manage";
            default -> null;
        };
    }

    private static void sendPermissionsSnapshot(MinecraftServer server, ServerPlayerEntity player, String botAlias) {
        if (server == null || player == null || player.isRemoved()) {
            return;
        }
        PermissionsSnapshotData data = new PermissionsSnapshotData(
                Frens.isOperator(player),
                SurvivalRecruitmentService.getAdminPermissionGlobals(server),
                SurvivalRecruitmentService.getAdminPermissionUserOverrides(server),
                SurvivalRecruitmentService.collectKnownPermissionUsers(server)
        );
        ServerPlayNetworking.send(player, new RecruitmentAdminPermissionsPayload(botAlias, GSON.toJson(data)));
    }

    private static void broadcastPermissionsSnapshot(MinecraftServer server, String botAlias) {
        if (server == null) {
            return;
        }
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (p == null || p.isRemoved() || (p instanceof createFakePlayer)) {
                continue;
            }
            sendPermissionsSnapshot(server, p, botAlias);
        }
    }

    private static String worldKey(MinecraftServer server) {
        if (server == null) {
            return "default";
        }
        String key = server.getSaveProperties().getLevelName();
        return (key == null || key.isBlank()) ? "default" : key;
    }

    private static List<String> buildStatusLines(MinecraftServer server, String worldKey, ManualConfig.SurvivalRecruitmentState st) {
        List<String> out = new ArrayList<>();
        boolean enabled = SurvivalRecruitmentService.isEnabled(server);
        out.add("Survival recruitment mode: " + enabled);
        out.add("World key: " + (worldKey == null ? "default" : worldKey));
        if (st == null) {
            out.add("State: <null>");
            return out;
        }
        out.add("Mode selection done: " + st.isModeSelectionDone()
                + " (selected=" + (st.getSelectedWorldMode() == null ? "unset" : st.getSelectedWorldMode()) + ")");
        Map<String, String> delegates = st.getModeSelectionDelegatesByUuid();
        out.add("Mode delegates: " + delegates.size());
        if (!delegates.isEmpty()) {
            List<String> names = new ArrayList<>(delegates.values());
            names.sort(String.CASE_INSENSITIVE_ORDER);
            out.add("Delegated players: " + String.join(", ", names));
        }

        out.add("Recruited: " + st.isRecruited() + " (botAlias=" + st.getBotAlias() + ")");
        if (st.isRecruited()) {
            String by = (st.getRecruitedByName() == null || st.getRecruitedByName().isBlank()) ? "unknown" : st.getRecruitedByName();
            out.add("Recruited by: " + by);
            out.add("Recruited at (epoch ms): " + st.getRecruitedAtEpochMs());
        }

        out.add("Companion quest: stage=" + st.getCompanionQuestStage() + " permanent=" + st.isPermanentCompanion());
        out.add("Skin policy: allowEveryoneSkinChange=" + st.isAllowEveryoneSkinChange()
            + " allowCustomSkins=" + st.isAllowCustomSkins());
        if (st.isCompanionAnchorSet()) {
            BlockPos anchor = BlockPos.fromLong(st.getCompanionAnchorPos());
            String dim = st.getCompanionAnchorDimension();
            out.add("Anchor: set=true dim=" + (dim == null ? "?" : dim) + " pos=" + anchor.getX() + "," + anchor.getY() + "," + anchor.getZ());
        } else {
            out.add("Anchor: set=false");
        }

        // If enabled, hint about current gating state.
        if (server != null && enabled) {
            out.add("World recruited (gating): " + SurvivalRecruitmentService.isWorldRecruited(server));
        }
        return out;
    }
}
