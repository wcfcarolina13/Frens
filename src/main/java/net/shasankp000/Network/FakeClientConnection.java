package net.shasankp000.network;

import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.state.NetworkState;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;

/** Lightweight fake client connection used for spawning offline-mode fake players during testing. */
public final class FakeClientConnection extends ClientConnection {
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

    @Override
    public <T extends PacketListener> void transitionInbound(NetworkState<T> state, T packetListener) {
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
