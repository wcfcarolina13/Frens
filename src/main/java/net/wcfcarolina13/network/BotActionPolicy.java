package net.wcfcarolina13.network;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure rules for {@code BotActionPayload}: which UI actions exist, who may run each one, the
 * closed shape of every argument, and the canonical {@code /bot ...} string the server builds
 * (without the leading slash) once it has resolved and authorised the bot itself.
 *
 * <p>Free of Minecraft imports so it can be unit-tested. The client never sends a command
 * string: it sends an {@link Action} name, a bot alias and a short argument list. Every argument
 * is checked against a range, an allowlist or a strict charset, and only checked values are ever
 * echoed into the command. The bot name in the command is the SERVER-resolved profile name.
 *
 * <p>Where the bot goes: {@code /bot skill} and {@code fortify} take the target as any token of
 * a whitespace-split argument list, scanned from the END, so the resolved name is appended last
 * there and no client token may equal {@code all}, {@code each} or a bot name. The other commands
 * bind the bot at a fixed grammar slot, which is not always last: {@code leash <bot>} (a plain
 * string slot), {@code leash release <bot>}, {@code retrieve <bot> me}, {@code guard <bot> <radius>}, {@code patrol <bot> <radius>}, {@code compass home <bot> <name>},
 * {@code compass travel <bot> [name]}. Slots typed {@code EntityArgumentType.player()} (return,
 * guard, patrol, craft, cook) need a name of at most 16 chars, quoted when it has other chars.
 */
public final class BotActionPolicy {

    private BotActionPolicy() {}

    /** Who may run an action. */
    public enum Access {
        /** The bot's configured owner, an operator, or the integrated-server host. */
        BOT_OWNER,
        /** Operator or integrated-server host only. */
        SERVER_ADMIN
    }

