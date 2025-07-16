package be.esmay.atlas.spigot.network;

import be.esmay.atlas.common.network.packet.Packet;
import be.esmay.atlas.common.network.packet.PacketHandler;
import be.esmay.atlas.common.network.packet.packets.AtlasServerUpdatePacket;
import be.esmay.atlas.common.network.packet.packets.AuthenticationPacket;
import be.esmay.atlas.common.network.packet.packets.HandshakePacket;
import be.esmay.atlas.common.network.packet.packets.HeartbeatPacket;
import be.esmay.atlas.common.network.packet.packets.ServerAddPacket;
import be.esmay.atlas.common.network.packet.packets.ServerCommandPacket;
import be.esmay.atlas.common.network.packet.packets.ServerInfoUpdatePacket;
import be.esmay.atlas.common.network.packet.packets.ServerListPacket;
import be.esmay.atlas.common.network.packet.packets.ServerListRequestPacket;
import be.esmay.atlas.common.network.packet.packets.ServerRemovePacket;
import be.esmay.atlas.common.network.packet.packets.ServerUpdatePacket;
import be.esmay.atlas.spigot.AtlasSpigotPlugin;
import be.esmay.atlas.spigot.cache.NetworkServerCacheManager;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.bukkit.Bukkit;

import java.util.logging.Logger;

@RequiredArgsConstructor
public final class SpigotPacketHandler extends SimpleChannelInboundHandler<Packet> implements PacketHandler {

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
        this.logger.severe("Exception in packet handler");
        cause.printStackTrace();
        ctx.close();
    }

    @Override
    public void handleHandshake(HandshakePacket packet) {
        if (!packet.isAccepted()) {
            this.logger.severe("Handshake failed: " + packet.getReason());
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

        this.logger.info("Registered " + packet.getAtlasServers().size() + " servers on startup");
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
        Bukkit.getScheduler().runTask(AtlasSpigotPlugin.getInstance(), () -> {
            try {
                boolean success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), packet.getCommand());
                if (success) {
                    this.logger.info("Command executed successfully: " + packet.getCommand());
                    return;
                }

                this.logger.warning("Command execution failed: " + packet.getCommand());
            } catch (Exception e) {
                this.logger.severe("Exception while executing command: " + packet.getCommand());
                e.printStackTrace();
            }
        });
    }
}