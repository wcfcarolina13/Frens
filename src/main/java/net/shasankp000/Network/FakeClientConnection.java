package net.shasankp000.network;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.state.NetworkState;
import net.minecraft.text.Text;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;

/**
 * Minimal ClientConnection used for server-controlled fake players.
 * Mirrors the behaviour that Carpet injected for its fake-player support.
 */
public class FakeClientConnection extends ClientConnection {

    public FakeClientConnection(NetworkSide side) {
        super(side);
        Channel embedded = new EmbeddedChannel();
        setChannel("channel", embedded);
        setChannel("address", new InetSocketAddress("127.0.0.1", 0));
    }

    private void setChannel(String fieldName, Object value) {
        try {
            Field field = ClientConnection.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(this, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to initialize fake client connection", e);
        }
    }

    @Override
    public void tryDisableAutoRead() {
        // No-op: fake connections do not stream packets.
    }

    @Override
    public void handleDisconnection() {
        // No-op: fake connections skip Netty lifecycle handling.
    }

    @Override
    public void setInitialPacketListener(PacketListener packetListener) {
        // Intentionally left empty; server will manage the listener directly.
    }

    @Override
    public <T extends PacketListener> void transitionInbound(NetworkState<T> state, T packetListener) {
        // Intentionally left empty; fake clients do not transition states.
    }

    @Override
    public void disconnect(Text reason) {
        // Drop disconnections silently for fake players.
    }

    @Override
    public void disconnect(DisconnectionInfo disconnectionInfo) {
        // Drop disconnections silently for fake players.
    }
}
