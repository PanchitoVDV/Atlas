package be.esmay.atlas.velocity.modules.scaling.network;

import be.esmay.atlas.common.enums.ServerStatus;
import be.esmay.atlas.common.models.AtlasServer;
import be.esmay.atlas.common.network.packet.Packet;
import be.esmay.atlas.common.network.packet.PacketHandler;
import be.esmay.atlas.common.network.packet.packets.AtlasServerUpdatePacket;
import be.esmay.atlas.common.network.packet.packets.AuthenticationPacket;
import be.esmay.atlas.common.network.packet.packets.HandshakePacket;
import be.esmay.atlas.common.network.packet.packets.HeartbeatPacket;
import be.esmay.atlas.common.network.packet.packets.MetadataUpdatePacket;
import be.esmay.atlas.common.network.packet.packets.ServerAddPacket;
import be.esmay.atlas.common.network.packet.packets.ServerCommandPacket;
import be.esmay.atlas.common.network.packet.packets.ServerControlPacket;
import be.esmay.atlas.common.network.packet.packets.ServerInfoUpdatePacket;
import be.esmay.atlas.common.network.packet.packets.ServerListPacket;
import be.esmay.atlas.common.network.packet.packets.ServerListRequestPacket;
import be.esmay.atlas.common.network.packet.packets.ServerRemovePacket;
import be.esmay.atlas.common.network.packet.packets.ServerUpdatePacket;
import be.esmay.atlas.velocity.AtlasVelocityPlugin;
import be.esmay.atlas.velocity.modules.scaling.cache.NetworkServerCacheManager;
import be.esmay.atlas.velocity.modules.scaling.registry.VelocityServerRegistryManager;
import be.esmay.atlas.velocity.utils.ChatUtils;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.slf4j.Logger;

@RequiredArgsConstructor
public final class VelocityPacketHandler extends SimpleChannelInboundHandler<Packet> implements PacketHandler {

    private final NetworkServerCacheManager cacheManager;
    private final VelocityServerRegistryManager registryManager;
    private final ProxyServer proxyServer;
    private final AtlasVelocityPlugin plugin;
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

        AtlasServer oldServer = this.cacheManager.getServer(packet.getAtlasServer().getServerId()).orElse(null);
        if ((oldServer == null && packet.getAtlasServer().getServerInfo().getStatus() == ServerStatus.STARTING) || (oldServer != null && oldServer.getServerInfo().getStatus() != ServerStatus.STARTING && packet.getAtlasServer().getServerInfo().getStatus() == ServerStatus.STARTING)) {
            for (Player player : this.proxyServer.getAllPlayers()) {
                if (!player.hasPermission("atlas.notify")) continue;

                player.sendMessage(ChatUtils.format(this.plugin.getMessagesConfiguration().getServerStartingNotification(), packet.getAtlasServer().getName(), "Node-1"));
            }
        }

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

        AtlasServer atlasServer = this.cacheManager.getServer(packet.getServerId()).orElse(null);
        if (atlasServer == null) {
            this.logger.warn("Received ServerRemovePacket for unknown server: {}", packet.getServerId());
            return;
        }

        this.cacheManager.removeServer(packet.getServerId());
        this.registryManager.handleServerRemove(atlasServer.getName());
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

    @Override
    public void handleServerCommand(ServerCommandPacket packet) {
        this.logger.debug("Server command received: {} for server: {}", packet.getCommand(), packet.getServerId());

        CommandManager commandManager = this.proxyServer.getCommandManager();
        CommandSource consoleSource = this.proxyServer.getConsoleCommandSource();

        commandManager.executeImmediatelyAsync(consoleSource, packet.getCommand()).thenAccept(success -> {
            if (success) {
                this.logger.info("Command executed successfully: {}", packet.getCommand());
                return;
            }

            this.logger.warn("Command execution failed: {}", packet.getCommand());
        }).exceptionally(throwable -> {
            this.logger.error("Exception while executing command: {}", packet.getCommand(), throwable);
            return null;
        });
    }

    @Override
    public void handleServerControl(ServerControlPacket packet) {
        this.logger.debug("Server control packet received (should not happen on client side): {} for server: {}", packet.getAction(), packet.getServerIdentifier());
    }

    @Override
    public void handleMetadataUpdate(MetadataUpdatePacket packet) {
        this.logger.debug("Metadata update received for server: {}", packet.getServerId());
        
        AtlasServer server = this.cacheManager.getServer(packet.getServerId()).orElse(null);
        if (server != null) {
            server.setMetadata(packet.getMetadata());
            this.cacheManager.updateAtlasServer(server);
        }
    }
}