    /** Every UI action that may cross the wire. Args are documented per constant. */
    public enum Action {
        /** [] -> {@code open <bot>} */
        OPEN(Access.BOT_OWNER, true),
        /** [] -> {@code stop <bot>} */
        STOP(Access.BOT_OWNER, true),
        /** [] -> {@code resume <bot>} */
        RESUME(Access.BOT_OWNER, true),
        /** [] -> {@code standdown <bot>} */
        STAND_DOWN(Access.BOT_OWNER, true),
        /** [] -> {@code come <bot>} */
        COME(Access.BOT_OWNER, true),
        /** [] -> {@code go_to_look <bot>} */
        GO_TO_LOOK(Access.BOT_OWNER, true),
        /** [] -> {@code sleep <bot>} */
        SLEEP(Access.BOT_OWNER, true),
        /** [] -> {@code return <bot>} */
        RETURN_HOME(Access.BOT_OWNER, true),
        /** [] -> {@code follow <bot>} (the bot follows the sender) */
        FOLLOW(Access.BOT_OWNER, true),
        /** [] -> {@code follow stop <bot>} */
        FOLLOW_STOP(Access.BOT_OWNER, true),
        /** [] -> {@code follow toggle <bot>} */
        FOLLOW_TOGGLE(Access.BOT_OWNER, true),
        /** [distance 1.0..64.0] -> {@code follow-distance <d.d> <bot>} */
        FOLLOW_DISTANCE(Access.BOT_OWNER, true),
        /** [radius 3..32] -> {@code guard <bot> <radius>} */
        GUARD(Access.BOT_OWNER, true),
        /** [radius 3..32] -> {@code patrol <bot> <radius>} */
        PATROL(Access.BOT_OWNER, true),
        /** [] -> {@code leash <bot>}, or ["release"] -> {@code leash release <bot>} */
        LEASH(Access.BOT_OWNER, true),
        /** [] -> {@code fish forget <bot>} */
        FISH_FORGET(Access.BOT_OWNER, true),
        /** [name in {@link #SIMPLE_SKILLS}] -> {@code skill <name> <bot>} */
        SKILL(Access.BOT_OWNER, true),
        /** [] or [trees 1..64] -> {@code skill woodcut [n] <bot>} */
        SKILL_WOODCUT(Access.BOT_OWNER, true),
        /** [] or [fish 1..128] -> {@code skill fish [n] <bot>} */
        SKILL_FISH(Access.BOT_OWNER, true),
        /** [count 0..256 (0 omits), range 0 or 16..128 (0 omits)] -> {@code skill wool [n] [range=r] <bot>} */
        SKILL_WOOL(Access.BOT_OWNER, true),
        /** [] or [length 1..128] -> {@code skill stripmine [n] <bot>} */
        SKILL_STRIPMINE(Access.BOT_OWNER, true),
        /** [] or [blocks 1..128 or "surface"] -> {@code skill mining ascent [x] <bot>} */
        SKILL_ASCENT(Access.BOT_OWNER, true),
        /** [] or [blocks 1..128] -> {@code skill mining descent [n] <bot>} */
        SKILL_DESCENT(Access.BOT_OWNER, true),
        /** [x, y, z] -> {@code skill farm targetX=x targetY=y targetZ=z manual=true <bot>} */
        SKILL_FARM(Access.BOT_OWNER, true),
        /** [hovel|burrow, x, y, z] -> {@code skill shelter <type> [radius=4] targetX=x targetY=y targetZ=z <bot>} */
        SKILL_SHELTER(Access.BOT_OWNER, true),
        /** [schematic in {@link #BUILD_SCHEMATICS}, rotation 0..3, x, y, z] -> {@code skill build <s> [rotation=r] targetX=x targetY=y targetZ=z <bot>} */
        SKILL_BUILD(Access.BOT_OWNER, true),
        /** [count 0..{@value #MAX_HUNT_COUNT} (0 omits), mob id or "" (omits)] -> {@code skill hunt [n] [mob] <bot>} */
        SKILL_HUNT(Access.BOT_OWNER, true),
        /**
         * [sub in {@link #FORTIFY_SUBS}, wall label or ""] -> {@code fortify [sub] [label] [auto] <bot>}.
         * {@code list} is server admin only ({@link #requiresServerAdmin}).
         */
        FORTIFY(Access.BOT_OWNER, true),
        /** [key in {@link #TOGGLE_KEYS}, toggle|on|off] -> {@code <key> <value> <bot>} */
        TOGGLE(Access.BOT_OWNER, true),
        /** [hobby in {@link #HOBBY_NAMES}, on|off] -> {@code hobby <name> <on|off> <bot>} */
        HOBBY_SET(Access.BOT_OWNER, true),
        /** [sub in {@link #COMPANION_SUBS}] -> {@code companion <sub> <bot>} */
        COMPANION(Access.BOT_OWNER, true),
        /** [compass name] -> {@code compass home <bot> <name>} */
        COMPASS_HOME(Access.BOT_OWNER, false),
        /** [compass name or ""] -> {@code compass travel <bot> [name]} */
        COMPASS_TRAVEL(Access.BOT_OWNER, false),
        /** [item id, amount 1..64] -> {@code craft <item> <n> "" <bot>} */
        CRAFT(Access.BOT_OWNER, true),
        /** [item id, fuel: none|auto|item id] -> {@code cook <item> <fuel> <bot>} */
        COOK(Access.BOT_OWNER, true),
        /** [] -> {@code retrieve <bot> me} (fetch the sender's own death drops) */
        RETRIEVE(Access.BOT_OWNER, false),
        /** [] with the NEW bot name as alias -> {@code spawn <name> admin} */
        SPAWN(Access.SERVER_ADMIN, false);

        private final Access access;
        private final boolean untargetedForm;

        Action(Access access, boolean untargetedForm) {
            this.access = access;
            this.untargetedForm = untargetedForm;
        }

        public Access access() {
            return access;
        }

        /**
         * Whether the command has a form with no bot ({@code bot stop}), which acts on the
         * sender's remembered or active bot. Only a server admin with a blank alias gets it,
         * so their buttons keep today's behaviour.
         */
        public boolean hasUntargetedForm() {
            return untargetedForm;
        }

    }

    // ── Allowlists ──────────────────────────────────────────────────────────

    public static final Set<String> SIMPLE_SKILLS = Set.of(
            "drop_sweep", "woodcut_cleanup", "harvest", "collect_dirt", "leaf_litter", "mining");

    public static final Set<String> BUILD_SCHEMATICS = Set.of(
            "small_shelter", "small_hut", "watchtower", "defensive_wall_section",
            "defensive_wall_corner", "defensive_gatehouse", "bridge", "test_platform");

    public static final Set<String> SHELTER_TYPES = Set.of("hovel", "burrow");

    /** {@code start} is the bare {@code fortify}; {@code patch_auto} is {@code fortify patch <label> auto}. */
    public static final Set<String> FORTIFY_SUBS = Set.of(
            "start", "resume", "patch", "patch_auto", "status", "moat", "drift", "expand", "list");

    /** The per-bot toggles of the inventory screen's Skills/Behaviour tabs. */
    public static final Set<String> TOGGLE_KEYS = Set.of(
            "auto_return_sunset", "auto_return_self_sufficient", "tactical_shelter",
            "auto_return_sunset_guard_patrol", "auto_return_skip_permission", "attack_named_mobs",
            "idle_hobbies", "auto_hunt_starving", "unleash_tethered", "leash_on_dismount");

