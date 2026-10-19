package net.wcfcarolina13.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import net.wcfcarolina13.FilingSystem.ManualConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding 15: a settings save past 32,767 chars disconnected the player. The save and open
 * config codecs now carry up to {@link ConfigPayloadSizePolicy#MAX_CONFIG_JSON_CHARS} chars.
 * The {@code registerLarge} splitter is not live under JUnit, so the field check covers that part.
 */
class SaveConfigPayloadSizeTest {

    private static final String[] WORLDS = {"minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"};
    private static final int ALIASES = 46;

    private static ManualConfig newRealConfig() throws Exception {
        Constructor<ManualConfig> constructor = ManualConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    /** 46 aliases x 3 worlds, mixed non-default values, like the bloated sandbox config. */
    private static ManualConfig bloated() throws Exception {
        ManualConfig cfg = newRealConfig();
        for (int a = 0; a < ALIASES; a++) {
            for (int w = 0; w < WORLDS.length; w++) {
                ManualConfig.BotControlSettings s = cfg.getOrCreateBotControl("Bot" + a, WORLDS[w]);
                s.setFollowTeleport((a + w) % 2 == 0);
                s.setVoicedDialogue((a + w) % 3 == 0);
                s.setTeleportDuringSkills(a % 2 == 1);
                s.setAutoRespawnOnDeath(w == 1);
            }
        }
        return cfg;
    }

    @Test
    void bloatedConfigIsOverTheOldLimit() throws Exception {
        String json = ConfigJsonUtil.configToJson(bloated());
        assertTrue(json.length() > 32767, "expected > 32767 chars, got " + json.length());
        assertTrue(ConfigPayloadSizePolicy.fitsSavePayload(json));
    }

    @Test
    void saveCodecRoundTripsPast32k() throws Exception {
        String json = ConfigJsonUtil.configToJson(bloated());
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        SaveConfigPayload.CODEC.encode(buf, new SaveConfigPayload(json));
        SaveConfigPayload decoded = SaveConfigPayload.CODEC.decode(buf);
        assertEquals(json, decoded.configJson());
        assertTrue(buf.readableBytes() == 0);
    }

    @Test
    void openCodecRoundTripsPast32k() throws Exception {
        String json = ConfigJsonUtil.configToJson(bloated());
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        OpenConfigPayload.CODEC.encode(buf, new OpenConfigPayload(json));
        OpenConfigPayload decoded = OpenConfigPayload.CODEC.decode(buf);
        assertEquals(json, decoded.configData());
    }

    @Test
    void saveCodecCarriesTheFullLimit() {
        String json = "x".repeat(ConfigPayloadSizePolicy.MAX_CONFIG_JSON_CHARS);
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        SaveConfigPayload.CODEC.encode(buf, new SaveConfigPayload(json));
        assertTrue(buf.readableBytes() <= ConfigPayloadSizePolicy.MAX_SAVE_PAYLOAD_BYTES);
        assertEquals(json, SaveConfigPayload.CODEC.decode(buf).configJson());
    }

    @Test
    void appliedJsonRestoresEveryBotControlEntry() throws Exception {
        ManualConfig source = bloated();
        String json = ConfigJsonUtil.configToJson(source);
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        SaveConfigPayload.CODEC.encode(buf, new SaveConfigPayload(json));
        String decoded = SaveConfigPayload.CODEC.decode(buf).configJson();

        ManualConfig target = newRealConfig();
        assertTrue(ConfigJsonUtil.applyConfigJson(decoded, target));

        Map<String, Map<String, ManualConfig.BotControlSettings>> byWorld = target.getBotControlsByWorld();
        assertEquals(source.getBotControlsByWorld().keySet(), byWorld.keySet());
        for (int a = 0; a < ALIASES; a++) {
            Map<String, ManualConfig.BotControlSettings> worlds = byWorld.get("Bot" + a);
            assertNotNull(worlds, "missing alias Bot" + a);
            assertEquals(WORLDS.length, worlds.size());
            for (int w = 0; w < WORLDS.length; w++) {
                ManualConfig.BotControlSettings s = worlds.get(WORLDS[w]);
                assertNotNull(s, "missing Bot" + a + " @ " + WORLDS[w]);
                assertEquals((a + w) % 2 == 0, s.isFollowTeleport());
                assertEquals((a + w) % 3 == 0, s.isVoicedDialogue());
                assertEquals(a % 2 == 1, s.isTeleportDuringSkills());
                assertEquals(w == 1, s.isAutoRespawnOnDeath());
            }
        }
    }

    @Test
    void guardBoundaryIsTheCodecLimit() {
        int max = ConfigPayloadSizePolicy.MAX_CONFIG_JSON_CHARS;
        assertEquals(262_144, max);
        assertTrue(ConfigPayloadSizePolicy.fitsSavePayload("x".repeat(max)));
        assertFalse(ConfigPayloadSizePolicy.fitsSavePayload("x".repeat(max + 1)));
        assertTrue(ConfigPayloadSizePolicy.fitsOpenPayload("x".repeat(max)));
        assertFalse(ConfigPayloadSizePolicy.fitsOpenPayload("x".repeat(max + 1)));
        assertFalse(ConfigPayloadSizePolicy.fitsSavePayload(null));
        assertTrue(ConfigPayloadSizePolicy.fitsSavePayload(""));
    }

    @Test
    void largeCeilingCoversWorstCaseUtf8() {
        // 3 bytes per char at most, plus a 3-byte varint length prefix.
        long worst = 3L * ConfigPayloadSizePolicy.MAX_CONFIG_JSON_CHARS + 3;
        assertTrue(worst <= ConfigPayloadSizePolicy.MAX_SAVE_PAYLOAD_BYTES);
    }
}
