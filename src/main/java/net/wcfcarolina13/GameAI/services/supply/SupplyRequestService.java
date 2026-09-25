package net.wcfcarolina13.GameAI.services.supply;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.component.ComponentType;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.GameAI.services.BlockInteractionService;
import net.wcfcarolina13.GameAI.services.BotChestRegistryService;
import net.wcfcarolina13.GameAI.services.BotTerritoryAuthorizationService;
import net.wcfcarolina13.GameAI.services.BotWorldStateService;
import net.wcfcarolina13.GameAI.services.CompanionCommunicationPolicy;
import net.wcfcarolina13.GameAI.services.ProtectedZoneService;
import net.wcfcarolina13.GameAI.services.supply.SupplyChestRules.Access;
import net.wcfcarolina13.GameAI.services.supply.SupplyChestRules.AccessFacts;
import net.wcfcarolina13.GameAI.services.supply.SupplyChestRules.SlotView;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestLedger.AlwaysScope;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestLedger.Consume;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestLedger.OpenResult;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestLedger.OpenStatus;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestLedger.Response;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestLedger.ResponseStatus;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.ChestKey;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.Choice;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.ItemKey;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.Pos;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.RequestFingerprint;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.Stock;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.Verdict;
import net.wcfcarolina13.PlayerUtils.DebouncedWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Server adapter for companion supply requests: reads chests, stacks and players on the server
 * thread, turns them into the plain facts {@link SupplyChestRules} and {@link SupplyRequestLedger}
 * decide on, and acts on the answer — the owner's prompt, the clickable reply, the item move and
 * the per-save file of standing "always" permissions.
 *
 * <p><b>Dormant (supplies Phase 2).</b> {@link #request} and {@link #transferNow} have no
 * production caller: no skill, service, tick hook or command reaches them, and
 * {@code SupplyDormancyTest} scans the source tree to keep it that way. They stay unwired until
 * Phase 3 has closed the existing automatic chest withdrawals. What is live now: loading and
 * saving the ALWAYS file, and {@link #answer} / {@link #revoke} behind {@code /frens supply},
 * which are inert while no request can be opened.
 *
 * <p><b>Threading.</b> Every public method except {@link #register()} and {@link #isRunning()}
 * must run on the server thread; called anywhere else it logs a WARN and returns its failure
 * value without touching the world. The ledger itself is synchronized, so the debounced writer
 * may snapshot it from its own thread.
 */
public final class SupplyRequestService {

    private static final Logger LOGGER = LoggerFactory.getLogger("frens-supply");

    /** The owner must be at most this far from the bot, in the same world, to be prompted. */
    public static final double OWNER_PROMPT_RANGE_BLOCKS = 32.0D;

    private static final String ALWAYS_FILE = "supply_always.json";
    private static final long WRITE_QUIET_MS = 500L;
    private static final long WRITE_MAX_LATENCY_MS = 5_000L;
    /** How long SERVER_STOPPING waits for a write already in flight before giving up on it. */
    private static final long WRITER_STOP_WAIT_MS = 2_000L;

    /** Component-map key for a component type missing from the registry; never allowlisted. */
    private static final String UNREGISTERED_COMPONENT = "frens:unregistered_component";
    /** Fingerprint value for a component with no codec (transient) or one that failed to encode. */
    private static final String NO_CODEC_MARKER = "#no-codec";
    private static final String ENCODE_FAILED_MARKER = "#encode-failed";

    private static volatile MinecraftServer server;
    private static volatile SupplyRequestLedger ledger;
    private static volatile DebouncedWriter writer;
    private static volatile ScheduledExecutorService writeExecutor;

    private SupplyRequestService() {
    }

    // ── Outcomes ─────────────────────────────────────────────────────────────────────────────

    /** What {@link #request} did. The first six mirror {@link OpenStatus}; the rest are the adapter's own. */
    public enum RequestStatus {
        /** A prompt was opened and sent to the owner. */
        OPENED,
        /** This bot already has a prompt waiting. */
        DUPLICATE_PENDING,
        /** This bot prompted too recently. */
        PROMPT_COOLDOWN,
        /** The owner recently said No to this bot, chest and item. */
        REJECT_COOLDOWN,
        /** A standing permission covers it: no prompt; the caller may go to {@link #transferNow}. */
        COVERED_BY_ALWAYS,
        /** The policy refused it (see {@link RequestOutcome#verdict()}), including {@code NO_OWNER}. */
        INELIGIBLE,
        /**
         * No standing permission covers the chest, and the owner is offline, in another world, or
         * further than {@link #OWNER_PROMPT_RANGE_BLOCKS}.
         */
        OWNER_NOT_NEARBY,
        /** The chest may not be touched (see {@link RequestOutcome#access()}). */
        DENIED,
        /** A required argument was missing (no bot, no position, empty sample). */
        INVALID,
        /** The service has not started (no server, or between worlds). */
        NOT_RUNNING,
        /** Called off the server thread; nothing was read or changed. */
        WRONG_THREAD
    }

    /**
     * @param status  what happened
     * @param ledger  the ledger's answer when it was consulted, else {@code null}; for
     *                {@link RequestStatus#OPENED} it carries the request id and the approved
     *                fingerprint, for {@link RequestStatus#COVERED_BY_ALWAYS} the fingerprint to
     *                hand to {@link #transferNow}
     * @param access  the access verdict when the chest was examined, else {@code null}
     * @param verdict the policy verdict when the policy was consulted, else {@code null}
     */
    public record RequestOutcome(RequestStatus status, OpenResult ledger, Access access, Verdict verdict) {
        static RequestOutcome of(RequestStatus status) {
            return new RequestOutcome(status, null, null, null);
        }

        public UUID requestId() {
            return ledger == null ? null : ledger.requestId();
        }

        public RequestFingerprint fingerprint() {
            return ledger == null ? null : ledger.fingerprint();
        }

        String logText() {
            if (status == RequestStatus.DENIED && access != null) {
                return status + "(" + access + ")";
            }
            if (status == RequestStatus.INELIGIBLE && verdict != null) {
                return status + "(" + verdict + ")";
            }
            if (status == RequestStatus.OPENED && requestId() != null) {
                return status + " id=" + requestId();
            }
            return status.name();
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────────────────────

    /** Hooks server start and stop. Called once from mod init. */
    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(SupplyRequestService::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> stop());
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
            ledger = null;
            writer = null;
            writeExecutor = null;
            server = null;
        });
    }

    /** True between server start and stop. */
    public static boolean isRunning() {
        return server != null && ledger != null;
    }

    private static void start(MinecraftServer started) {
        SupplyRequestLedger fresh = new SupplyRequestLedger(System::currentTimeMillis, UUID::randomUUID,
                SupplyRequestLedger.Timings.defaults(), SupplyRequestPolicy.Config.defaults());
        Path file = started.getSavePath(WorldSavePath.ROOT).resolve("frens").resolve(ALWAYS_FILE);
        int restored = 0;
        if (Files.exists(file)) {
            try {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                for (AlwaysScope scope : SupplyChestRules.decodeAlways(json,
                        BotWorldStateService.currentWorldKey(started))) {
                    fresh.restoreAlways(scope.owner(), scope.chest());
                    restored++;
                }
                Integer version = SupplyChestRules.alwaysFileVersion(json);
                if (restored == 0 || version == null || version != SupplyChestRules.ALWAYS_FORMAT_VERSION) {
                    // One line, counts and version only: the file's contents are never logged.
                    // Whatever did decode stays restored; the next change rewrites the file.
                    LOGGER.warn("[supply] {} gave {} standing permission(s), format version {} (expected {});"
                                    + " anything unreadable is dropped at the next write",
                            file, restored, version == null ? "missing" : version,
                            SupplyChestRules.ALWAYS_FORMAT_VERSION);
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("[supply] could not read {}; starting with no standing permissions: {}",
                        file, e.toString());
            }
        }
        // A new executor per start: an integrated server's exit-to-title keeps statics alive after
        // SERVER_STOPPING shut the previous one down. The write captures this start's ledger and
        // file, so a late write can never land a stale ledger in another save.
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "frens-supply-writer");
            t.setDaemon(true);
            return t;
        });
        DebouncedWriter w = new DebouncedWriter(() -> writeAlways(fresh, file),
                WRITE_QUIET_MS, WRITE_MAX_LATENCY_MS, exec);
        writeExecutor = exec;
        writer = w;
        ledger = fresh;
        server = started;
        LOGGER.info("[supply] started: {} standing permission(s) restored", restored);
    }

    private static void stop() {
        DebouncedWriter w = writer;
        if (w != null) {
            w.flushNow();
            w.shutdown();
        }
        ScheduledExecutorService exec = writeExecutor;
        if (exec != null) {
            // shutdown(), not shutdownNow(): a write already running on the writer thread may
            // hold the last change (flushNow saw nothing dirty because that write took it), and
            // interrupting it would lose that change. The writer cancelled its pending task, so
            // this waits only for a write in flight, and never longer than the bound.
            exec.shutdown();
            try {
                if (!exec.awaitTermination(WRITER_STOP_WAIT_MS, TimeUnit.MILLISECONDS)) {
                    LOGGER.warn("[supply] standing-permission write still running after {} ms at stop",
                            WRITER_STOP_WAIT_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** The whole-file write: temp file, then an atomic move over the old one. Writer thread only. */
    private static void writeAlways(SupplyRequestLedger source, Path file) {
        String json = SupplyChestRules.encodeAlways(source.alwaysSnapshot());
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // DebouncedWriter logs this and stays dirty, so the next mark or flush retries.
            throw new UncheckedIOException(e);
        }
    }

    // ── Request ──────────────────────────────────────────────────────────────────────────────

    /**
     * Asks {@code bot}'s owner whether it may take {@code qty} of {@code sample}'s exact item from
     * the chest at {@code chestPos}. DORMANT: no production caller (see the class comment).
     *
     * <p>Order: owner (none → the policy's {@code NO_OWNER}; never the recruiter or controller) →
     * chest access ({@link SupplyChestRules#access}) → unless a standing permission already covers
     * this owner and chest, the owner online, in the bot's world and within
     * {@link #OWNER_PROMPT_RANGE_BLOCKS} → the ledger. So {@link RequestStatus#DENIED} comes before
     * {@link RequestStatus#OWNER_NOT_NEARBY}, and "always" really means without asking: the owner
     * need not be anywhere near. On {@link RequestStatus#OPENED} the owner gets one message with
     * three clickable answers.
     *
     * @param sample a stack of the exact item wanted (id and components), usually read from the chest
     * @param qty    how many the bot asks for
     * @param need   how many it actually needs
     */
    public static RequestOutcome request(ServerPlayerEntity bot, BlockPos chestPos, ItemStack sample,
                                         int qty, int need) {
        RequestOutcome outcome = evaluateRequest(bot, chestPos, sample, qty, need);
        LOGGER.info("[supply] request bot={} chest={} item={} qty={} need={} outcome={}",
                nameOf(bot), posText(chestPos), itemIdOf(sample), qty, need, outcome.logText());
        return outcome;
    }

    private static RequestOutcome evaluateRequest(ServerPlayerEntity bot, BlockPos chestPos, ItemStack sample,
                                                  int qty, int need) {
        if (offServerThread("request")) {
            return RequestOutcome.of(RequestStatus.WRONG_THREAD);
        }
        SupplyRequestLedger book = ledger;
        MinecraftServer srv = server;
        if (book == null || srv == null) {
            return RequestOutcome.of(RequestStatus.NOT_RUNNING);
        }
        if (bot == null || chestPos == null || sample == null || sample.isEmpty()) {
            return RequestOutcome.of(RequestStatus.INVALID);
        }
        ServerWorld world = bot.getEntityWorld();
        DynamicOps<JsonElement> ops = world.getRegistryManager().getOps(JsonOps.INSTANCE);
        ItemKey item = itemKey(sample, ops);

        UUID owner = CompanionCommunicationPolicy.resolveOwnerUuid(bot);
        if (owner == null) {
            // The policy, not the adapter, names this outcome; nothing is read or recorded.
            Verdict verdict = SupplyRequestPolicy.assess(null, item, Stock.of(0), qty, need, book.config()).verdict();
            return new RequestOutcome(RequestStatus.INELIGIBLE, null, null, verdict);
        }

        ChestRead chest = readChest(srv, world, chestPos, bot, owner);
        if (chest.access() != Access.OK) {
            return new RequestOutcome(RequestStatus.DENIED, null, chest.access(), null);
        }
        // A standing permission needs no prompt, so it needs no owner nearby either. Only an
        // uncovered request, the one that may prompt, requires the owner in range.
        ServerPlayerEntity ownerPlayer = null;
        if (!book.hasAlways(owner, chest.key())) {
            ownerPlayer = srv.getPlayerManager().getPlayer(owner);
            if (ownerPlayer == null
                    || !CompanionCommunicationPolicy.isWithinVisibleRange(bot, ownerPlayer, OWNER_PROMPT_RANGE_BLOCKS)) {
                return RequestOutcome.of(RequestStatus.OWNER_NOT_NEARBY);
            }
        }
        Stock stock = SupplyChestRules.stock(slotViews(chest.inventory(), item.itemId(), ops), item, book.config());
        RequestFingerprint fp = new RequestFingerprint(owner, bot.getUuid(), chest.key(), item, qty);

        book.sweep();
        OpenResult opened = book.open(fp, stock, need);
        RequestOutcome outcome = new RequestOutcome(statusOf(opened.status()), opened, Access.OK, opened.verdict());
        // A covered request never opens a prompt (the ledger checks "always" before prompting), so
        // ownerPlayer is set whenever this sends; the null check only keeps that fail-closed.
        if (opened.status() == OpenStatus.OPENED && ownerPlayer != null
                && opened.requestId() != null && opened.fingerprint() != null) {
            sendPrompt(ownerPlayer, bot, sample, chestPos, opened.requestId(), opened.fingerprint().qty());
        }
        return outcome;
    }

    private static RequestStatus statusOf(OpenStatus status) {
        return switch (status) {
            case OPENED -> RequestStatus.OPENED;
            case DUPLICATE_PENDING -> RequestStatus.DUPLICATE_PENDING;
            case PROMPT_COOLDOWN -> RequestStatus.PROMPT_COOLDOWN;
            case REJECT_COOLDOWN -> RequestStatus.REJECT_COOLDOWN;
            case COVERED_BY_ALWAYS -> RequestStatus.COVERED_BY_ALWAYS;
            case INELIGIBLE -> RequestStatus.INELIGIBLE;
        };
    }

    private static void sendPrompt(ServerPlayerEntity owner, ServerPlayerEntity bot, ItemStack sample,
                                   BlockPos pos, UUID requestId, int qty) {
        String botName = nameOf(bot);
        String itemName = sample.getName().getString();
        MutableText message = Text.literal(SupplyChestRules.promptText(botName, qty, itemName,
                pos.getX(), pos.getY(), pos.getZ()) + " ");
        message.append(choiceButton("[Allow once]", Formatting.GREEN, requestId, Choice.ALLOW_ONCE,
                "Let " + botName + " take exactly " + qty + " " + itemName + " from this chest, once."));
        message.append(Text.literal(" "));
        message.append(choiceButton("[Always: common supplies, this chest]", Formatting.GOLD, requestId,
                Choice.ALWAYS_COMMON,
                "Let your companions take common supplies from this chest without asking. Undo with "
                        + SupplyChestRules.revokeCommand(pos.getX(), pos.getY(), pos.getZ())));
        message.append(Text.literal(" "));
        message.append(choiceButton("[No]", Formatting.RED, requestId, Choice.NO,
                "Refuse. " + botName + " won't ask for this here again for a while."));
        owner.sendMessage(message, false);
    }

    private static MutableText choiceButton(String label, Formatting color, UUID requestId, Choice choice,
                                            String hover) {
        return Text.literal(label).styled(s -> s
                .withColor(color)
                .withBold(true)
                .withClickEvent(new ClickEvent.RunCommand(SupplyChestRules.answerCommand(requestId, choice)))
                .withHoverEvent(new HoverEvent.ShowText(Text.literal(hover))));
    }

    // ── Answer ───────────────────────────────────────────────────────────────────────────────

    /**
     * Records {@code responder}'s answer to prompt {@code requestId} and tells them, in plain
     * words, what it did. Only the bot's owner may answer: anyone else gets
     * {@link ResponseStatus#FOREIGN_CALLER} and the prompt is left exactly as it was.
     *
     * @return the ledger's response; {@link ResponseStatus#NOT_FOUND} when the service is not
     *         running or this is not the server thread
     */
    public static Response answer(ServerPlayerEntity responder, UUID requestId, Choice choice) {
        if (offServerThread("answer")) {
            return new Response(ResponseStatus.NOT_FOUND, null);
        }
        SupplyRequestLedger book = ledger;
        if (book == null || responder == null || requestId == null || choice == null) {
            LOGGER.info("[supply] answer responder={} request={} result=NOT_RUNNING_OR_INVALID",
                    nameOf(responder), requestId);
            return new Response(ResponseStatus.NOT_FOUND, null);
        }
        Response response = book.respond(requestId, responder.getUuid(), Frens.isOperator(responder), choice);
        if (response.status() == ResponseStatus.GRANTED_ALWAYS) {
            markDirty();
        }
        responder.sendMessage(Text.literal(replyText(response)), false);
        LOGGER.info("[supply] answer responder={} request={} choice={} result={}",
                nameOf(responder), requestId, SupplyChestRules.choiceToken(choice), response.status());
        return response;
    }

    private static String replyText(Response response) {
        RequestFingerprint fp = response.fingerprint();
        String bot = fp == null ? "Your companion" : botNameOf(fp.bot());
        String item = fp == null ? "items" : itemNameOf(fp.itemId());
        int qty = fp == null ? 0 : fp.qty();
        return switch (response.status()) {
            case GRANTED_ONCE -> "Allowed: " + bot + " may take " + qty + " " + item + " from that chest, this once.";
            case GRANTED_ALWAYS -> "Allowed: your companions may take common supplies from that chest"
                    + " until you revoke it (" + revokeHint(fp) + ").";
            case REJECTED -> "Refused: " + bot + " won't ask for " + item + " from that chest again for a while.";
            case EXPIRED -> "That request timed out before your answer. Nothing was taken.";
            case NOT_FOUND -> "That request is no longer open.";
            case FOREIGN_CALLER -> "That request isn't yours to answer.";
        };
    }

    private static String revokeHint(RequestFingerprint fp) {
        ChestKey chest = fp == null ? null : fp.chest();
        return chest == null ? "/" + SupplyChestRules.COMMAND_ROOT + " " + SupplyChestRules.COMMAND_SUPPLY
                + " " + SupplyChestRules.COMMAND_REVOKE
                : SupplyChestRules.revokeCommand(chest.x(), chest.y(), chest.z());
    }

    // ── Transfer ─────────────────────────────────────────────────────────────────────────────

    /**
     * Moves the granted items from the chest into {@code bot}'s inventory, now, in this tick.
     * DORMANT: no production caller (see the class comment).
     *
     * <p>Re-checks everything against the world as it is now: the bot's current owner must still
     * be {@code fp.owner()} and the chest at {@code chestPos} must be {@code fp.chest()}; access,
     * reach, stock and inventory room are read fresh. If the bot has no room, nothing is consumed.
     * Otherwise the grant is consumed for at most the room available and exactly the permitted
     * quantity of stacks matching {@code fp}'s item id and component fingerprint is moved.
     *
     * @return how many items were moved (0 on any refusal)
     */
    public static int transferNow(ServerPlayerEntity bot, BlockPos chestPos, RequestFingerprint fp, int need) {
        TransferResult result = evaluateTransfer(bot, chestPos, fp, need);
        LOGGER.info("[supply] transfer bot={} chest={} item={} qty={} need={} moved={} status={}",
                nameOf(bot), posText(chestPos), fp == null ? "-" : fp.itemId(), fp == null ? 0 : fp.qty(),
                need, result.moved(), result.status());
        return result.moved();
    }

    private record TransferResult(int moved, String status) {
        static TransferResult refused(String status) {
            return new TransferResult(0, status);
        }
    }

    private static TransferResult evaluateTransfer(ServerPlayerEntity bot, BlockPos chestPos,
                                                   RequestFingerprint fp, int need) {
        if (offServerThread("transferNow")) {
            return TransferResult.refused("WRONG_THREAD");
        }
        SupplyRequestLedger book = ledger;
        MinecraftServer srv = server;
        if (book == null || srv == null) {
            return TransferResult.refused("NOT_RUNNING");
        }
        if (bot == null || chestPos == null || fp == null) {
            return TransferResult.refused("INVALID");
        }
        UUID owner = CompanionCommunicationPolicy.resolveOwnerUuid(bot);
        if (owner == null || !owner.equals(fp.owner()) || !bot.getUuid().equals(fp.bot())) {
            return TransferResult.refused("OWNER_OR_BOT_MISMATCH");
        }
        ServerWorld world = bot.getEntityWorld();
        ChestRead chest = readChest(srv, world, chestPos, bot, owner);
        if (chest.access() != Access.OK) {
            return TransferResult.refused("DENIED(" + chest.access() + ")");
        }
        if (!fp.chest().equals(chest.key())) {
            return TransferResult.refused("CHEST_MISMATCH");
        }
        if (!BlockInteractionService.canInteract(bot, chestPos)) {
            return TransferResult.refused("OUT_OF_REACH");
        }
        DynamicOps<JsonElement> ops = world.getRegistryManager().getOps(JsonOps.INSTANCE);
        Inventory storage = chest.inventory();
        List<SlotView> slots = slotViews(storage, fp.itemId(), ops);
        Stock stockNow = SupplyChestRules.stock(slots, fp.item(), book.config());
        List<SupplyChestRules.Take> first = SupplyChestRules.withdrawPlan(slots, fp.item(), 1);
        if (first.isEmpty()) {
            return TransferResult.refused("NO_STOCK");
        }
        ItemStack template = storage.getStack(first.get(0).slot());
        int maxPerStack = bot.getInventory().getMaxCount(template);
        int capacity = SupplyChestRules.capacity(slotViews(bot.getInventory().getMainStacks(), fp.itemId(), ops),
                fp.item(), maxPerStack);
        if (capacity <= 0) {
            return TransferResult.refused("NO_ROOM");
        }
        RequestFingerprint capped = fp.withQty(Math.min(fp.qty(), capacity));
        Consume consume = book.consumeGrant(capped, stockNow, need);
        if (!consume.permitted()) {
            return TransferResult.refused(consume.status() + "(" + consume.verdict() + ")");
        }
        int quantity = consume.quantity();
        int moved = 0;
        for (SupplyChestRules.Take take : SupplyChestRules.withdrawPlan(slots, fp.item(), quantity)) {
            ItemStack stack = storage.getStack(take.slot());
            if (stack == null || stack.isEmpty()) {
                break;
            }
            ItemStack extracted = stack.copyWithCount(take.count());
            bot.getInventory().insertStack(extracted);
            int inserted = take.count() - extracted.getCount();
            if (inserted <= 0) {
                break;
            }
            stack.decrement(inserted);
            storage.setStack(take.slot(), stack.isEmpty() ? ItemStack.EMPTY : stack);
            moved += inserted;
            if (inserted < take.count()) {
                break;
            }
        }
        if (moved > 0) {
            storage.markDirty();
        }
        if (moved < quantity) {
            LOGGER.warn("[supply] transfer moved {} of {} permitted for bot={}; the grant is spent",
                    moved, quantity, nameOf(bot));
            return new TransferResult(moved, consume.status() + "_SHORT");
        }
        return new TransferResult(moved, consume.status().name());
    }

    // ── Revoke ───────────────────────────────────────────────────────────────────────────────

    /**
     * Withdraws {@code owner}'s standing permission for the chest at {@code pos} in the owner's
     * current world (either half of a double chest names the same chest). It clears every key
     * from {@link SupplyChestRules#revokeKeys}: the chest's key now and each half's own key, so a
     * permission granted before the chest gained or lost a half is withdrawn too.
     *
     * @return whether a permission was removed; {@code false} also when the service is not
     *         running or this is not the server thread
     */
    public static boolean revoke(ServerPlayerEntity owner, BlockPos pos) {
        if (offServerThread("revoke")) {
            return false;
        }
        SupplyRequestLedger book = ledger;
        MinecraftServer srv = server;
        if (book == null || srv == null || owner == null || pos == null) {
            return false;
        }
        ServerWorld world = owner.getEntityWorld();
        BlockPos partner = partnerOf(world, pos);
        boolean removed = false;
        for (ChestKey key : SupplyChestRules.revokeKeys(worldIdOf(srv, world), toPos(pos),
                partner == null ? null : toPos(partner))) {
            removed |= book.revokeAlways(owner.getUuid(), key);
        }
        if (removed) {
            markDirty();
        }
        LOGGER.info("[supply] revoke owner={} chest={} removed={}", nameOf(owner), posText(pos), removed);
        return removed;
    }

    // ── Chest facts ──────────────────────────────────────────────────────────────────────────

    /**
     * What {@link #readChest} found: the access verdict, the canonical key (null when the chest
     * could not be resolved), and the merged inventory (null unless access is OK).
     */
    private record ChestRead(Access access, ChestKey key, Inventory inventory) {
        static ChestRead denied(Access access) {
            return new ChestRead(access, null, null);
        }
    }

    /**
     * Reads every access fact for the chest at {@code posA} and, for a double chest, its partner,
     * without loading a chunk: an unloaded half stops the read before its block is touched.
     */
    private static ChestRead readChest(MinecraftServer srv, ServerWorld world, BlockPos posA,
                                       ServerPlayerEntity bot, UUID owner) {
        if (world == null || posA == null || !world.isChunkLoaded(posA)) {
            return ChestRead.denied(Access.DENY_UNLOADED);
        }
        BlockState stateA = world.getBlockState(posA);
        if (!(stateA.getBlock() instanceof ChestBlock chestBlock)) {
            return ChestRead.denied(Access.DENY_NOT_CHEST);
        }
        BlockPos posB = null;
        if (stateA.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE) {
            posB = posA.offset(ChestBlock.getFacing(stateA));
            if (!world.isChunkLoaded(posB)) {
                return ChestRead.denied(Access.DENY_UNLOADED);
            }
        }
        boolean isDouble = posB != null;
        BlockEntity entityA = world.getBlockEntity(posA);
        BlockEntity entityB = isDouble ? world.getBlockEntity(posB) : null;
        boolean chestEntities = entityA instanceof ChestBlockEntity
                && (!isDouble || (entityB instanceof ChestBlockEntity && isPartner(stateA, world.getBlockState(posB))));
        if (!chestEntities) {
            return ChestRead.denied(Access.DENY_NOT_CHEST);
        }
        ChestBlockEntity chestA = (ChestBlockEntity) entityA;
        ChestBlockEntity chestB = isDouble ? (ChestBlockEntity) entityB : null;

        String dimension = world.getRegistryKey().getValue().toString();
        boolean zonesLoaded = ProtectedZoneService.isLoaded(dimension);
        boolean territoryA = territoryAllows(bot, world, posA);
        boolean territoryB = !isDouble || territoryAllows(bot, world, posB);
        boolean blocked = ChestBlock.getInventory(chestBlock, stateA, world, posA, false) == null;
        // Footgun: a single chest passes null for half B. recordedOwnersAt(world, null) fails closed
        // with [null], which would read as DENY_UNKNOWN for every single chest.
        SupplyChestRules.Ownership ownership = SupplyChestRules.ownership(owner,
                BotChestRegistryService.recordedOwnersAt(world, posA),
                isDouble ? BotChestRegistryService.recordedOwnersAt(world, posB) : null);
        AccessFacts facts = new AccessFacts(true, true, zonesLoaded,
                chestA.isLocked(), chestB != null && chestB.isLocked(),
                territoryA, territoryB, blocked, ownership);
        Access access = SupplyChestRules.access(facts);
        ChestKey key = chestKey(srv, world, posA, posB);
        if (access != Access.OK) {
            return new ChestRead(access, key, null);
        }
        Inventory inventory = ChestBlock.getInventory(chestBlock, stateA, world, posA, true);
        if (inventory == null) {
            return new ChestRead(Access.DENY_BLOCKED, key, null);
        }
        return new ChestRead(Access.OK, key, inventory);
    }

    /**
     * Whether {@code b} is the other half of {@code a}'s double chest, by vanilla's merge rule:
     * each half's block accepts the other ({@link ChestBlock#canMergeWith}: the same block for a
     * plain chest, any copper chest for a copper one, whatever its oxidation or wax), both face
     * the same way, and the halves are opposite. Anything else, a state without the chest
     * properties included, is not a partner.
     *
     * <p>Copper halves at different stages are one chest here, and vanilla re-syncs them to one
     * block on the next neighbour update. Until it does, {@code ChestBlock.getInventory}, like
     * vanilla's own chest screen, merges only identical blocks and returns the clicked half
     * alone. The key, locks, territory and ownership still cover both halves; the smaller stock
     * read can only mean fewer items taken.
     */
    private static boolean isPartner(BlockState a, BlockState b) {
        if (a == null || b == null
                || !(a.getBlock() instanceof ChestBlock blockA) || !(b.getBlock() instanceof ChestBlock blockB)
                || !hasChestProperties(a) || !hasChestProperties(b)
                || !blockA.canMergeWith(b) || !blockB.canMergeWith(a)) {
            return false;
        }
        ChestType typeA = a.get(ChestBlock.CHEST_TYPE);
        ChestType typeB = b.get(ChestBlock.CHEST_TYPE);
        return typeA != ChestType.SINGLE && typeB == typeA.getOpposite()
                && a.get(ChestBlock.FACING) == b.get(ChestBlock.FACING);
    }

    private static boolean hasChestProperties(BlockState state) {
        return state.contains(ChestBlock.CHEST_TYPE) && state.contains(ChestBlock.FACING);
    }

    /** The other half of a loaded double chest at {@code pos}, or {@code null}. Never loads a chunk. */
    private static BlockPos partnerOf(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null || !world.isChunkLoaded(pos)) {
            return null;
        }
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock) || state.get(ChestBlock.CHEST_TYPE) == ChestType.SINGLE) {
            return null;
        }
        BlockPos partner = pos.offset(ChestBlock.getFacing(state));
        if (!world.isChunkLoaded(partner) || !isPartner(state, world.getBlockState(partner))) {
            return null;
        }
        return partner;
    }

    private static boolean territoryAllows(ServerPlayerEntity bot, ServerWorld world, BlockPos pos) {
        // authorizeBlockMutation returns ALLOW on null arguments; a missing argument must deny here.
        if (bot == null || world == null || pos == null) {
            return false;
        }
        return BotTerritoryAuthorizationService.authorizeBlockMutation(bot, world, pos).allowed();
    }

    private static ChestKey chestKey(MinecraftServer srv, ServerWorld world, BlockPos posA, BlockPos posBOrNull) {
        return ChestKey.canonical(worldIdOf(srv, world), toPos(posA), posBOrNull == null ? null : toPos(posBOrNull));
    }

    private static String worldIdOf(MinecraftServer srv, ServerWorld world) {
        return SupplyChestRules.worldId(BotWorldStateService.currentWorldKey(srv),
                world.getRegistryKey().getValue().toString());
    }

    private static Pos toPos(BlockPos p) {
        return new Pos(p.getX(), p.getY(), p.getZ());
    }

    // ── Stacks ───────────────────────────────────────────────────────────────────────────────

    /** The policy's view of a live stack: registry id plus a fingerprint of its component changes. */
    private static ItemKey itemKey(ItemStack stack, DynamicOps<JsonElement> ops) {
        SortedMap<String, String> changes = componentChanges(stack, ops);
        return new ItemKey(itemIdOf(stack), SupplyChestRules.componentsFp(changes),
                SupplyChestRules.nonDefaultComponentIds(changes));
    }

    /**
     * One entry per slot, {@code null} for an empty slot. The component fingerprint is computed
     * only for stacks of {@code targetItemId}: stock, capacity and the withdraw plan compare it
     * only after the ids already match.
     */
    private static List<SlotView> slotViews(Inventory inventory, String targetItemId, DynamicOps<JsonElement> ops) {
        List<ItemStack> stacks = new ArrayList<>(inventory.size());
        for (int i = 0; i < inventory.size(); i++) {
            stacks.add(inventory.getStack(i));
        }
        return slotViews(stacks, targetItemId, ops);
    }

    private static List<SlotView> slotViews(List<ItemStack> stacks, String targetItemId, DynamicOps<JsonElement> ops) {
        List<SlotView> views = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) {
                views.add(null);
                continue;
            }
            String id = itemIdOf(stack);
            String fp = id.equals(targetItemId)
                    ? SupplyChestRules.componentsFp(componentChanges(stack, ops)) : null;
            views.add(new SlotView(id, fp, stack.getCount()));
        }
        return views;
    }

    /**
     * The stack's component changes as {@code id → JSON} ({@code null} value = a default removed).
     * Never uses {@code ComponentChanges.hashCode/toString}: its map is identity-hashed.
     */
    private static SortedMap<String, String> componentChanges(ItemStack stack, DynamicOps<JsonElement> ops) {
        SortedMap<String, String> changes = new TreeMap<>();
        for (Map.Entry<ComponentType<?>, Optional<?>> entry : stack.getComponentChanges().entrySet()) {
            ComponentType<?> type = entry.getKey();
            Identifier id = Registries.DATA_COMPONENT_TYPE.getId(type);
            String key = id == null ? UNREGISTERED_COMPONENT : id.toString();
            Optional<?> value = entry.getValue();
            changes.put(key, value.isPresent() ? encodeComponent(type, value.get(), ops) : null);
        }
        return changes;
    }

    @SuppressWarnings("unchecked")
    private static <T> String encodeComponent(ComponentType<T> type, Object value, DynamicOps<JsonElement> ops) {
        Codec<T> codec = type.getCodec();
        if (codec == null) {
            return NO_CODEC_MARKER;
        }
        try {
            return codec.encodeStart(ops, (T) value).result().map(JsonElement::toString).orElse(ENCODE_FAILED_MARKER);
        } catch (RuntimeException e) {
            return ENCODE_FAILED_MARKER;
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────────────

    private static void markDirty() {
        DebouncedWriter w = writer;
        if (w != null) {
            w.markDirty();
        }
    }

    /**
     * True, with a WARN, when the running server is called from any thread but its own. With no
     * server running it is false, so the caller reports NOT_RUNNING instead.
     */
    private static boolean offServerThread(String method) {
        MinecraftServer srv = server;
        if (srv == null || srv.isOnThread()) {
            return false;
        }
        LOGGER.warn("[supply] {} called off the server thread ({}); refused", method,
                Thread.currentThread().getName());
        return true;
    }

    private static String itemIdOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "-";
        }
        return Registries.ITEM.getId(stack.getItem()).toString();
    }

    private static String itemNameOf(String itemId) {
        Identifier id = itemId == null ? null : Identifier.tryParse(itemId);
        Optional<Item> item = id == null ? Optional.empty() : Registries.ITEM.getOptionalValue(id);
        return item.map(i -> i.getName().getString()).orElse(itemId == null ? "items" : itemId);
    }

    private static String botNameOf(UUID botUuid) {
        MinecraftServer srv = server;
        ServerPlayerEntity bot = srv == null || botUuid == null ? null : srv.getPlayerManager().getPlayer(botUuid);
        return bot == null ? "Your companion" : nameOf(bot);
    }

    private static String nameOf(ServerPlayerEntity player) {
        return player == null ? "-" : player.getName().getString();
    }

    private static String posText(BlockPos pos) {
        return pos == null ? "-" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
