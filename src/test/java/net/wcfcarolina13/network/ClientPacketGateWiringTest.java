package net.wcfcarolina13.network;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Source-reading guards for the 2026-11-05 multiplayer fixes to the client packet receivers for
 * bases, quest topics and cookables. Style of {@code RespawnHandlingWiringTest}.
 */
class ClientPacketGateWiringTest {
    private static final Path BASES = Path.of("src/main/java/net/wcfcarolina13/network/BaseNetworkManager.java");
    private static final Path QUEST = Path.of("src/main/java/net/wcfcarolina13/network/CompanionQuestNetworkManager.java");
    private static final Path COOK = Path.of("src/main/java/net/wcfcarolina13/network/CookablesNetworkManager.java");
    private static final Path ZONES = Path.of("src/main/java/net/wcfcarolina13/network/ZoneNetworkManager.java");
    private static final Path LIST_PAYLOAD = Path.of("src/main/java/net/wcfcarolina13/network/BasesListPayload.java");

    // ── Finding 1: labels are capped, the list cannot overflow its codec ────

    @Test
    void everyStoringLabelPathCapsThroughThePolicy() throws IOException {
        String bases = flatten(BASES);
        String set = method(bases, "applyBaseSet");
        assertTrue(set.indexOf("label = BaseScreenLimitPolicy.capLabel(label);") < set.indexOf("labelInUse("));
        String rename = method(bases, "applyBaseRename");
        assertTrue(rename.indexOf("newLabel = BaseScreenLimitPolicy.capLabel(newLabel);") < rename.indexOf("labelInUse("));
        String village = receiver(bases, "BaseMapVillagePayload");
        assertTrue(village.indexOf("BaseScreenLimitPolicy.capLabel(") < village.indexOf("labelInUse("));
        assertTrue(village.indexOf("labelInUse(") < village.indexOf("MappedVillageService.save("));
    }

    @Test
    void zoneWandAndBasesShareOneLabelCap() throws IOException {
        String zones = flatten(ZONES);
        assertTrue(receiver(zones, "ZoneConfirmPayload").contains("name = BaseScreenLimitPolicy.capLabel(name);"));
        assertFalse(zones.contains("name.substring(0, 32)"));
    }

    @Test
    void basesListIsTrimmedToTheCodecLimitInsteadOfThrowing() throws IOException {
        String bases = flatten(BASES);
        String send = method(bases, "sendBasesList(ServerPlayerEntity player, String botAliasContext)");
        assertFalse(send.contains("GSON.toJson(out)"));
        assertTrue(send.contains("new BasesListPayload(encodeBasesList(out))"));
        String encode = block(bases, "static String encodeBasesList(List<BaseDto> out)");
        assertTrue(encode.contains("BaseScreenLimitPolicy.entriesThatFit(lengths, BaseScreenLimitPolicy.MAX_LIST_JSON_CHARS)"));
        assertTrue(encode.contains("for (int i = 0; i < keep; i++)"));
        assertTrue(flatten(LIST_PAYLOAD).contains("new StringCodec(BaseScreenLimitPolicy.MAX_LIST_JSON_CHARS)"));
    }

    // ── Finding 2: the list resolves the bot through the access gate ────────

    @Test
    void basesListResolvesTheSelectedBotForTheRequestingPlayer() throws IOException {
        String bases = flatten(BASES);
        String send = method(bases, "sendBasesList(ServerPlayerEntity player, String botAliasContext)");
        assertTrue(send.contains("resolveControlledBot(player.getCommandSource().getServer(), botAliasContext, player, \"bases_list\")"));
        assertFalse(bases.contains(", null, null)"), "no ungated resolveControlledBot call remains");
        // Home mark and lodestone destinations come only from the gated bot.
        assertTrue(send.indexOf("ServerPlayerEntity selectedBot = resolveControlledBot(") < send.indexOf("getPreferredHomeBaseLabel(selectedBot)"));
        assertTrue(block(send, "if (selectedBot != null) { for (").contains("findLodestoneCompasses(selectedBot)"));
    }

