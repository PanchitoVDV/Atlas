package be.esmay.atlas.minestom.network;

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
import be.esmay.atlas.minestom.cache.NetworkServerCacheManager;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.CommandResult;
import org.slf4j.Logger;

@RequiredArgsConstructor
public final class MinestomPacketHandler extends SimpleChannelInboundHandler<Packet> implements PacketHandler {

    private final NetworkServerCacheManager cacheManager;
    private final Logger logger;

    @Setter
    private AtlasNetworkClient networkClient;

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
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
        if (!packet.isAccepted()) {
            this.logger.error("Handshake failed: {}", packet.getReason());
            return;
        }

        if (this.networkClient == null) return;

        this.networkClient.onAuthenticated();
    }

    @Override
    public void handleAuthentication(AuthenticationPacket packet) {
    }

    @Override
    public void handleHeartbeat(HeartbeatPacket packet) {
    }

    @Override
    public void handleServerUpdate(ServerUpdatePacket packet) {
        this.cacheManager.updateAtlasServer(packet.getAtlasServer());
    }

    @Override
    public void handleServerList(ServerListPacket packet) {
        packet.getAtlasServers().forEach(this.cacheManager::updateAtlasServer);

        this.logger.info("Registered {} servers on startup", packet.getAtlasServers().size());
    }

    @Override
    public void handleServerAdd(ServerAddPacket packet) {
        this.cacheManager.updateAtlasServer(packet.getAtlasServer());
    }

    @Override
    public void handleServerRemove(ServerRemovePacket packet) {
        this.cacheManager.removeServer(packet.getServerId());
    }

    @Override
    public void handleServerInfoUpdate(ServerInfoUpdatePacket packet) {
    }

    @Override
    public void handleAtlasServerUpdate(AtlasServerUpdatePacket packet) {
        this.cacheManager.updateAtlasServer(packet.getAtlasServer());
    }

    @Override
    public void handleServerListRequest(ServerListRequestPacket packet) {
    }

    @Override
    public void handleServerCommand(ServerCommandPacket packet) {
        try {
            CommandResult result = MinecraftServer.getCommandManager().execute(MinecraftServer.getCommandManager().getConsoleSender(), packet.getCommand());
            if (result.getType() == CommandResult.Type.SUCCESS) {
                this.logger.info("Command executed successfully: {}", packet.getCommand());
                return;
            }

            this.logger.warn("Command execution failed: {}", packet.getCommand());
        } catch (Exception e) {
            this.logger.error("Exception while executing command: {}", packet.getCommand(), e);
        }
    }

    @Override
    public void handleServerControl(ServerControlPacket packet) {
        this.logger.info("Server control packet received (should not happen on client side): {} for server: {}", packet.getAction(), packet.getServerIdentifier());
    }
}