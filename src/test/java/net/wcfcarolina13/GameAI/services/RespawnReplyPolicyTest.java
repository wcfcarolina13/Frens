package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RespawnReplyPolicyTest {
    @Test
    void currentOwnerCanAnswerButAnOwnershipTransferRevokesTheOldAnswer() {
        UUID oldOwner = UUID.randomUUID();
        UUID newOwner = UUID.randomUUID();
        assertTrue(RespawnReplyPolicy.mayAnswer(oldOwner, oldOwner, oldOwner, false));
        assertFalse(RespawnReplyPolicy.mayAnswer(oldOwner, oldOwner, newOwner, false));
        assertFalse(RespawnReplyPolicy.mayAnswer(oldOwner, oldOwner, null, false));
    }

    @Test
    void administratorRetainsAuthorityForTheirOwnPendingRoute() {
        UUID admin = UUID.randomUUID();
        assertTrue(RespawnReplyPolicy.mayAnswer(admin, admin, UUID.randomUUID(), true));
        assertTrue(RespawnReplyPolicy.mayAnswer(admin, admin, null, true));
    }

    @Test
    void staleReverseMappingCannotAnswerSomeoneElsesPromptEvenAsAdmin() {
        UUID sender = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        assertFalse(RespawnReplyPolicy.mayAnswer(sender, other, sender, false));
        assertFalse(RespawnReplyPolicy.mayAnswer(sender, other, sender, true));
    }

    @Test
    void missingResponderOrPromptOwnerNeverAuthorizesAnAnswer() {
        UUID owner = UUID.randomUUID();
        assertFalse(RespawnReplyPolicy.mayAnswer(null, owner, owner, true));
        assertFalse(RespawnReplyPolicy.mayAnswer(owner, null, owner, true));
        assertFalse(RespawnReplyPolicy.mayAnswer(null, null, null, true));
    }

    @Test
    void ownerlessRecruiterIsAcceptedOnlyWhileStillTheController() {
        UUID recruiter = UUID.randomUUID();
        assertTrue(RespawnReplyPolicy.mayAnswer(recruiter, recruiter, recruiter, false));
        assertFalse(RespawnReplyPolicy.mayAnswer(recruiter, recruiter, UUID.randomUUID(), false));
    }

    @Test
    void chatChecksCurrentAuthorityBeforeActingOnEitherAnswer() throws Exception {
        String chat = method("public static boolean handleChat(");
        int lookup = chat.indexOf("Pending pending = BY_ALIAS.get(aliasKey)");
        int auth = chat.indexOf("if (!isReplyAuthorized(server, sender, pending))");
        int answer = chat.indexOf("if (normalized.equals(\"yes\"))");
        assertTrue(lookup >= 0 && lookup < auth && auth < answer);
        assertTrue(chat.substring(auth, answer).contains("refuseReply(sender, pending, aliasKey)"));
    }

    @Test
    void queuedSpawnRechecksIdentityAndAuthorityBeforeConsoleExecution() throws Exception {
        String queued = method("private static void runReplySpawn(");
        int hop = queued.indexOf("server.execute(() ->");
        int identity = queued.indexOf("BY_ALIAS.get(aliasKey) != pending");
        int auth = queued.indexOf("if (!isReplyAuthorized(server, sender, pending))");
        int spawn = queued.indexOf("executeSpawn(server, pending.alias(), pending.spawnMode())");
        assertTrue(hop >= 0 && hop < identity && identity < auth && auth < spawn);
        String authorize = method("private static boolean isReplyAuthorized(");
        assertTrue(authorize.contains("!sender.isRemoved()"));
        assertTrue(authorize.contains("resolveControllerUuidForAlias(server, pending.alias())"));
        assertTrue(authorize.contains("RespawnReplyPolicy.mayAnswer("));
        assertTrue(authorize.contains("BotAccessGate.isServerAdmin(sender)"));
    }

    @Test
    void rejectedAnswerKeepsTheRequestAndRemovesOnlyItsOwnReplyRoute() throws Exception {
        String refusal = method("private static void refuseReply(");
        assertTrue(refusal.contains("BY_OWNER.remove(sender.getUuid(), aliasKey)"));
        assertFalse(refusal.contains("BY_ALIAS.remove("));
        assertTrue(refusal.contains("ChatUtils.sendSystemMessage(sender.getCommandSource()"));
        assertTrue(refusal.contains("respawn request was kept"));
        assertTrue(method("public static void clear(").contains("BY_OWNER.remove(p.ownerUuid(), aliasKey)"));
        assertTrue(method("public static void promptForRespawn(").contains("BY_OWNER.remove(existing.ownerUuid(), aliasKey)"));
    }

    @Test
    void yesRetainsTheRequestUntilTheQueuedAttemptAndNoUsesConditionalRemoval() throws Exception {
        String chat = method("public static boolean handleChat(");
        String yes = block(chat, "if (normalized.equals(\"yes\"))");
        assertTrue(yes.contains("runReplySpawn(server, sender, pending, aliasKey)"));
        assertFalse(yes.contains("BY_ALIAS.remove("));
        assertTrue(chat.contains("BY_ALIAS.remove(aliasKey, pending)"));
    }

    @Test
    void refusedSpawnRestoresOnlyAnUnreplacedRequestAndTellsTheResponder() throws Exception {
        String queued = method("private static void runReplySpawn(");
        assertTrue(queued.contains("BY_ALIAS.putIfAbsent(aliasKey, pending)"));
        assertTrue(queued.contains("BY_OWNER.putIfAbsent(sender.getUuid(), aliasKey)"));
        assertTrue(queued.contains("Couldn't respawn "));
        assertTrue(queued.contains("ChatUtils.sendSystemMessage(sender.getCommandSource()"));
    }

    private static String method(String signature) throws Exception {
        return block(Files.readString(Path.of("src/main/java/net/wcfcarolina13/GameAI/services/BotRespawnPromptService.java")), signature);
    }

    private static String block(String source, String marker) {
        int at = source.indexOf(marker);
        assertTrue(at >= 0, "Missing scope: " + marker);
        int start = source.indexOf('{', at);
        int depth = 1;
        for (int i = start + 1; i < source.length(); i++) {
            if (source.charAt(i) == '{') depth++;
            if (source.charAt(i) == '}' && --depth == 0) return source.substring(start + 1, i);
        }
        throw new AssertionError("Unclosed scope: " + marker);
    }
}