    public static final Set<String> TOGGLE_VALUES = Set.of("toggle", "on", "off");

    /** The hobby names, the same list as {@code BotPlayerInventoryScreenHandler.HOBBY_BIT_ORDER}. */
    public static final List<String> HOBBY_NAMES = net.wcfcarolina13.ui.HobbyBitOrder.NAMES;

    public static final Set<String> COMPANION_SUBS = Set.of("come", "summon", "home", "open", "soulofender");

    // ── Limits ──────────────────────────────────────────────────────────────

    public static final int MAX_ARGS = 8;
    public static final int MAX_ALIAS_LEN = 32;
    public static final int MAX_ID_LEN = 64;
    public static final int MAX_LABEL_LEN = 64;
    public static final int MAX_PLAYER_SLOT_NAME_LEN = 16;
    /** BotRestoreScreen's new-bot name rule. */
    public static final int MAX_NEW_BOT_NAME_LEN = 24;
    public static final int COORD_XZ_LIMIT = 30_000_000;
    public static final int COORD_Y_LIMIT = 2048;
    /** The Hunt screen's count field takes 5 digits. */
    public static final int MAX_HUNT_COUNT = 99_999;

    private static final Pattern ALIAS = Pattern.compile("[A-Za-z0-9_ .'\\-]+");
    private static final Pattern NEW_BOT_NAME = Pattern.compile("[A-Za-z0-9_ .'\\-]+");
    private static final Pattern ITEM_ID = Pattern.compile("[a-z0-9_:./\\-]+");
    private static final Pattern INT = Pattern.compile("-?[0-9]{1,9}");
    private static final Pattern DECIMAL = Pattern.compile("[0-9]{1,2}(\\.[0-9]{1,3})?");

    // ── Parsing helpers ─────────────────────────────────────────────────────

