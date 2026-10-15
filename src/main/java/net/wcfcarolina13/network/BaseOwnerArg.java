package net.wcfcarolina13.network;

import net.wcfcarolina13.GameAI.services.BotHomeService;

/**
 * Pure resolution of a base-owner choice into the {@code ownerUuid}/{@code ownerName} pair that
 * {@link BotHomeService#setBaseOwner} stores. Shared by the {@code BaseSetOwnerPayload} receiver and
 * {@code /bot base owner <base> <player|server|none>}. No Minecraft types.
 */
public final class BaseOwnerArg {

    /** The three owner choices of {@code /bot base owner}. */
    public enum Kind { PLAYER, SERVER, NONE }

    /** Owner pair to store. Both null means unowned (legacy). */
    public record Owner(String uuid, String name) {
    }

    private BaseOwnerArg() {
    }

    /**
     * @param kind which owner the admin chose
     * @param uuid the player's UUID string ({@code UUID.toString()}), only read for {@link Kind#PLAYER}
     * @param name the player's name, only read for {@link Kind#PLAYER}
     */
    public static Owner resolve(Kind kind, String uuid, String name) {
        if (kind == null) {
            return new Owner(null, null);
        }
        return switch (kind) {
            case SERVER -> new Owner(BotHomeService.SERVER_OWNER_UUID, BotHomeService.SERVER_OWNER_NAME);
            case NONE -> new Owner(null, null);
            case PLAYER -> normalize(uuid, name);
        };
    }

    /** Blank strings become null so a cleared owner reads as legacy (the payload path's rule). */
    public static Owner normalize(String uuid, String name) {
        return new Owner(blankToNull(uuid), blankToNull(name));
    }

    /** How the new owner is named in the confirmation message. */
    public static String display(Owner owner) {
        if (owner == null) {
            return "(unowned)";
        }
        if (owner.name() != null) {
            return owner.name();
        }
        return BotHomeService.SERVER_OWNER_UUID.equals(owner.uuid()) ? BotHomeService.SERVER_OWNER_NAME : "(unowned)";
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
