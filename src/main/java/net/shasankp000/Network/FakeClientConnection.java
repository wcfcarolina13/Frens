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
 *
 * NOTE:
 * In production (obfuscated) environments we *cannot* rely on field names like "channel" or "address",
 * because those names only exist in the deobfuscated dev runtime.
 * Instead, we reflect by field *type* (Channel / InetSocketAddress), which is stable across mappings.
 */
public class FakeClientConnection extends ClientConnection {

    public FakeClientConnection(NetworkSide side) {
        super(side);

        // Create a dummy Netty channel and loopback address so that any base-class logic
        // that expects them to be non-null will be satisfied.
        Channel embedded = new EmbeddedChannel();
        setFieldOfType(Channel.class, embedded);
        setFieldOfType(InetSocketAddress.class, new InetSocketAddress("127.0.0.1", 0));
    }

    /**
     * Sets the first declared field on ClientConnection whose type is assignable
     * from the given value's class (e.g., Channel, InetSocketAddress).
     *
     * This works in both dev (mapped) and production (obfuscated) environments,
     * since it does not depend on the field *name*.
     */
    private void setFieldOfType(Class<?> targetType, Object value) {
        try {
            Field targetField = null;

            for (Field field : ClientConnection.class.getDeclaredFields()) {
                if (field.getType().isAssignableFrom(targetType)) {
                    targetField = field;
                    break;
                }
            }

            if (targetField == null) {
                throw new IllegalStateException(
                    "Unable to initialize fake client connection: no field of type " + targetType.getName() +
                    " found on " + ClientConnection.class.getName()
                );
            }

            targetField.setAccessible(true);
            targetField.set(this, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                "Unable to initialize fake client connection (failed to set field of type " +
                targetType.getName() + ")", e
            );
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