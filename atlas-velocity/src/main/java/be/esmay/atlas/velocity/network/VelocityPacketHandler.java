package be.esmay.atlas.velocity.network;

import be.esmay.atlas.common.network.packet.Packet;
import be.esmay.atlas.common.network.packet.PacketHandler;
import be.esmay.atlas.common.network.packet.packets.AtlasServerUpdatePacket;
import be.esmay.atlas.common.network.packet.packets.AuthenticationPacket;
import be.esmay.atlas.common.network.packet.packets.HandshakePacket;
import be.esmay.atlas.common.network.packet.packets.HeartbeatPacket;
import be.esmay.atlas.common.network.packet.packets.ServerAddPacket;
import be.esmay.atlas.common.network.packet.packets.ServerInfoUpdatePacket;
import be.esmay.atlas.common.network.packet.packets.ServerListPacket;
import be.esmay.atlas.common.network.packet.packets.ServerListRequestPacket;
import be.esmay.atlas.common.network.packet.packets.ServerRemovePacket;
import be.esmay.atlas.common.network.packet.packets.ServerUpdatePacket;
import be.esmay.atlas.velocity.cache.NetworkServerCacheManager;
import be.esmay.atlas.velocity.registry.VelocityServerRegistryManager;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.slf4j.Logger;

@RequiredArgsConstructor
public final class VelocityPacketHandler extends SimpleChannelInboundHandler<Packet> implements PacketHandler {

    private final NetworkServerCacheManager cacheManager;
    private final VelocityServerRegistryManager registryManager;
    private final Logger logger;

    @Setter
    private AtlasNetworkClient networkClient;

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        this.logger.debug("Channel active: {}", ctx.channel().remoteAddress());
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        this.logger.debug("Channel inactive: {}", ctx.channel().remoteAddress());
        if (this.networkClient != null) {
            this.networkClient.onDisconnected();
        }

        super.channelInactive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Packet packet) throws Exception {
        packet.handle(this);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        this.logger.error("Exception in packet handler", cause);
        ctx.close();
    }

    @Override
    public void handleHandshake(HandshakePacket packet) {
        this.logger.debug("Handshake response received: accepted={}, reason={}", packet.isAccepted(), packet.getReason());

        if (!packet.isAccepted()) {
            this.logger.error("Handshake failed: {}", packet.getReason());

            return;
        }

        if (this.networkClient == null) return;
        this.networkClient.onAuthenticated();
    }

    @Override
    public void handleAuthentication(AuthenticationPacket packet) {
        this.logger.debug("Authentication packet received: authenticated={}", packet.isAuthenticated());
    }

    @Override
    public void handleHeartbeat(HeartbeatPacket packet) {
        this.logger.debug("Heartbeat received from server: {}", packet.getServerId());
    }

    @Override
    public void handleServerUpdate(ServerUpdatePacket packet) {
        this.logger.debug("Server update received: {}", packet.getAtlasServer().getName());

        this.cacheManager.updateAtlasServer(packet.getAtlasServer());
    }

    @Override
    public void handleServerList(ServerListPacket packet) {
        this.logger.debug("Server list received with {} servers", packet.getAtlasServers().size());

        packet.getAtlasServers().forEach(server -> {
            this.cacheManager.updateAtlasServer(server);
            this.registryManager.handleAtlasServerAdd(server);
        });

        this.logger.info("Registered {} servers on startup", packet.getAtlasServers().size());
    }

    @Override
    public void handleServerAdd(ServerAddPacket packet) {
        this.logger.debug("Server add received: {}", packet.getAtlasServer().getName());

        this.cacheManager.updateAtlasServer(packet.getAtlasServer());
        this.registryManager.handleAtlasServerAdd(packet.getAtlasServer());
    }

    @Override
    public void handleServerRemove(ServerRemovePacket packet) {
        this.logger.debug("Server remove received: {} (reason: {})", packet.getServerId(), packet.getReason());

        this.cacheManager.removeServer(packet.getServerId());
        this.registryManager.handleServerRemove(packet.getServerId());
    }

    @Override
    public void handleServerInfoUpdate(ServerInfoUpdatePacket packet) {
        this.logger.warn("Backend server received ServerInfoUpdatePacket, this should not happen");
    }

    @Override
    public void handleAtlasServerUpdate(AtlasServerUpdatePacket packet) {
        this.logger.debug("Atlas server update received: {}", packet.getAtlasServer().getName());

        this.cacheManager.updateAtlasServer(packet.getAtlasServer());
        this.registryManager.handleAtlasServerUpdate(packet.getAtlasServer());
    }

    @Override
    public void handleServerListRequest(ServerListRequestPacket packet) {
        this.logger.debug("Server list request packet received (should not happen on client side)");
    }
}