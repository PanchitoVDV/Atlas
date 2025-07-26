package be.esmay.atlas.velocity.modules.scaling.network;

import be.esmay.atlas.common.network.packet.Packet;
import be.esmay.atlas.common.network.packet.PacketDecoder;
import be.esmay.atlas.common.network.packet.PacketEncoder;
import be.esmay.atlas.common.network.packet.packets.HandshakePacket;
import be.esmay.atlas.common.network.packet.packets.HeartbeatPacket;
import be.esmay.atlas.common.network.packet.packets.ServerControlPacket;
import be.esmay.atlas.common.network.packet.packets.ServerInfoUpdatePacket;
import be.esmay.atlas.common.network.packet.packets.ServerListRequestPacket;
import be.esmay.atlas.velocity.AtlasVelocityPlugin;
import be.esmay.atlas.velocity.modules.scaling.cache.NetworkServerCacheManager;
import be.esmay.atlas.velocity.modules.scaling.proxy.ProxyServerInfoManager;
import be.esmay.atlas.velocity.modules.scaling.registry.VelocityServerRegistryManager;
import com.velocitypowered.api.proxy.ProxyServer;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@RequiredArgsConstructor
public final class AtlasNetworkClient {

    private final String host;
    private final int port;
    private final String authToken;
    private final String serverId;
    private final NetworkServerCacheManager cacheManager;
    private final ProxyServerInfoManager serverInfoManager;
    private final VelocityServerRegistryManager registryManager;
    private final ProxyServer proxyServer;
    private final AtlasVelocityPlugin plugin;
    private final Logger logger;

    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean authenticated = new AtomicBoolean(false);
    private final AtomicBoolean shouldReconnect = new AtomicBoolean(true);

    private EventLoopGroup workerGroup;
    private Channel channel;
    private ScheduledExecutorService scheduler;

    public CompletableFuture<Void> connect() {
        if (this.connected.get()) {
            return CompletableFuture.completedFuture(null);
        }

        this.workerGroup = new NioEventLoopGroup();
        this.scheduler = new ScheduledThreadPoolExecutor(1);

        return this.attemptConnection();
    }

    private CompletableFuture<Void> attemptConnection() {
        CompletableFuture<Void> future = new CompletableFuture<>();

        try {
            Bootstrap bootstrap = new Bootstrap();
            Bootstrap groupedBootstrap = bootstrap.group(this.workerGroup);
            Bootstrap channelBootstrap = groupedBootstrap.channel(NioSocketChannel.class);
            Bootstrap optionBootstrap = channelBootstrap.option(ChannelOption.SO_KEEPALIVE, true);

            ChannelInitializer<SocketChannel> channelInitializer = new ChannelInitializer<>() {
                @Override
                protected void initChannel(SocketChannel ch) throws Exception {
                    ch.pipeline().addLast(new ReadTimeoutHandler(60, TimeUnit.SECONDS));
                    ch.pipeline().addLast(new PacketDecoder());
                    ch.pipeline().addLast(new PacketEncoder());

                    VelocityPacketHandler handler = new VelocityPacketHandler(
                            AtlasNetworkClient.this.cacheManager,
                            AtlasNetworkClient.this.registryManager,
                            AtlasNetworkClient.this.proxyServer,
                            AtlasNetworkClient.this.plugin,
                            AtlasNetworkClient.this.logger
                    );

                    handler.setNetworkClient(AtlasNetworkClient.this);
                    ch.pipeline().addLast(handler);
                }
            };

            Bootstrap handlerBootstrap = optionBootstrap.handler(channelInitializer);

            ChannelFuture connectFuture = handlerBootstrap.connect(this.host, this.port);
            connectFuture.addListener(channelFuture -> {
                if (channelFuture.isSuccess()) {
                    this.channel = connectFuture.channel();
                    this.connected.set(true);

                    HandshakePacket handshakePacket = new HandshakePacket(
                            "velocity",
                            "1.0.0",
                            this.authToken,
                            false,
                            null
                    );

                    this.sendPacket(handshakePacket);
                    this.startHeartbeat();

                    future.complete(null);
                } else {
                    this.logger.error("Failed to connect to Atlas base", channelFuture.cause());
                    future.completeExceptionally(channelFuture.cause());

                    if (this.shouldReconnect.get()) {
                        this.scheduleReconnection();
                    }
                }
            });

        } catch (Exception e) {
            this.logger.error("Exception during connection attempt", e);
            future.completeExceptionally(e);
        }

        return future;
    }

    private void scheduleReconnection() {
        this.scheduler.schedule(() -> {
            if (!this.shouldReconnect.get() || this.connected.get())
                return;

            this.logger.info("Attempting to reconnect to Atlas base...");
            this.attemptConnection();
        }, 5, TimeUnit.SECONDS);
    }

    private void startHeartbeat() {
        this.scheduler.scheduleAtFixedRate(() -> {
            if (!this.connected.get() || !this.authenticated.get()) {
                return;
            }

            HeartbeatPacket heartbeatPacket = new HeartbeatPacket(this.serverId, System.currentTimeMillis());
            this.sendPacket(heartbeatPacket);
        }, 5, 5, TimeUnit.SECONDS);
    }

    public void sendPacket(Packet packet) {
        if (this.channel == null || !this.channel.isActive())
            return;

        this.channel.writeAndFlush(packet);
    }

    public void sendServerInfoUpdate() {
        if (!this.authenticated.get())
            return;

        ServerInfoUpdatePacket packet = new ServerInfoUpdatePacket(this.serverId, this.serverInfoManager.getServerInfo());
        this.sendPacket(packet);
    }

    public void disconnect() {
        this.shouldReconnect.set(false);
        this.authenticated.set(false);
        this.connected.set(false);

        if (this.channel != null) {
            this.channel.close();
        }

        if (this.scheduler != null) {
            this.scheduler.shutdown();
        }

        if (this.workerGroup != null) {
            this.workerGroup.shutdownGracefully();
        }

        this.logger.info("Disconnected from Atlas base");
    }

    public void requestServerList() {
        if (!this.authenticated.get())
            return;

        ServerListRequestPacket packet = new ServerListRequestPacket(this.serverId);
        this.sendPacket(packet);
        this.logger.debug("Requested server list from Atlas base");
    }

    public void sendServerControl(String serverIdentifier, ServerControlPacket.ControlAction action) {
        if (!this.authenticated.get())
            return;

        ServerControlPacket packet = new ServerControlPacket(serverIdentifier, action, this.serverId);
        this.sendPacket(packet);
        this.logger.debug("Sent server control {} for server {}", action, serverIdentifier);
    }

    public void onAuthenticated() {
        this.authenticated.set(true);
        this.logger.info("Successfully authenticated with Atlas base");

        this.sendServerInfoUpdate();
        this.requestServerList();
    }

    public void onDisconnected() {
        this.connected.set(false);
        this.authenticated.set(false);

        if (this.shouldReconnect.get()) {
            this.logger.warn("Connection to Atlas base lost, attempting to reconnect...");
            this.scheduleReconnection();
        }
    }

    public boolean isConnected() {
        return this.connected.get();
    }

    public boolean isAuthenticated() {
        return this.authenticated.get();
    }

}