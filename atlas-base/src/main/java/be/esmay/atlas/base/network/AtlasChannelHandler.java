package be.esmay.atlas.base.network;

import be.esmay.atlas.base.AtlasBase;
import be.esmay.atlas.base.network.connection.Connection;
import be.esmay.atlas.base.network.connection.ConnectionManager;
import be.esmay.atlas.base.network.security.AuthenticationHandler;
import be.esmay.atlas.base.utils.Logger;
import be.esmay.atlas.common.enums.ServerStatus;
import be.esmay.atlas.common.enums.ServerType;
import be.esmay.atlas.common.models.ServerInfo;
import be.esmay.atlas.common.network.packet.Packet;
import be.esmay.atlas.common.network.packet.PacketHandler;
import be.esmay.atlas.common.network.packet.packets.AuthenticationPacket;
import be.esmay.atlas.common.network.packet.packets.HandshakePacket;
import be.esmay.atlas.common.network.packet.packets.HeartbeatPacket;
import be.esmay.atlas.common.network.packet.packets.ServerAddPacket;
import be.esmay.atlas.common.network.packet.packets.ServerInfoUpdatePacket;
import be.esmay.atlas.common.network.packet.packets.ServerListPacket;
import be.esmay.atlas.common.network.packet.packets.ServerListRequestPacket;
import be.esmay.atlas.common.network.packet.packets.ServerRemovePacket;
import be.esmay.atlas.common.network.packet.packets.ServerUpdatePacket;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.util.List;
import java.util.stream.Collectors;

public final class AtlasChannelHandler extends SimpleChannelInboundHandler<Packet> implements PacketHandler {

    private final ConnectionManager connectionManager;
    private final AuthenticationHandler authHandler;
    private ChannelHandlerContext currentContext;

    public AtlasChannelHandler(ConnectionManager connectionManager, AuthenticationHandler authHandler) {
        this.connectionManager = connectionManager;
        this.authHandler = authHandler;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        this.connectionManager.addConnection(ctx.channel());
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        this.connectionManager.removeConnection(ctx.channel());
        super.channelInactive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Packet packet) throws Exception {
        this.currentContext = ctx;
        packet.handle(this);
        this.currentContext = null;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        Logger.error("Exception in channel handler", cause);
        ctx.close();
    }

    @Override
    public void handleHandshake(HandshakePacket packet) {
        Logger.debug("Handshake received from {} v{}", packet.getPluginType(), packet.getVersion());

        boolean accepted = this.authHandler.authenticate(packet.getAuthToken());
        String reason = accepted ? "Accepted" : "Invalid authentication token";

        HandshakePacket response = new HandshakePacket(
                packet.getPluginType(),
                packet.getVersion(),
                packet.getAuthToken(),
                accepted,
                reason
        );

        if (this.currentContext != null) {
            this.currentContext.writeAndFlush(response);

            if (accepted) {
                Connection connection = this.connectionManager.getConnection(this.currentContext.channel());
                if (connection != null) {
                    connection.setAuthenticated(true);
                }
            }
        }
    }

    @Override
    public void handleAuthentication(AuthenticationPacket packet) {
        // This is handled during handshake
    }

    @Override
    public void handleHeartbeat(HeartbeatPacket packet) {
        if (this.currentContext == null) {
            return;
        }

        Connection connection = this.connectionManager.getConnection(this.currentContext.channel());
        if (connection == null) {
            Logger.warn("Heartbeat from unknown connection");
            return;
        }

        if (connection.getServerId() == null) {
            this.connectionManager.registerServer(connection, packet.getServerId());
            Logger.debug("Registered server {} from heartbeat", packet.getServerId());
        }

        connection.updateHeartbeat();

        HeartbeatPacket response = new HeartbeatPacket(
                "atlas-base",
                System.currentTimeMillis(),
                0,
                0
        );
        this.currentContext.writeAndFlush(response);

        AtlasBase atlasInstance = AtlasBase.getInstance();
        if (atlasInstance != null && atlasInstance.getScalerManager() != null) {
            atlasInstance.getScalerManager().getScalers().forEach(scaler -> {
                scaler.updateServerPlayerCount(packet.getServerId(), packet.getOnlinePlayers());
                scaler.updateServerCapacity(packet.getServerId(), packet.getMaxPlayers());
                scaler.updateServerStatus(packet.getServerId(), ServerStatus.RUNNING);
            });
        }

        Logger.debug("Heartbeat received from server {} - {} players", packet.getServerId(), packet.getOnlinePlayers());
    }

    @Override
    public void handleServerUpdate(ServerUpdatePacket packet) {
        Logger.debug("Server update received: {}", packet.getServerInfo().getName());
    }

    @Override
    public void handleServerList(ServerListPacket packet) {
        List<ServerInfo> servers = this.getAllServers();
        ServerListPacket response = new ServerListPacket(servers);
    }

    @Override
    public void handleServerAdd(ServerAddPacket packet) {
        Logger.debug("Server add received: {}", packet.getServerInfo().getName());
    }

    @Override
    public void handleServerRemove(ServerRemovePacket packet) {
        Logger.debug("Server remove received: {}", packet.getServerId());
    }

    @Override
    public void handleServerInfoUpdate(ServerInfoUpdatePacket packet) {
        Logger.debug("Server info update received: {}", packet.getServerInfo().getName());

        if (this.currentContext != null) {
            Connection connection = this.connectionManager.getConnection(this.currentContext.channel());
            if (connection != null && connection.getServerId() == null) {
                String serverId = packet.getServerInfo().getServerId();
                this.connectionManager.registerServer(connection, serverId);
                Logger.debug("Registered server {} from info update", serverId);
            }
        }

        this.connectionManager.broadcastPacket(packet);
    }

    @Override
    public void handleServerListRequest(ServerListRequestPacket packet) {
        Logger.debug("Server list request received from: {}", packet.getRequesterId());
        
        List<ServerInfo> servers = this.getAllServers().stream()
                .filter(server -> server.getGroup().equalsIgnoreCase("proxy"))
                .collect(Collectors.toList());
        
        ServerListPacket response = new ServerListPacket(servers);
        
        if (this.currentContext != null) {
            this.currentContext.writeAndFlush(response);
        }
        
        Logger.debug("Sent server list with {} non-proxy servers", servers.size());
    }

    private List<ServerInfo> getAllServers() {
        AtlasBase atlasInstance = AtlasBase.getInstance();
        if (atlasInstance == null || atlasInstance.getScalerManager() == null) {
            return List.of();
        }

        return atlasInstance.getScalerManager().getScalers().stream()
                .flatMap(scaler -> scaler.getServers().stream())
                .collect(Collectors.toList());
    }

}