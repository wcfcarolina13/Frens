package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.NavigationOfferPolicy.Result;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NavigationOfferPolicyTest {

    private static final UUID BOT = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID OTHER_BOT = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void liveOfferIsConsumedOnce() {
        NavigationOfferPolicy offers = new NavigationOfferPolicy();
        offers.offer(BOT, OWNER, 1_000L);
        assertEquals(Result.CONSUMED, offers.consume(BOT, OWNER, 2_000L));
    }

    @Test
    void replayAfterConsumeIsRefused() {
        NavigationOfferPolicy offers = new NavigationOfferPolicy();
        offers.offer(BOT, OWNER, 1_000L);
        offers.consume(BOT, OWNER, 2_000L);
        assertEquals(Result.NONE, offers.consume(BOT, OWNER, 3_000L));
    }

    @Test
    void noOfferMeansNone() {
        NavigationOfferPolicy offers = new NavigationOfferPolicy();
        assertEquals(Result.NONE, offers.consume(BOT, OWNER, 1_000L));
    }

    @Test
    void offerExpiresAtTtl() {
        NavigationOfferPolicy offers = new NavigationOfferPolicy();
        offers.offer(BOT, OWNER, 1_000L);
        assertEquals(Result.EXPIRED, offers.consume(BOT, OWNER, 1_000L + NavigationOfferPolicy.TTL_MS));
        assertEquals(Result.NONE, offers.consume(BOT, OWNER, 1_000L + NavigationOfferPolicy.TTL_MS));
    }

    @Test
    void offerIsLiveJustBeforeTtl() {
        NavigationOfferPolicy offers = new NavigationOfferPolicy();
        offers.offer(BOT, OWNER, 1_000L);
        assertEquals(Result.CONSUMED, offers.consume(BOT, OWNER, 1_000L + NavigationOfferPolicy.TTL_MS - 1));
    }

    @Test
    void wrongRecipientCannotConsumeAndLeavesTheOfferForTheRightOne() {
        NavigationOfferPolicy offers = new NavigationOfferPolicy();
        offers.offer(BOT, OWNER, 1_000L);
        assertEquals(Result.NONE, offers.consume(BOT, STRANGER, 2_000L));
        assertEquals(Result.CONSUMED, offers.consume(BOT, OWNER, 2_000L));
    }

    @Test
    void wrongBotCannotConsume() {
        NavigationOfferPolicy offers = new NavigationOfferPolicy();
        offers.offer(BOT, OWNER, 1_000L);
        assertEquals(Result.NONE, offers.consume(OTHER_BOT, OWNER, 2_000L));
    }

    @Test
    void reOfferRestartsTheTtl() {
        NavigationOfferPolicy offers = new NavigationOfferPolicy();
        offers.offer(BOT, OWNER, 1_000L);
        offers.offer(BOT, OWNER, 100_000L);
        assertEquals(Result.CONSUMED, offers.consume(BOT, OWNER, 1_000L + NavigationOfferPolicy.TTL_MS + 5_000L));
    }

    @Test
    void expiredOffersArePrunedOnTheNextOffer() {
        NavigationOfferPolicy offers = new NavigationOfferPolicy();
        offers.offer(BOT, OWNER, 1_000L);
        offers.offer(OTHER_BOT, OWNER, 1_000L + NavigationOfferPolicy.TTL_MS);
        assertEquals(1, offers.size());
    }

    @Test
    void nullKeysAreIgnored() {
        NavigationOfferPolicy offers = new NavigationOfferPolicy();
        offers.offer(null, OWNER, 1_000L);
        offers.offer(BOT, null, 1_000L);
        assertEquals(0, offers.size());
        assertEquals(Result.NONE, offers.consume(null, OWNER, 1_000L));
    }
}
