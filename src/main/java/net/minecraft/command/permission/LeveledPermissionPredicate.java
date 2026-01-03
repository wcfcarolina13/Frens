package net.minecraft.command.permission;

/** Compile-time stub for LeveledPermissionPredicate used by older/newer mappings.
 *  Matches runtime field types so compiled descriptors stay compatible.
 */
public final class LeveledPermissionPredicate extends PermissionPredicate {
    public static final LeveledPermissionPredicate ALL = new LeveledPermissionPredicate();
    public static final LeveledPermissionPredicate MODERATORS = new LeveledPermissionPredicate();
    public static final LeveledPermissionPredicate GAMEMASTERS = new LeveledPermissionPredicate();
    public static final LeveledPermissionPredicate ADMINS = new LeveledPermissionPredicate();
    public static final LeveledPermissionPredicate OWNERS = new LeveledPermissionPredicate();

    private LeveledPermissionPredicate() {}
}
