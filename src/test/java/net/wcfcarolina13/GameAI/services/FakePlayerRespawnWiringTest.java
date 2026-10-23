package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FakePlayerRespawnWiringTest {
    private static final Path ROOT = Path.of("src/main/java/net/wcfcarolina13");

    @Test
    void onlyTheVanillaRespawnConstructorIsReplaced() throws Exception {
        String mixin = mixin();
        assertTrue(mixin.contains("@Mixin(PlayerManager.class)"));
        assertTrue(mixin.contains("respawnPlayer(Lnet/minecraft/server/network/ServerPlayerEntity;ZLnet/minecraft/entity/Entity$RemovalReason;)Lnet/minecraft/server/network/ServerPlayerEntity;"));
        assertTrue(mixin.contains("value = \"NEW\""));
        // Carpet @Redirects the same NEW, so Frens must wrap it rather than redirect it.
        assertTrue(mixin.contains("@WrapOperation("));
        assertFalse(mixin.contains("@Redirect"));
        assertTrue(mixin.contains("(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/world/ServerWorld;Lcom/mojang/authlib/GameProfile;Lnet/minecraft/network/packet/c2s/common/SyncedClientOptions;)Lnet/minecraft/server/network/ServerPlayerEntity;"));
        assertTrue(mixin.contains("require = 1"));
        assertTrue(mixin.contains("allow = 1"));
    }

    @Test
    void fakePlayersKeepTheirTypeAndRealPlayersKeepTheVanillaConstructor() throws Exception {
        String mixin = mixin();
        assertTrue(mixin.contains("oldPlayer instanceof createFakePlayer fake"));
        assertTrue(mixin.contains("return fake.createRespawnReplacement(server, world, profile, options);"));
        assertTrue(mixin.contains("@Local(argsOnly = true) ServerPlayerEntity oldPlayer"));
        assertTrue(mixin.contains("return original.call(server, world, profile, options);"));
        assertFalse(mixin.contains("new ServerPlayerEntity("));
        assertFalse(mixin.contains("BotRegistry"));
        assertFalse(mixin.contains("copyFrom("));
        assertFalse(mixin.contains("onPlayerConnect("));
        assertFalse(mixin.contains("networkHandler"));
        assertFalse(mixin.contains("getInventory("));
    }

    @Test
    void replacementConstructionPreservesShadowWithoutSpawnOrRestoreSideEffects() throws Exception {
        String source = Files.readString(ROOT.resolve("Entity/createFakePlayer.java"));
        int start = source.indexOf("public createFakePlayer createRespawnReplacement(");
        assertTrue(start >= 0);
        String factory = source.substring(start, source.indexOf("\n    }", start));
        assertTrue(factory.contains("return new createFakePlayer(server, world, profile, options, isAShadow);"));
        assertFalse(factory.contains("spawnFake("));
        assertFalse(factory.contains("BotPersistenceService"));
        assertFalse(factory.contains("setHealth("));
    }

    @Test
    void mixinIsRegisteredForTheServer() throws Exception {
        String config = Files.readString(Path.of("src/main/resources/frens.mixins.json"));
        int mixins = config.indexOf("\"mixins\"");
        int client = config.indexOf("\"client\"");
        int entry = config.indexOf("\"PlayerManagerRespawnMixin\"");
        assertTrue(entry > mixins && entry < client);
    }

    private static String mixin() throws Exception {
        Path path = ROOT.resolve("mixin/PlayerManagerRespawnMixin.java");
        return Files.exists(path) ? Files.readString(path) : "";
    }
}
