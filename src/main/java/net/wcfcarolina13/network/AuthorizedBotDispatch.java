package net.wcfcarolina13.network;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.context.ParsedArgument;
import com.mojang.brigadier.context.ParsedCommandNode;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs one server-built {@code /bot ...} command as a player who may lack {@code /bot}
 * permission, under a per-call grant that makes {@code Frens.hasBotCommandPermission} pass for
 * that player only while the command is parsed and executed.
 *
 * <p>Only {@link BotActionNetworkManager} calls this, with a string from
 * {@link BotActionPolicy#build} for a bot it has already authorised. The command runs with the
 * player's own command source, so feedback, look-raycasts and follow targets stay theirs.
 *
 * <p>Threads: server thread only. The grant is a plain static field read by {@link #isGranted}
 * on the same thread; it is set before {@code dispatcher.parse} (Brigadier evaluates
 * {@code requires} while parsing and never again) and restored in {@code finally}, so it never
 * survives a worker hop or a later {@code server.execute}.
 *
 * <p>Queueing (verified with javap on 1.21.11 Yarn): {@code CommandManager.execute(ParseResults,
 * String)} is void and calls {@code callWithContext}. With no command execution context on the
 * thread it creates one, runs the command synchronously, closes it and clears the ThreadLocal.
 * With a context already active (we are inside another command) it only enqueues into that
 * context, so the command would run after this method's {@code finally}. That case is refused.
 */
public final class AuthorizedBotDispatch {

    private static final Logger LOGGER = LoggerFactory.getLogger("bot-access");

    /** Player the current dispatch is granted for, or null. Server thread only. */
    private static UUID grantedPlayer;

    private AuthorizedBotDispatch() {}

    /**
     * Parses and executes {@code command} (no leading slash) as {@code player} under the grant.
     *
     * @return 1 when the command was handed to the command manager (its own outcome reaches the
     *         player as command feedback, because {@code CommandManager.execute} is void and
     *         reports errors to the source itself); 0 when it was refused: parse error, unread
     *         input, not executable, the {@code inline} catch-all, or a command context already
     *         active on this thread
     * @throws IllegalStateException when called off the server thread
     */
    public static int run(ServerPlayerEntity player, String command) {
        return run(player, command, null, null);
    }

    /**
     * Returned by {@link #run(ServerPlayerEntity, String, String, String)} when the parse did not
     * bind the resolved bot in a bot slot, or the check itself failed. Nothing ran.
     */
    public static final int REFUSED_BOT_SLOT = -1;

    /**
     * {@link #run(ServerPlayerEntity, String)} plus a fail-closed check that the parse bound
     * {@code resolvedBotName} in a bot slot ({@link BotActionPolicy#botSlotVerdict} over
     * {@link BotActionPolicy#botSlotRefusal}). A mismatch, or a check that throws, logs
     * {@code [bot-access] refused granted dispatch ... bot-slot mismatch} at WARN and the command
     * does not run (1.1.296; WARN-only from 1.1.232).
     *
     * @param resolvedBotName the server-resolved bot name, or null for the untargeted form (skipped)
     * @param actionName      the UI action, for the log line only (may be null)
     * @return 1 when handed to the command manager, 0 for the refusals of the two-argument form,
     *         {@link #REFUSED_BOT_SLOT} when the bot-slot check refused
     */
    public static int run(ServerPlayerEntity player, String command, String resolvedBotName, String actionName) {
        if (player == null || command == null || command.isBlank()) {
            return 0;
        }
        ServerCommandSource source = player.getCommandSource();
        MinecraftServer server = source.getServer();
        if (server == null || !server.isOnThread()) {
            throw new IllegalStateException("AuthorizedBotDispatch.run must be called on the server thread");
        }
        if (isCommandContextActive(source)) {
            LOGGER.warn("[bot-access] refused granted dispatch for {}: a command is already executing on this "
                    + "thread, so the command would be queued past the grant: {}",
                    logName(player), command);
            return 0;
        }
        CommandManager manager = server.getCommandManager();
        UUID previous = grantedPlayer;
        grantedPlayer = player.getUuid();
        try {
            ParseResults<ServerCommandSource> parse = manager.getDispatcher().parse(command, source);
            CommandContextBuilder<ServerCommandSource> deepest = parse.getContext();
            List<String> names = new ArrayList<>();
            for (CommandContextBuilder<ServerCommandSource> b = parse.getContext(); b != null; b = b.getChild()) {
                for (ParsedCommandNode<ServerCommandSource> node : b.getNodes()) {
                    names.add(node.getNode().getName());
                }
                deepest = b;
            }
            Optional<String> refusal = BotActionPolicy.dispatchRefusal(
                    !parse.getExceptions().isEmpty(),
                    parse.getReader().canRead(),
                    deepest.getCommand() != null,
                    names);
            if (refusal.isPresent()) {
                LOGGER.warn("[bot-access] refused granted dispatch for {}: {} in '{}'",
                        logName(player), refusal.get(), command);
                return 0;
            }
            // Fail closed: a mismatch or a check that throws refuses the dispatch.
            BotActionPolicy.BotSlotVerdict verdict = BotActionPolicy.botSlotVerdict(resolvedBotName,
                    () -> BotActionPolicy.botSlotRefusal(
                            boundArguments(parse, command), BotActionPolicy.BOT_SLOT_ARGS, resolvedBotName));
            if (verdict.refuse()) {
                LOGGER.warn("[bot-access] refused granted dispatch for {}: bot-slot mismatch action={} bot={} reason={} cmd={}",
                        logName(player), actionName, resolvedBotName, verdict.reason(), command);
                return REFUSED_BOT_SLOT;
            }
            manager.execute(parse, command);
            return 1;
        } finally {
            grantedPlayer = previous;
        }
    }

    /**
     * Every bound argument across the context chain, by node name: a String result as parsed,
     * anything else (an entity selector, a position) as its input text, unquoted. Numbers are
     * left out, so a radius cannot look like a bot called "8".
     */
    private static Map<String, String> boundArguments(ParseResults<ServerCommandSource> parse, String command) {
        Map<String, String> out = new LinkedHashMap<>();
        for (CommandContextBuilder<ServerCommandSource> b = parse.getContext(); b != null; b = b.getChild()) {
            for (Map.Entry<String, ParsedArgument<ServerCommandSource, ?>> e : b.getArguments().entrySet()) {
                Object result = e.getValue().getResult();
                if (result instanceof Number || result instanceof Boolean) {
                    continue;
                }
                String text;
                if (result instanceof String str) {
                    text = str;
                } else {
                    try {
                        text = unquote(e.getValue().getRange().get(command));
                    } catch (RuntimeException ex) {
                        continue;
                    }
                }
                out.put(e.getKey(), text);
            }
        }
        return out;
    }

    private static String unquote(String raw) {
        if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            return raw.substring(1, raw.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return raw;
    }

    /**
     * True only on the server thread, during a {@link #run} for this source's player. Read by
     * {@code Frens.hasBotCommandPermission}; never by {@code BotAccessGate}/{@code BotAccessPolicy},
     * which use {@code Frens.isOperator}.
     */
    public static boolean isGranted(ServerCommandSource source) {
        UUID granted = grantedPlayer;
        if (granted == null || source == null) {
            return false;
        }
        MinecraftServer server = source.getServer();
        if (server == null || !server.isOnThread()) {
            return false;
        }
        ServerPlayerEntity player = source.getPlayer();
        return player != null && granted.equals(player.getUuid());
    }

    /**
     * Whether a command execution context is already active on this thread. {@code
     * CommandManager.CURRENT_CONTEXT} is private, so instead of reflection this asks
     * {@code callWithContext} twice: with no active context each call creates (and closes) a
     * fresh, empty one, so the two differ; with one active both calls see the same instance.
     * Neither probe enqueues anything.
     */
    private static boolean isCommandContextActive(ServerCommandSource source) {
        Object[] seen = new Object[2];
        CommandManager.callWithContext(source, ctx -> seen[0] = ctx);
        CommandManager.callWithContext(source, ctx -> seen[1] = ctx);
        return seen[0] != null && seen[0] == seen[1];
    }

    private static String logName(ServerPlayerEntity p) {
        return p == null ? "null" : net.wcfcarolina13.GameAI.services.BotAccessPolicy.logSafe(p.getName().getString());
    }
}