    @Test
    void resolveControlledBotStillGatesWhenAPlayerIsGiven() throws IOException {
        String resolve = block(flatten(BASES), "private static ServerPlayerEntity resolveControlledBot(");
        assertTrue(resolve.contains("if (!(bot instanceof createFakePlayer))"));
        assertTrue(resolve.contains("if (!BotEventHandler.isRegisteredBot(bot))"));
        assertTrue(resolve.contains("requestingPlayer != null && !BotAccessGate.permits(requestingPlayer, bot, action)"));
    }

    // ── Findings 3 and 4: save-base gate, shared write throttle ─────────────

    @Test
    void saveBasePacketIsThrottledThenGatedBeforeTheApplyPath() throws IOException {
        String set = receiver(flatten(BASES), "BaseSetPayload");
        int throttle = set.indexOf("if (player.isRemoved() || !acquireWriteSlot(player)) return;");
        int gate = set.indexOf("if (!BaseAccessPolicy.canMapVillage(ownsAnyBot(player), Frens.isOperator(player), BotAccessGate.isHost(player)))");
        int apply = set.indexOf("applyBaseSet(player.getCommandSource(), label);");
        assertTrue(throttle >= 0 && throttle < gate && gate < apply);
        String denied = block(set, "if (!BaseAccessPolicy.canMapVillage(");
        assertTrue(denied.contains("warnDenied(player, \"base_set\", label, \"not_bot_owner\")"));
        assertTrue(denied.contains("Only a bot owner, an operator or the host can save a base."));
        assertTrue(denied.contains("return;"));
    }

    @Test
    void mapVillagePacketIsThrottledAndKeepsItsGate() throws IOException {
        String village = receiver(flatten(BASES), "BaseMapVillagePayload");
        int throttle = village.indexOf("if (player.isRemoved() || !acquireWriteSlot(player)) return;");
        int gate = village.indexOf("if (!BaseAccessPolicy.canMapVillage(ownsAnyBot(player), Frens.isOperator(player), BotAccessGate.isHost(player)))");
        int scan = village.indexOf("VillageFortificationLayoutService.generateLayout(");
        assertTrue(throttle >= 0 && throttle < gate && gate < scan);
        assertTrue(village.contains("Only a bot owner, an operator or the host can map a village."));
    }

    @Test
    void writeThrottleUsesThePolicyBucketAndIsClearedOnDisconnect() throws IOException {
        String bases = flatten(BASES);
        String acquire = block(bases, "private static boolean acquireWriteSlot(ServerPlayerEntity player)");
        assertTrue(acquire.contains("WRITE_THROTTLES.computeIfAbsent(player.getUuid(), id -> BaseScreenLimitPolicy.newWriteThrottle())"));
        assertTrue(acquire.contains("if (throttle.tryAcquire(now)) return true;"));
        assertTrue(block(acquire, "if (throttle.shouldNotify(now))").contains("Slow down, that click was ignored."));
        assertTrue(bases.contains("ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> { UUID id = handler.player.getUuid(); server.execute(() -> WRITE_THROTTLES.remove(id)); });"));
        assertTrue(bases.contains("ServerLifecycleEvents.SERVER_STOPPED.register(server -> WRITE_THROTTLES.clear());"));
        // The command path keeps its own permission: no throttle or owner gate inside applyBaseSet.
        String apply = method(bases, "applyBaseSet");
        assertFalse(apply.contains("acquireWriteSlot("));
        assertFalse(apply.contains("canMapVillage("));
    }

    @Test
    void zoneRenameIsCappedAndBaseWritesSkipRemovedPlayers() throws IOException {
        String zone = flatten(Path.of("src/main/java/net/wcfcarolina13/network/ZoneNetworkManager.java"));
        assertTrue(zone.contains("String newName = BaseScreenLimitPolicy.capLabel(payload.newName());"));
        String bases = flatten(BASES);
        assertTrue(bases.contains("if (player.isRemoved() || !acquireWriteSlot(player)) return;"));
    }

