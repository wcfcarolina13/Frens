package net.shasankp000.network;

import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;

/** Lightweight fake client connection used for spawning offline-mode fake players during testing. */
public final class FakeClientConnection extends ClientConnection {
    public FakeClientConnection(NetworkSide side) {
        super(side);
    }
}
