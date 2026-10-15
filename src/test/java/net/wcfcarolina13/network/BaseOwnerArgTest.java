package net.wcfcarolina13.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BaseOwnerArgTest {

    private static final String UUID_STR = "0f8fad5b-d9cb-469f-a165-70867728950e";

    @Test
    void serverResolvesToTheServerSentinel() {
        BaseOwnerArg.Owner owner = BaseOwnerArg.resolve(BaseOwnerArg.Kind.SERVER, UUID_STR, "Steve");
        assertEquals("SERVER", owner.uuid());
        assertEquals("Server", owner.name());
        assertEquals("Server", BaseOwnerArg.display(owner));
    }

    @Test
    void noneClearsTheOwner() {
        BaseOwnerArg.Owner owner = BaseOwnerArg.resolve(BaseOwnerArg.Kind.NONE, UUID_STR, "Steve");
        assertNull(owner.uuid());
        assertNull(owner.name());
        assertEquals("(unowned)", BaseOwnerArg.display(owner));
    }

    @Test
    void playerKeepsUuidAndName() {
        BaseOwnerArg.Owner owner = BaseOwnerArg.resolve(BaseOwnerArg.Kind.PLAYER, UUID_STR, "Steve");
        assertEquals(UUID_STR, owner.uuid());
        assertEquals("Steve", owner.name());
        assertEquals("Steve", BaseOwnerArg.display(owner));
    }

    @Test
    void blankPayloadFieldsBecomeUnowned() {
        BaseOwnerArg.Owner owner = BaseOwnerArg.normalize("  ", "");
        assertNull(owner.uuid());
        assertNull(owner.name());
        assertEquals("(unowned)", BaseOwnerArg.display(owner));
    }

    @Test
    void serverSentinelWithoutNameStillDisplaysServer() {
        assertEquals("Server", BaseOwnerArg.display(BaseOwnerArg.normalize("SERVER", null)));
    }

    @Test
    void nullKindIsUnowned() {
        BaseOwnerArg.Owner owner = BaseOwnerArg.resolve(null, UUID_STR, "Steve");
        assertNull(owner.uuid());
        assertNull(owner.name());
    }
}
