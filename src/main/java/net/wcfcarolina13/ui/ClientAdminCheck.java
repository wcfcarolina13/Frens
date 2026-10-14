package net.wcfcarolina13.ui;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.DefaultPermissions;
import net.minecraft.command.permission.Permission;

import java.lang.reflect.Method;

/**
 * Client-side guess at whether the local player is a server admin (operator or the
 * integrated-server host), for showing or hiding admin UI. A UI hint only: the server re-checks
 * every action.
 *
 * <p>Host: {@code isIntegratedServerRunning()} (singleplayer or the LAN owner, never a LAN guest).
 * Operator: the player's permission predicate grants {@code DefaultPermissions.MODERATORS}, the
 * level vanilla sends the client for any ops.json entry. 1.21.11 has no
 * {@code hasPermissionLevel(int)}, which is why the old reflective checks were always false.
 *
 * <p>{@code PermissionPredicate} is shadowed at compile time by an empty stub class in
 * {@code src/main/java/net/minecraft/command/permission/}, so {@code hasPermission} cannot be called
 * directly. It is looked up by reflection on the real interface (intermediary
 * {@code net.minecraft.class_12096}, mapped to the runtime namespace); {@code hasPermission} keeps the
 * same name in every namespace. Looking the method up on the interface, not the instance's class,
 * avoids IllegalAccessException on a non-public implementation. Any failure answers false.
 *
 * <p>Render thread only.
 */
public final class ClientAdminCheck {

    private static final String PERMISSION_PREDICATE_INTERMEDIARY = "net.minecraft.class_12096";

    private static volatile Method hasPermission;
    private static volatile boolean lookupFailed;

    private ClientAdminCheck() {}

    public static boolean isLikelyAdmin() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return false;
        }
        if (client.isIntegratedServerRunning()) {
            return true;
        }
        if (client.player == null) {
            return false;
        }
        try {
            Method m = hasPermissionMethod();
            if (m == null) {
                return false;
            }
            Object predicate = client.player.getPermissions();
            return predicate != null && Boolean.TRUE.equals(m.invoke(predicate, DefaultPermissions.MODERATORS));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Method hasPermissionMethod() {
        Method m = hasPermission;
        if (m != null || lookupFailed) {
            return m;
        }
        try {
            String runtimeName = FabricLoader.getInstance().getMappingResolver()
                    .mapClassName("intermediary", PERMISSION_PREDICATE_INTERMEDIARY);
            Class<?> iface = Class.forName(runtimeName, false, ClientAdminCheck.class.getClassLoader());
            m = iface.getMethod("hasPermission", Permission.class);
            hasPermission = m;
            return m;
        } catch (Throwable t) {
            lookupFailed = true;
            return null;
        }
    }
}
