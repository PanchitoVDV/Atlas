package be.esmay.atlas.base.network;

import be.esmay.atlas.base.AtlasBase;
import be.esmay.atlas.base.network.connection.Connection;
import be.esmay.atlas.base.network.connection.ConnectionManager;
import be.esmay.atlas.base.network.packet.Packet;
import be.esmay.atlas.base.network.packet.PacketHandler;
import be.esmay.atlas.base.network.packet.packets.AuthenticationPacket;
import be.esmay.atlas.base.network.packet.packets.HandshakePacket;
import be.esmay.atlas.base.network.packet.packets.HeartbeatPacket;
import be.esmay.atlas.base.network.packet.packets.ServerAddPacket;
import be.esmay.atlas.base.network.packet.packets.ServerListPacket;
import be.esmay.atlas.base.network.packet.packets.ServerRemovePacket;
import be.esmay.atlas.base.network.packet.packets.ServerUpdatePacket;
import be.esmay.atlas.base.network.security.AuthenticationHandler;
import be.esmay.atlas.base.utils.Logger;
import be.esmay.atlas.common.models.ServerInfo;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.util.List;
import java.util.stream.Collectors;

public final class AtlasChannelHandler extends SimpleChannelInboundHandler<Packet> implements PacketHandler {
    
    private final ConnectionManager connectionManager;
    private final AuthenticationHandler authHandler;
    
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
        packet.handle(this);
    }
    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        Logger.error("Exception in channel handler", cause);
        ctx.close();
    }
    
    @Override
    public void handleHandshake(HandshakePacket packet) {
        Logger.info("Handshake received from {} v{}", packet.getPluginType(), packet.getVersion());
        
        boolean accepted = this.authHandler.authenticate(packet.getAuthToken());
        String reason = accepted ? "Accepted" : "Invalid authentication token";
        
        HandshakePacket response = new HandshakePacket(
            packet.getPluginType(),
            packet.getVersion(),
            packet.getAuthToken(),
            accepted,
            reason
        );
    }
    
    @Override
    public void handleAuthentication(AuthenticationPacket packet) {
        // This is handled during handshake
    }
    
    @Override
    public void handleHeartbeat(HeartbeatPacket packet) {
        Connection connection = this.connectionManager.getServerConnection(packet.getServerId());
        if (connection == null) {
            Logger.warn("Heartbeat from unknown server: {}", packet.getServerId());
            return;
        }
        
        connection.updateHeartbeat();
        
        AtlasBase atlasInstance = AtlasBase.getInstance();
        if (atlasInstance != null && atlasInstance.getScalerManager() != null) {
            atlasInstance.getScalerManager().getScalers().forEach(scaler -> {
                scaler.updateServerPlayerCount(packet.getServerId(), packet.getOnlinePlayers());
                scaler.updateServerCapacity(packet.getServerId(), packet.getMaxPlayers());
            });
        }
        
        Logger.debug("Heartbeat received from server {} - {} players", 
            packet.getServerId(), packet.getOnlinePlayers());
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