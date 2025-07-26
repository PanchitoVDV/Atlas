package be.esmay.atlas.base.network;

import be.esmay.atlas.base.AtlasBase;
import be.esmay.atlas.base.network.connection.Connection;
import be.esmay.atlas.base.network.connection.ConnectionManager;
import be.esmay.atlas.base.network.security.AuthenticationHandler;
import be.esmay.atlas.base.provider.ServiceProvider;
import be.esmay.atlas.base.scaler.Scaler;
import be.esmay.atlas.base.utils.Logger;
import be.esmay.atlas.common.enums.ServerStatus;
import be.esmay.atlas.common.models.AtlasServer;
import be.esmay.atlas.common.models.ServerInfo;
import be.esmay.atlas.common.network.packet.Packet;
import be.esmay.atlas.common.network.packet.PacketHandler;
import be.esmay.atlas.common.network.packet.packets.AtlasServerUpdatePacket;
import be.esmay.atlas.common.network.packet.packets.AuthenticationPacket;
import be.esmay.atlas.common.network.packet.packets.HandshakePacket;
import be.esmay.atlas.common.network.packet.packets.HeartbeatPacket;
import be.esmay.atlas.common.network.packet.packets.ServerAddPacket;
import be.esmay.atlas.common.network.packet.packets.ServerCommandPacket;
import be.esmay.atlas.common.network.packet.packets.ServerControlPacket;
import be.esmay.atlas.common.network.packet.packets.ServerInfoUpdatePacket;
import be.esmay.atlas.common.network.packet.packets.ServerListPacket;
import be.esmay.atlas.common.network.packet.packets.ServerListRequestPacket;
import be.esmay.atlas.common.network.packet.packets.ServerRemovePacket;
import be.esmay.atlas.common.network.packet.packets.ServerUpdatePacket;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.util.List;
import java.util.concurrent.CompletableFuture;
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

        HeartbeatPacket response = new HeartbeatPacket("atlas-base", System.currentTimeMillis());
        this.currentContext.writeAndFlush(response);

        AtlasBase atlasInstance = AtlasBase.getInstance();
        if (atlasInstance != null && atlasInstance.getScalerManager() != null) {
            atlasInstance.getScalerManager().getScalers().forEach(scaler -> {
                scaler.updateServerHeartbeat(packet.getServerId());
                scaler.updateServerStatus(packet.getServerId(), ServerStatus.RUNNING);
            });
        }

        Logger.debug("Heartbeat received from server {}", packet.getServerId());
    }

    @Override
    public void handleServerUpdate(ServerUpdatePacket packet) {
        Logger.debug("Server update received: {}", packet.getAtlasServer().getName());
    }

    @Override
    public void handleServerList(ServerListPacket packet) {
        List<AtlasServer> servers = this.getAllAtlasServers();
        ServerListPacket response = new ServerListPacket(servers);
    }

    @Override
    public void handleServerAdd(ServerAddPacket packet) {
        Logger.debug("Server add received: {}", packet.getAtlasServer().getName());
    }

    @Override
    public void handleServerRemove(ServerRemovePacket packet) {
        Logger.debug("Server remove received: {}", packet.getServerId());
    }

    @Override
    public void handleServerInfoUpdate(ServerInfoUpdatePacket packet) {
        Logger.debug("Server info update received from backend server");

        if (this.currentContext == null) {
            return;
        }

        Connection connection = this.connectionManager.getConnection(this.currentContext.channel());
        if (connection == null) {
            Logger.warn("Server info update from unknown connection");
            return;
        }

        String serverId = packet.getServerId();
        ServerInfo serverInfo = packet.getServerInfo();

        if (connection.getServerId() == null) {
            this.connectionManager.registerServer(connection, serverId);
            Logger.debug("Registered server {} from ServerInfoUpdatePacket", serverId);
        }

        Logger.debug("Updating server info for server: {} with status: {}, players: {}/{}", serverId, serverInfo.getStatus(), serverInfo.getOnlinePlayers(), serverInfo.getMaxPlayers());

        connection.updateHeartbeat();

        AtlasBase atlasInstance = AtlasBase.getInstance();
        if (atlasInstance != null && atlasInstance.getScalerManager() != null) {
            boolean found = false;
            for (Scaler scaler : atlasInstance.getScalerManager().getScalers()) {
                if (scaler.getServer(serverId) != null) {
                    scaler.updateServerInfo(serverId, serverInfo);
                    found = true;
                    break;
                }
            }
            
            if (!found) {
                Logger.debug("Server {} not found in any scaler tracking", serverId);
            }
        }
    }

    @Override
    public void handleAtlasServerUpdate(AtlasServerUpdatePacket packet) {
        Logger.warn("Backend server attempted to send AtlasServerUpdatePacket, ignoring");
    }

    @Override
    public void handleServerListRequest(ServerListRequestPacket packet) {
        Logger.debug("Server list request received from: {}", packet.getRequesterId());

        List<AtlasServer> atlasServers = this.getAllAtlasServers().stream()
                .filter(server -> server.getServerInfo() != null && server.getServerInfo().getStatus() == ServerStatus.RUNNING)
                .collect(Collectors.toList());

        ServerListPacket response = new ServerListPacket(atlasServers);

        if (this.currentContext != null) {
            this.currentContext.writeAndFlush(response);
        }

        Logger.debug("Sent server list with {} non-proxy servers", atlasServers.size());
    }

    private List<AtlasServer> getAllAtlasServers() {
        AtlasBase atlasInstance = AtlasBase.getInstance();
        if (atlasInstance == null || atlasInstance.getScalerManager() == null) {
            return List.of();
        }

        return atlasInstance.getScalerManager().getScalers().stream()
                .flatMap(scaler -> scaler.getServers().stream())
                .collect(Collectors.toList());
    }

    @Override
    public void handleServerCommand(ServerCommandPacket packet) {
        Logger.debug("Server command received: {} for server: {}", packet.getCommand(), packet.getServerId());

        if (this.currentContext == null) {
            Logger.warn("Server command received but no current context available");
            return;
        }

        Connection connection = this.connectionManager.getConnection(this.currentContext.channel());
        if (connection == null || !connection.isAuthenticated()) {
            Logger.warn("Server command from unauthenticated connection");
            return;
        }

        Logger.warn("Server command packet received by Atlas base: {} for server: {}. This packet should be handled by the target server.", packet.getCommand(), packet.getServerId());
    }

    @Override
    public void handleServerControl(ServerControlPacket packet) {
        Logger.debug("Server control received: {} for server: {} from requester: {}", packet.getAction(), packet.getServerIdentifier(), packet.getRequesterId());

        if (this.currentContext == null) {
            Logger.warn("Server control received but no current context available");
            return;
        }

        Connection connection = this.connectionManager.getConnection(this.currentContext.channel());
        if (connection == null || !connection.isAuthenticated()) {
            Logger.warn("Server control from unauthenticated connection");
            return;
        }

        AtlasBase atlasInstance = AtlasBase.getInstance();
        if (atlasInstance == null || atlasInstance.getServerManager() == null) {
            Logger.error("Atlas instance or ServerManager is not available");
            return;
        }

        String serverIdentifier = packet.getServerIdentifier();
        ServerControlPacket.ControlAction action = packet.getAction();
        ServiceProvider provider = atlasInstance.getProviderManager().getProvider();

        provider.getServer(serverIdentifier).thenCompose(serverOpt -> {
            if (serverOpt.isPresent()) {
                return CompletableFuture.completedFuture(serverOpt);
            }

            return provider.getAllServers()
                    .thenApply(servers -> servers.stream()
                            .filter(server -> server.getName().equals(serverIdentifier))
                            .findFirst());
        }).thenAccept(serverOpt -> {
            if (serverOpt.isEmpty()) {
                Logger.warn("Server not found: {} for control action: {}", serverIdentifier, action);
                return;
            }

            AtlasServer server = serverOpt.get();

            switch (action) {
                case START -> {
                    atlasInstance.getServerManager().startServer(server);
                    Logger.info("Starting server {} requested by {}", serverIdentifier, packet.getRequesterId());
                }
                case STOP -> {
                    atlasInstance.getServerManager().stopServer(server);
                    Logger.info("Stopping server {} requested by {}", serverIdentifier, packet.getRequesterId());
                }
                case RESTART -> {
                    atlasInstance.getServerManager().restartServer(server);
                    Logger.info("Restarting server {} requested by {}", serverIdentifier, packet.getRequesterId());
                }
            }
        }).exceptionally(throwable -> {
            Logger.error("Failed to handle server control for {}: {}", serverIdentifier, throwable.getMessage());
            return null;
        });
    }

}