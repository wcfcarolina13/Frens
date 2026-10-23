package net.wcfcarolina13.network;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PlayerPreferenceReceiverWiringTest {
    private static final String PRESERVE = "UpdatePlayerPreservePayload";
    private static final String PRECIOUS = "UpdatePlayerAutoAcceptPreciousPayload";

    @Test
    void serverThreadUpdateCallbacksDoNotDispatchAnExtraTask() throws IOException {
        assertFalse(receiver(PRESERVE).contains(".execute("));
        assertFalse(receiver(PRECIOUS).contains(".execute("));
    }

    @Test
    void updatesRemainBoundToTheSendingPlayersIdentity() throws IOException {
        for (String payload : new String[]{PRESERVE, PRECIOUS}) {
            String body = receiver(payload);
            int sender = body.indexOf("ServerPlayerEntitysender=context.player();");
            int identity = body.indexOf("java.util.UUIDsenderUuid=sender.getUuid();");
            int config = body.indexOf("if(Frens.CONFIG==null)");
            int admission = body.indexOf("PREFERENCE_GATE.admit(");
            assertTrue(sender >= 0 && sender < identity && identity < config && config < admission);
            assertTrue(body.substring(sender, identity).contains("if(sender==null)"));
            assertTrue(body.substring(identity, config).contains("if(senderUuid==null)"));
        }
    }

    @Test
    void preserveAdmissionStillGatesTheFullStateUpdateAndRisingEdgeCleanup() throws IOException {
        String body = receiver(PRESERVE);
        assertTrue(body.contains("PREFERENCE_GATE.admit(senderUuid,\"preserveExpensiveGear\",current,payload.enabled(),System.currentTimeMillis())"));
        assertTrue(body.contains("booleancurrent=Frens.CONFIG.getPreserveExpensiveGear(senderUuid);"));
        int accepted = body.indexOf("if(!admission.accepted()){return;}");
        int setter = body.indexOf("Frens.CONFIG.setPreserveExpensiveGear(senderUuid,payload.enabled());");
        int cleanup = body.indexOf("if(admission.risingEdge())");
        assertTrue(accepted >= 0 && accepted < setter && setter < cleanup);
        assertTrue(block(body, "if(admission.risingEdge())").contains(
                "DurabilityFallbackService.clearCooldownsForOwner(senderUuid);"));
    }

    @Test
    void preciousFoodAdmissionStillGatesTheFullStateUpdate() throws IOException {
        String body = receiver(PRECIOUS);
        assertTrue(body.contains("PREFERENCE_GATE.admit(senderUuid,\"autoAcceptPreciousFoods\",current,payload.enabled(),System.currentTimeMillis())"));
        assertTrue(body.contains("booleancurrent=Frens.CONFIG.getAutoAcceptPreciousFoods(senderUuid);"));
        assertTrue(block(body, "if(admission.accepted())").contains(
                "Frens.CONFIG.setAutoAcceptPreciousFoods(senderUuid,payload.enabled());"));
    }

    @Test
    void rateLimitedUpdatesStillResyncCurrentStateThenReturnBeforeMutation() throws IOException {
        for (String payload : new String[]{PRESERVE, PRECIOUS}) {
            String body = receiver(payload);
            String limited = block(body, "if(admission.decision()==PlayerPreferenceGate.Decision.RATE_LIMITED)");
            String preference = payload.equals(PRESERVE) ? "preserveExpensiveGear" : "autoAcceptPreciousFoods";
            String state = payload.equals(PRESERVE) ? "PlayerPreserveStatePayload" : "PlayerAutoAcceptPreciousStatePayload";
            assertTrue(limited.contains("PREFERENCE_GATE.resyncDue(senderUuid,\"" + preference + "\",System.currentTimeMillis())"));
            assertTrue(limited.contains("ServerPlayNetworking.send(sender,new" + state + "(current))"));
            assertTrue(limited.endsWith("return;"));
            assertFalse(limited.contains("Frens.CONFIG.set"));
            assertTrue(body.indexOf("PlayerPreferenceGate.Decision.RATE_LIMITED") < body.indexOf("Frens.CONFIG.set"));
        }
    }

    private static String receiver(String payload) throws IOException {
        String source = Files.readString(Path.of("src/main/java/net/wcfcarolina13/Frens.java"));
        StringBuilder compact = new StringBuilder();
        for (char c : source.toCharArray()) {
            if (!Character.isWhitespace(c)) compact.append(c);
        }
        return block(compact.toString(), "registerGlobalReceiver(" + payload + ".ID,(payload,context)->");
    }

    private static String block(String source, String marker) {
        int at = source.indexOf(marker);
        assertTrue(at >= 0, "Missing receiver scope: " + marker);
        int start = source.indexOf('{', at);
        int depth = 1;
        for (int i = start + 1; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            if (c == '}' && --depth == 0) return source.substring(start + 1, i);
        }
        throw new AssertionError("Unclosed receiver scope: " + marker);
    }
}
