package net.wcfcarolina13.network;

import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.state.NetworkState;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;

/** Lightweight fake client connection used for spawning offline-mode fake players during testing. */
public final class FakeClientConnection extends ClientConnection {
    // Not the vanilla field: ClientConnection.tick() reads that one directly, and the bot's handler is
    // already ticked by the server, so it must stay null there.
    private volatile PacketListener listener;

    public FakeClientConnection(NetworkSide side) {
        super(side);
        setChannelForFake(new EmbeddedChannel());
    }

    @Override
    public void tryDisableAutoRead() {
    }

    @Override
    public void handleDisconnection() {
    }

    @Override
    public void setInitialPacketListener(PacketListener packetListener) {
    }

    /**
     * Skips vanilla's channel pipeline swap (the embedded channel has no codec handlers), but keeps the
     * listener so {@link #getPacketListener()} answers like a real connection. Mods that read the player
     * from the connection while packets are sent (Polymer, through packet-tweaker) threw a
     * NullPointerException on every bot spawn when this returned null.
     */
    @Override
    public <T extends PacketListener> void transitionInbound(NetworkState<T> state, T packetListener) {
        this.listener = packetListener;
    }

    @Override
    public PacketListener getPacketListener() {
        return this.listener;
    }

    private void setChannelForFake(Channel channel) {
        String[] candidates = new String[]{"channel", "field_11651", "k"};
        for (String name : candidates) {
            try {
                var field = ClientConnection.class.getDeclaredField(name);
                field.setAccessible(true);
                field.set(this, channel);
                field.setAccessible(false);
                return;
            } catch (ReflectiveOperationException ignored) {
            }
        }
    }
}