    /** The action named {@code raw} (exact enum name), or empty. */
    public static Optional<Action> parseAction(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > 32) return Optional.empty();
        try {
            return Optional.of(Action.valueOf(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** {@code all} and {@code each} are target keywords of the skill parser. */
    public static boolean isReservedTargetWord(String token) {
        if (token == null) return false;
        String t = token.trim().toLowerCase(Locale.ROOT);
        return t.equals("all") || t.equals("each");
    }

    /**
     * A client alias naming an existing bot: 1..{@value #MAX_ALIAS_LEN} chars of
     * {@code [A-Za-z0-9_ .'-]}, not blank-padded, and not {@code all}/{@code each}.
     */
    public static boolean isValidAlias(String alias) {
        if (alias == null || alias.isEmpty() || alias.length() > MAX_ALIAS_LEN) return false;
        if (!alias.equals(alias.trim())) return false;
        if (!ALIAS.matcher(alias).matches()) return false;
        return !isReservedTargetWord(alias);
    }

    /** A new bot name for SPAWN: BotRestoreScreen's charset and length, not {@code all}/{@code each}. */
    public static boolean isValidNewBotName(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_NEW_BOT_NAME_LEN) return false;
        if (!name.equals(name.trim())) return false;
        if (!NEW_BOT_NAME.matcher(name).matches()) return false;
        return !isReservedTargetWord(name);
    }

    /**
     * An item or mob id: {@code [a-z0-9_:./-]}, 1..{@value #MAX_ID_LEN} chars, not a target
     * keyword and not any current bot name ({@code knownBotNamesLower} holds them lower-cased).
     */
    public static boolean isValidId(String id, Set<String> knownBotNamesLower) {
        if (id == null || id.isEmpty() || id.length() > MAX_ID_LEN) return false;
        if (!ITEM_ID.matcher(id).matches()) return false;
        return !clashesWithTarget(id, knownBotNamesLower);
    }

    /**
     * A wall or compass label: 1..{@value #MAX_LABEL_LEN} printable chars with no control char,
     * {@code §}, {@code "} or {@code \}, no leading/trailing or doubled whitespace, and no
     * whitespace-separated token that is a target keyword or a current bot name.
     */
    public static boolean isValidLabel(String label, Set<String> knownBotNamesLower) {
        if (!isValidLabelText(label)) return false;
        for (String token : label.split(" ")) {
            if (clashesWithTarget(token, knownBotNamesLower)) return false;
        }
        return true;
    }

    /**
     * A FORTIFY wall label: {@link #isValidLabel} and no token the skill parser or
     * {@code handlePatch} would consume ({@link FortifyWallEditPolicy#isRewrittenToken}), so the
     * wall the server checks is the wall the skill uses. Block-id tokens are refused by the
     * server, which has the registry.
     */
    public static boolean isValidFortifyLabel(String label, Set<String> knownBotNamesLower) {
        if (!isValidLabel(label, knownBotNamesLower)) return false;
        for (String token : label.split(" ")) {
            if (FortifyWallEditPolicy.isRewrittenToken(token)) return false;
        }
        return true;
    }

    /**
     * A compass name: {@link #isValidLabel}'s character rules only. {@code compass home|travel}
     * bind the bot in its own {@code string()} slot before the greedy name, so no word of the
     * name can retarget the command.
     */
    public static boolean isValidCompassLabel(String label) {
        return isValidLabelText(label);
    }

    private static boolean isValidLabelText(String label) {
        if (label == null || label.isEmpty() || label.length() > MAX_LABEL_LEN) return false;
        if (!label.equals(label.trim())) return false;
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if (c < 0x20 || c == 0x7F || c == '§' || c == '"' || c == '\\') return false;
            if (Character.isWhitespace(c) && c != ' ') return false;
        }
        return !label.contains("  ");
    }

    private static boolean clashesWithTarget(String token, Set<String> knownBotNamesLower) {
        if (isReservedTargetWord(token)) return true;
        return knownBotNamesLower != null && knownBotNamesLower.contains(token.toLowerCase(Locale.ROOT));
    }

    /** An int in {@code [min, max]}, or null. */
    static Integer intIn(String raw, int min, int max) {
        if (raw == null || !INT.matcher(raw).matches()) return null;
        int v;
        try {
            v = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return null;
        }
        return v < min || v > max ? null : v;
    }

    /**
     * A Brigadier {@code StringArgumentType.string()} token: unquoted when every char is in
     * Brigadier's unquoted set ({@code 0-9 A-Z a-z _ - . +}), otherwise double-quoted with
     * {@code \} and {@code "} escaped.
     */
    public static String quoteIfNeeded(String value) {
        boolean plain = !value.isEmpty();
        for (int i = 0; i < value.length() && plain; i++) {
            char c = value.charAt(i);
            plain = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || c == '_' || c == '-' || c == '.' || c == '+';
        }
        if (plain) return value;
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ── Canonical builder ───────────────────────────────────────────────────

    /**
     * The canonical command, without the leading slash, or empty when any argument is invalid.
     *
     * @param action             the action
     * @param args               client args, each checked against the action's shape
     * @param resolvedBotName    the server-resolved bot profile name (for SPAWN: the validated
     *                           new name); null asks for the untargeted form, which the caller
     *                           allows only for a server admin with a blank alias
     * @param knownBotNamesLower every current bot name, lower-cased; free-text args may not equal one
     */
    public static Optional<String> build(Action action, List<String> args, String resolvedBotName,
                                         Set<String> knownBotNamesLower) {
        if (action == null) return Optional.empty();
        List<String> a = args == null ? List.of() : args;
        if (a.size() > MAX_ARGS) return Optional.empty();
        for (String s : a) {
            if (s == null) return Optional.empty();
        }
        if (resolvedBotName == null) {
            if (!action.hasUntargetedForm()) return Optional.empty();
        } else if (resolvedBotName.isEmpty() || isReservedTargetWord(resolvedBotName)
                || resolvedBotName.startsWith("@")) {
            return Optional.empty();
        }
        Set<String> known = knownBotNamesLower == null ? Set.of() : knownBotNamesLower;
        Target bot = new Target(resolvedBotName);
        return Optional.ofNullable(buildOrNull(action, a, bot, known));
    }

    /** The bot name rendered for each kind of grammar slot; null means untargeted. */
    private record Target(String name) {
        boolean untargeted() {
            return name == null;
        }

        /** {@code StringArgumentType.string()} slot. */
        String str() {
            return quoteIfNeeded(name);
        }

        /** {@code EntityArgumentType.player()} slot (readString, at most 16 chars), or null. */
        String player() {
            return name.length() > MAX_PLAYER_SLOT_NAME_LEN ? null : quoteIfNeeded(name);
        }

        /** Whitespace-split skill argument token (no quoting possible), or null. */
        String token() {
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                if (Character.isWhitespace(c) || c == '"') return null;
            }
            return name;
        }
    }

    private static String buildOrNull(Action action, List<String> a, Target bot, Set<String> known) {
        return switch (action) {
            case OPEN -> simple(a, "open", bot);
            case STOP -> simple(a, "stop", bot);
            case RESUME -> simple(a, "resume", bot);
            case STAND_DOWN -> simple(a, "standdown", bot);
            case COME -> simple(a, "come", bot);
            case GO_TO_LOOK -> simple(a, "go_to_look", bot);
            case SLEEP -> simple(a, "sleep", bot);
            case FOLLOW -> simple(a, "follow", bot);
            case FOLLOW_STOP -> simple(a, "follow stop", bot);
            case FOLLOW_TOGGLE -> simple(a, "follow toggle", bot);
            case FISH_FORGET -> simple(a, "fish forget", bot);
            case RETURN_HOME -> {
                if (!a.isEmpty()) yield null;
                if (bot.untargeted()) yield "return";
                String p = bot.player();
                yield p == null ? null : "return " + p;
            }
            case FOLLOW_DISTANCE -> {
                if (a.size() != 1 || !DECIMAL.matcher(a.get(0)).matches()) yield null;
                double d = Double.parseDouble(a.get(0));
                if (d < 1.0D || d > 64.0D) yield null;
                yield withStr("follow-distance " + String.format(Locale.ROOT, "%.1f", d), bot);
            }
            case GUARD -> guardOrPatrol("guard", a, bot);
            case PATROL -> guardOrPatrol("patrol", a, bot);
            case LEASH -> {
                if (a.isEmpty()) yield withToken("leash", bot);
                // leash release takes the bot in a StringArgumentType.string() slot named target.
                yield a.size() == 1 && "release".equals(a.get(0)) ? withStr("leash release", bot) : null;
            }
            case RETRIEVE -> {
                // retrieve takes the bot in a StringArgumentType.string() slot named target, then
                // the who slot ("me" is the sender's own death).
                if (bot.untargeted() || !a.isEmpty()) yield null;
                yield "retrieve " + bot.str() + " me";
            }
            case SKILL -> {
                if (a.size() != 1 || !SIMPLE_SKILLS.contains(a.get(0))) yield null;
                yield withToken("skill " + a.get(0), bot);
            }
            case SKILL_WOODCUT -> optionalCount("skill woodcut", a, 1, 64, bot);
            case SKILL_FISH -> optionalCount("skill fish", a, 1, 128, bot);
            case SKILL_WOOL -> {
                if (a.size() != 2) yield null;
                Integer count = intIn(a.get(0), 0, 256);
                Integer range = intIn(a.get(1), 0, 128);
                if (count == null || range == null || (range != 0 && range < 16)) yield null;
                StringBuilder sb = new StringBuilder("skill wool");
                if (count > 0) sb.append(' ').append(count);
                if (range > 0) sb.append(" range=").append(range);
                yield withToken(sb.toString(), bot);
            }
            case SKILL_STRIPMINE -> optionalCount("skill stripmine", a, 1, 128, bot);
            case SKILL_ASCENT -> {
                if (a.size() == 1 && "surface".equals(a.get(0))) yield withToken("skill mining ascent surface", bot);
                yield optionalCount("skill mining ascent", a, 1, 128, bot);
            }
            case SKILL_DESCENT -> optionalCount("skill mining descent", a, 1, 128, bot);
            case SKILL_FARM -> {
                if (a.size() != 3) yield null;
                String xyz = coords(a, 0);
                yield xyz == null ? null : withToken("skill farm " + xyz + " manual=true", bot);
            }
            case SKILL_SHELTER -> {
                if (a.size() != 4 || !SHELTER_TYPES.contains(a.get(0))) yield null;
                String xyz = coords(a, 1);
                if (xyz == null) yield null;
                String radius = "hovel".equals(a.get(0)) ? " radius=4" : "";
                yield withToken("skill shelter " + a.get(0) + radius + " " + xyz, bot);
            }
            case SKILL_BUILD -> {
                if (a.size() != 5 || !BUILD_SCHEMATICS.contains(a.get(0))) yield null;
                Integer rotation = intIn(a.get(1), 0, 3);
                String xyz = coords(a, 2);
                if (rotation == null || xyz == null) yield null;
                String rot = rotation == 0 ? "" : " rotation=" + rotation;
                yield withToken("skill build " + a.get(0) + rot + " " + xyz, bot);
            }
            case SKILL_HUNT -> {
                if (a.size() != 2) yield null;
                Integer count = intIn(a.get(0), 0, MAX_HUNT_COUNT);
                if (count == null) yield null;
                String mob = a.get(1);
                if (!mob.isEmpty() && (!isValidId(mob, known) || intIn(mob, 0, Integer.MAX_VALUE) != null)) yield null;
                StringBuilder sb = new StringBuilder("skill hunt");
                if (count > 0) sb.append(' ').append(count);
                if (!mob.isEmpty()) sb.append(' ').append(mob);
                yield withToken(sb.toString(), bot);
            }
            case FORTIFY -> fortify(a, bot, known);
            case TOGGLE -> {
                if (a.size() != 2 || !TOGGLE_KEYS.contains(a.get(0)) || !TOGGLE_VALUES.contains(a.get(1))) yield null;
                yield withStr(a.get(0) + " " + a.get(1), bot);
            }
            case HOBBY_SET -> {
                if (a.size() != 2 || !HOBBY_NAMES.contains(a.get(0))
                        || !("on".equals(a.get(1)) || "off".equals(a.get(1)))) yield null;
                yield withStr("hobby " + a.get(0) + " " + a.get(1), bot);
            }
            case COMPANION -> {
                if (a.size() != 1 || !COMPANION_SUBS.contains(a.get(0))) yield null;
                yield withStr("companion " + a.get(0), bot);
            }
            case COMPASS_HOME -> {
                if (bot.untargeted() || a.size() != 1 || !isValidCompassLabel(a.get(0))) yield null;
                yield "compass home " + bot.str() + " " + a.get(0);
            }
            case COMPASS_TRAVEL -> {
                if (bot.untargeted() || a.size() != 1) yield null;
                if (a.get(0).isEmpty()) yield "compass travel " + bot.str();
                yield isValidCompassLabel(a.get(0)) ? "compass travel " + bot.str() + " " + a.get(0) : null;
            }
            case CRAFT -> {
                if (a.size() != 2 || !isValidId(a.get(0), known)) yield null;
                Integer amount = intIn(a.get(1), 1, 64);
                if (amount == null) yield null;
                String item = quoteIfNeeded(a.get(0));
                if (bot.untargeted()) yield "craft " + item + " " + amount;
                String p = bot.player();
                if (p == null) yield null;
                // Always the full "<item> <amount> <material> <bot>" path. "craft <item> <bot>"
                // parses the name as <material> (registered before <bot>) and crafts with the
                // active bot; after <amount> the next string is <material> too. A quoted empty
                // material is the same as none (CraftingHelper treats a blank preference like
                // null), so the bot lands in its own slot.
                yield "craft " + item + " " + amount + " \"\" " + p;
            }
            case COOK -> {
                if (a.size() != 2 || !isValidId(a.get(0), known)) yield null;
                String fuel = a.get(1);
                if (!("none".equals(fuel) || "auto".equals(fuel) || isValidId(fuel, known))) yield null;
                String head = "cook " + quoteIfNeeded(a.get(0)) + " " + quoteIfNeeded(fuel);
                if (bot.untargeted()) yield head;
                String p = bot.player();
                yield p == null ? null : head + " " + p;
            }
            case SPAWN -> {
                if (!a.isEmpty() || bot.untargeted() || !isValidNewBotName(bot.name())) yield null;
                yield "spawn " + bot.str() + " admin";
            }
        };
    }

    private static String simple(List<String> a, String head, Target bot) {
        return a.isEmpty() ? withStr(head, bot) : null;
    }

    private static String withStr(String head, Target bot) {
        return bot.untargeted() ? head : head + " " + bot.str();
    }

    private static String withToken(String head, Target bot) {
        if (bot.untargeted()) return head;
        String t = bot.token();
        return t == null ? null : head + " " + t;
    }

    private static String guardOrPatrol(String head, List<String> a, Target bot) {
        if (a.size() != 1) return null;
        Integer r = intIn(a.get(0), 3, 32);
        if (r == null) return null;
        if (bot.untargeted()) return head + " " + r;
        String p = bot.player();
        return p == null ? null : head + " " + p + " " + r;
    }

    private static String optionalCount(String head, List<String> a, int min, int max, Target bot) {
        if (a.isEmpty()) return withToken(head, bot);
        if (a.size() != 1) return null;
        Integer n = intIn(a.get(0), min, max);
        return n == null ? null : withToken(head + " " + n, bot);
    }

    private static String coords(List<String> a, int from) {
        Integer x = intIn(a.get(from), -COORD_XZ_LIMIT, COORD_XZ_LIMIT);
        Integer y = intIn(a.get(from + 1), -COORD_Y_LIMIT, COORD_Y_LIMIT);
        Integer z = intIn(a.get(from + 2), -COORD_XZ_LIMIT, COORD_XZ_LIMIT);
        if (x == null || y == null || z == null) return null;
        return "targetX=" + x + " targetY=" + y + " targetZ=" + z;
    }

    private static String fortify(List<String> a, Target bot, Set<String> known) {
        if (a.size() != 2 || !FORTIFY_SUBS.contains(a.get(0))) return null;
        String sub = a.get(0);
        String label = a.get(1);
        boolean hasLabel = !label.isEmpty();
        if (hasLabel && !isValidFortifyLabel(label, known)) return null;
        String body;
        switch (sub) {
            case "start", "list" -> {
                if (hasLabel) return null;
                body = sub.equals("start") ? "fortify" : "fortify list";
            }
            case "patch_auto" -> {
                if (!hasLabel) return null;
                body = "fortify patch " + label + " auto";
            }
            default -> body = "fortify " + sub + (hasLabel ? " " + label : "");
        }
        return withToken(body, bot);
    }

    /** FORTIFY subcommands that change a saved wall (named, or the nearest one when unnamed). */
    public static final Set<String> FORTIFY_WALL_EDIT_SUBS = Set.of("resume", "patch", "patch_auto", "moat", "expand");

    /**
     * Whether a FORTIFY call edits a saved wall and so needs the wall edit check on top of bot
     * ownership. True for {@link #FORTIFY_WALL_EDIT_SUBS} with or without a label: with no label
     * the skill picks the NEAREST saved wall, which may belong to someone else.
     */
    public static boolean fortifyEditsSavedWall(Action action, List<String> args) {
        return action == Action.FORTIFY && args != null && !args.isEmpty()
                && FORTIFY_WALL_EDIT_SUBS.contains(args.get(0));
    }

    /** FORTIFY subcommands that report on a saved wall (named, or the nearest one when unnamed). */
    public static final Set<String> FORTIFY_WALL_VIEW_SUBS = Set.of("status", "drift");

    /** Whether a FORTIFY call reports on a saved wall and so needs the wall visibility check. */
    public static boolean fortifyReadsSavedWall(Action action, List<String> args) {
        return action == Action.FORTIFY && args != null && !args.isEmpty()
                && FORTIFY_WALL_VIEW_SUBS.contains(args.get(0));
    }

    /**
     * Whether this call needs an operator or the host on top of {@link Action#access()}:
     * {@code fortify list} prints every saved wall in the world, past the Base screen's
     * visibility rule.
     */
    public static boolean requiresServerAdmin(Action action, List<String> args) {
        if (action == null) return false;
        if (action.access() == Access.SERVER_ADMIN) return true;
        return action == Action.FORTIFY && args != null && !args.isEmpty() && "list".equals(args.get(0));
    }

    /**
     * {@code args} with the FORTIFY label set to {@code wallName}, for a blank-label wall sub
     * whose wall the server resolved, so the skill uses exactly the wall that was checked.
     * Returns {@code args} unchanged for anything else.
     */
    public static List<String> withResolvedFortifyLabel(Action action, List<String> args, String wallName) {
        if (wallName == null || wallName.isBlank()) return args;
        if (!fortifyEditsSavedWall(action, args) && !fortifyReadsSavedWall(action, args)) return args;
        if (args.size() != 2 || !args.get(1).isEmpty()) return args;
        return List.of(args.get(0), wallName);
    }

    // ── Dispatch refusal (pure part of AuthorizedBotDispatch) ───────────────

    /** Name of the greedy catch-all argument under {@code /bot}, which runs ungated inline text. */
    public static final String CATCH_ALL_NODE = "inline";

    /**
     * Why a parsed command must not run under a grant, or empty when it may. Codes:
     * {@code PARSE_ERROR}, {@code UNREAD_INPUT}, {@code NOT_EXECUTABLE}, {@code CATCH_ALL}
     * (any parsed node named {@value #CATCH_ALL_NODE}), {@code EMPTY}.
     */
    public static Optional<String> dispatchRefusal(boolean hasParseErrors, boolean unreadInput,
                                                   boolean executable, List<String> parsedNodeNames) {
        if (hasParseErrors) return Optional.of("PARSE_ERROR");
        if (unreadInput) return Optional.of("UNREAD_INPUT");
        if (parsedNodeNames == null || parsedNodeNames.isEmpty()) return Optional.of("EMPTY");
        if (parsedNodeNames.contains(CATCH_ALL_NODE)) return Optional.of("CATCH_ALL");
        if (!executable) return Optional.of("NOT_EXECUTABLE");
        return Optional.empty();
    }

    // ── Bot-slot check (pure part of AuthorizedBotDispatch, WARN-only) ─────

    /**
     * Argument names that bind the bot in the grammar of every {@link Action}'s target node:
     * {@code bot} (return, guard, patrol, craft, cook, companion), {@code bots} (come, go_to_look,
     * follow, follow toggle, follow-distance), {@code target} (stop, resume, standdown, sleep,
     * follow stop, leash, retrieve, fish forget, the toggles, hobby), {@code alias} (open) and
     * {@code bot_name} (compass home/travel, spawn). Not {@code player} (follow's target player)
     * and not {@code name} (a compass label or hobby name).
     */
    public static final Set<String> BOT_SLOT_ARGS = Set.of("bot", "bots", "target", "alias", "bot_name");

    /**
     * Greedy arguments that carry the bot as one token of a whitespace-split list ({@code skill},
     * {@code fish}, {@code fortify}). The skill parser finds the bot there, so a parse that bound
     * one of these is skipped, never reported.
     */
    public static final Set<String> BOT_SLOT_SKIP_ARGS = Set.of("arguments", "options");

    /**
     * Why the parsed command does not bind {@code resolvedName} in a bot slot, or empty when it
     * does (or the check does not apply). Codes: {@code NO_BOT_SLOT} (no bot-slot argument was
     * bound), {@code BOT_SLOT_OTHER_NAME:<arg>} (a bot slot holds another name), each followed by
     * {@code ;IN:<arg>} when a non-bot argument holds the resolved name (the 1.1.231 CRAFT bug).
     * A non-bot argument equal to the name is only reported when the bot slot is wrong, so
     * {@code compass home Bob Bob} (a compass named after its bot) is fine.
     *
     * @param boundStringArgs argument name to bound text (unquoted) across the context chain
     * @param botSlotNames    names that bind a bot ({@link #BOT_SLOT_ARGS})
     * @param resolvedName    the server-resolved bot name; null or blank skips (untargeted form)
     */
    public static Optional<String> botSlotRefusal(Map<String, String> boundStringArgs,
                                                  Set<String> botSlotNames, String resolvedName) {
        if (resolvedName == null || resolvedName.isBlank()) return Optional.empty();
        Map<String, String> bound = boundStringArgs == null ? Map.of() : boundStringArgs;
        Set<String> slots = botSlotNames == null ? Set.of() : botSlotNames;
        for (String name : bound.keySet()) {
            if (BOT_SLOT_SKIP_ARGS.contains(name)) return Optional.empty();
        }
        String slotHolder = null;
        String otherName = null;
        for (Map.Entry<String, String> e : bound.entrySet()) {
            if (!slots.contains(e.getKey())) continue;
            if (resolvedName.equalsIgnoreCase(e.getValue())) {
                return Optional.empty();
            }
            if (otherName == null) otherName = e.getKey();
        }
        for (Map.Entry<String, String> e : bound.entrySet()) {
            if (!slots.contains(e.getKey()) && resolvedName.equalsIgnoreCase(e.getValue())) {
                slotHolder = e.getKey();
                break;
            }
        }
        String code = otherName == null ? "NO_BOT_SLOT" : "BOT_SLOT_OTHER_NAME:" + otherName;
        return Optional.of(slotHolder == null ? code : code + ";IN:" + slotHolder);
    }

    // ── Throttle ────────────────────────────────────────────────────────────

    /**
     * Burst a player may send at once: ConfigureHobbiesScreen's "all on/off" sends one HOBBY_SET
     * per hobby (16) in a single click, and a held +/- button repeats fast.
     */
    public static final int THROTTLE_CAPACITY = 20;
    /** One token back every this many ms, i.e. a steady 10 actions per second. */
    public static final long THROTTLE_REFILL_MS = 100L;

    /** At most one "Slow down" notice per player this often. */
    public static final long THROTTLE_NOTICE_MS = 2000L;

    /** Per-player token bucket; not thread-safe (server thread only). */
    public static final class Throttle {
        private double tokens = THROTTLE_CAPACITY;
        private long lastMs = Long.MIN_VALUE;
        private long lastNoticeMs = Long.MIN_VALUE;

        /**
         * Whether a dropped click at {@code nowMs} should tell the player: the first time, then
         * at most once per {@value #THROTTLE_NOTICE_MS} ms. Records the notice when true.
         */
        public boolean shouldNotify(long nowMs) {
            if (lastNoticeMs != Long.MIN_VALUE && nowMs - lastNoticeMs < THROTTLE_NOTICE_MS) {
                return false;
            }
            lastNoticeMs = nowMs;
            return true;
        }

        /** Takes one token if available at {@code nowMs}. */
        public boolean tryAcquire(long nowMs) {
            if (lastMs != Long.MIN_VALUE && nowMs > lastMs) {
                tokens = Math.min(THROTTLE_CAPACITY, tokens + (nowMs - lastMs) / (double) THROTTLE_REFILL_MS);
            }
            lastMs = nowMs;
            if (tokens >= 1.0D) {
                tokens -= 1.0D;
                return true;
            }
            return false;
        }
    }
}
