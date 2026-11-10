package net.wcfcarolina13.network;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Remote Guidance used to take both pearls and then try the trip, ignoring a refusal (fight, rest,
 * mount), so a refused cast still cost the pearls. The pearls are now paid from inside the travel
 * service, after every refusal and before the bot's inventory is saved for the trip.
 */
class RemoteGuidancePaymentWiringTest {

    private static final Path SPELLS = Path.of("src/main/java/net/wcfcarolina13/network/SpellNavigationNetworkManager.java");
    private static final Path TRAVEL = Path.of("src/main/java/net/wcfcarolina13/GameAI/services/NavigationArtifactService.java");

    @Test
    void guidancePaysOnlyInsideTheDepartureCallback() throws IOException {
        String src = Files.readString(SPELLS);
        int start = src.indexOf("private static void handleRemoteGuidance(");
        int end = src.indexOf("private static void handleChorusRecall(");
        String body = src.substring(start, end);
        int travel = body.indexOf("NavigationArtifactService.beginMagicTravel(");
        int firstConsume = body.indexOf("consumeItem(");
        assertTrue(travel >= 0 && firstConsume > travel, "pearls must be consumed inside the beginMagicTravel callback");
        assertTrue(body.contains("if (!started)"), "a refused trip must be reported, not announced as cast");
    }

    @Test
    void paymentRunsAfterEveryRefusalAndBeforeTheInventoryIsSaved() throws IOException {
        String src = Files.readString(TRAVEL);
        int pay = src.indexOf("payOnDeparture.getAsBoolean()");
        assertTrue(pay > 0, "the travel service must call the payment");
        int lastRefusal = Math.max(src.lastIndexOf("REFUSE_CROSS_DIM_ANIMAL -> {", pay),
                src.lastIndexOf("if (!tier.allowed())", pay));
        assertTrue(lastRefusal > 0 && lastRefusal < pay, "payment must come after the refusals");
        int save = src.indexOf("BotPersistenceService.onBotDisconnect(bot);", pay);
        int mountDiscard = src.indexOf("mount.discard();", pay);
        assertTrue(save > pay, "payment must come before the inventory is saved for the trip");
        assertTrue(mountDiscard > pay, "payment must come before the mount is removed");
        assertFalse(src.substring(pay, save).contains("return false;\n            }\n\n            if (tier"),
                "no refusal may follow the payment");
    }
}