    // ── Finding 5: quest topic needs a bot the sender may act on ────────────

    @Test
    void questTopicChecksStringsThenBotThenGateBeforeHandleTopic() throws IOException {
        String quest = flatten(QUEST);
        String topic = receiver(quest, "CompanionQuestTopicPayload");
        int strings = topic.indexOf("CompanionQuestTopicPolicy.refusal(alias, payload.topicKey())");
        int resolve = topic.indexOf("ServerPlayerEntity bot = resolveBotByAlias(context.server(), alias);");
        int gate = topic.indexOf("if (bot == null || !BotAccessGate.permits(player, bot, \"companion_quest_topic\"))");
        int handle = topic.indexOf("SurvivalCompanionQuestService.handleTopic(player, alias, payload.topicKey())");
        int sound = topic.indexOf("BotDialoguePlayer.playSoundForBotDetailed(bot, sound, VoiceLineCategory.TOPICS_QUESTS)");
        int reply = topic.indexOf("new CompanionQuestResponsePayload( alias,");
        assertTrue(strings >= 0 && strings < resolve && resolve < gate && gate < handle && handle < sound && sound < reply);
        assertTrue(block(topic, "if (refusal.isPresent())").contains("return;"));
        assertTrue(block(topic, "if (bot == null || !BotAccessGate.permits(").contains("return;"));
        assertFalse(topic.contains("payload.botAlias(), resp.linesJoined()"), "the raw client alias is not echoed");
    }

    @Test
    void questTopicAliasResolvesOnlyRegisteredBots() throws IOException {
        String resolve = block(flatten(QUEST), "private static ServerPlayerEntity resolveBotByAlias(MinecraftServer server, String alias)");
        assertTrue(resolve.contains("if (!(found instanceof createFakePlayer) || !BotEventHandler.isRegisteredBot(found))"));
        assertFalse(resolve.contains("return exact;"));
    }

    // ── Finding 6: cookables never fall back to the global bot ──────────────

    @Test
    void cookablesUseOnlyTheOpenScreensBotAndGateIt() throws IOException {
        String cook = flatten(COOK);
        assertFalse(cook.contains("BotEventHandler"));
        String send = method(cook, "sendCookablesList");
        assertTrue(send.contains("if (player.currentScreenHandler instanceof BotPlayerInventoryScreenHandler handler) { bot = handler.getBotRef(); }"));
        String denied = block(send, "if (bot == null || bot.isRemoved() || !BotAccessGate.permits(player, bot, \"cookables_list\"))");
        assertTrue(denied.contains("new CookablesPayload(\"[]\")"));
        assertTrue(denied.contains("return;"));
        assertTrue(send.indexOf("\"cookables_list\"") < send.indexOf("SmeltingService.listCookableIds("));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static String flatten(Path path) throws IOException {
        return Files.readString(path)
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "")
                .replaceAll("\\s+", " ");
    }

    private static String receiver(String source, String payload) {
        return block(source, "registerGlobalReceiver(" + payload + ".ID, (payload, context) ->");
    }

    private static String method(String source, String name) {
        String signature = name.contains("(") ? Pattern.quote(name) : name + "\\([^)]*\\)";
        var declaration = Pattern.compile("(?:public |private )?static [\\w.<>]+ " + signature + "\\s*\\{")
                .matcher(source);
        assertTrue(declaration.find(), "Missing method: " + name);
        return block(source, declaration.group());
    }

    private static String block(String source, String marker) {
        int at = source.indexOf(marker);
        assertTrue(at >= 0, "Missing scope: " + marker);
        int start = source.indexOf('{', at);
        int depth = 1;
        for (int i = start + 1; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            if (c == '}' && --depth == 0) return source.substring(start + 1, i);
        }
        throw new AssertionError("Unclosed scope: " + marker);
    }
}
