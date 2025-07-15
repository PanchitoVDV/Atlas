package be.esmay.atlas.base.network;

import be.esmay.atlas.base.config.impl.AtlasConfig;
import be.esmay.atlas.base.network.connection.ConnectionManager;
import be.esmay.atlas.base.utils.SecureKeyGen;
import be.esmay.atlas.common.network.packet.Packet;
import be.esmay.atlas.common.network.packet.packets.ServerAddPacket;
import be.esmay.atlas.common.network.packet.packets.ServerRemovePacket;
import be.esmay.atlas.common.network.packet.packets.ServerUpdatePacket;
import be.esmay.atlas.base.network.security.AuthenticationHandler;
import be.esmay.atlas.base.network.security.ConnectionValidator;
import be.esmay.atlas.base.utils.Logger;
import be.esmay.atlas.common.models.AtlasServer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import lombok.Getter;

import java.util.concurrent.CompletableFuture;

@Getter
public final class NettyServer {
    
    private final AtlasConfig.Network networkConfig;
    private final ConnectionManager connectionManager;
    private final AuthenticationHandler authHandler;
    private final ConnectionValidator connectionValidator;
    
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private ChannelFuture channelFuture;

    private volatile boolean running = false;
    private final String nettyKey;
    
    public NettyServer(AtlasConfig.Network networkConfig) {
        this.nettyKey = SecureKeyGen.generateKey();

        this.networkConfig = networkConfig;
        this.connectionManager = new ConnectionManager(networkConfig.getConnectionTimeout());
        this.authHandler = new AuthenticationHandler(this);
        this.connectionValidator = new ConnectionValidator(networkConfig);
    }
    
    public CompletableFuture<Void> start() {
        if (this.running) {
            return CompletableFuture.completedFuture(null);
        }
        
        return CompletableFuture.runAsync(() -> {
            try {
                this.bossGroup = new NioEventLoopGroup(1);
                this.workerGroup = new NioEventLoopGroup();
                
                ServerBootstrap bootstrap = new ServerBootstrap();
                ServerBootstrap groupedBootstrap = bootstrap.group(this.bossGroup, this.workerGroup);
                ServerBootstrap channelBootstrap = groupedBootstrap.channel(NioServerSocketChannel.class);
                
                AtlasChannelInitializer channelInitializer = new AtlasChannelInitializer(
                    this.connectionManager, 
                    this.authHandler, 
                    this.connectionValidator
                );
                
                ServerBootstrap childHandlerBootstrap = channelBootstrap.childHandler(channelInitializer);
                ServerBootstrap optionBootstrap = childHandlerBootstrap.option(ChannelOption.SO_BACKLOG, 128);
                ServerBootstrap childOptionBootstrap = optionBootstrap.childOption(ChannelOption.SO_KEEPALIVE, true);
                
                this.channelFuture = childOptionBootstrap.bind(
                    this.networkConfig.getBindAddress(), 
                    this.networkConfig.getPort()
                ).sync();
                
                this.running = true;
                Logger.info("Netty server started on {}:{}", 
                    this.networkConfig.getBindAddress(), 
                    this.networkConfig.getPort());
                
                this.channelFuture.channel().closeFuture().sync();
            } catch (Exception e) {
                Logger.error("Failed to start Netty server", e);
                throw new RuntimeException(e);
            } finally {
                this.shutdown();
            }
        });
    }
    
    public void shutdown() {
        if (!this.running) {
            return;
        }
        
        this.running = false;
        
        if (this.connectionManager != null) {
            this.connectionManager.shutdown();
        }
        
        if (this.channelFuture != null) {
            this.channelFuture.channel().close();
        }
        
        if (this.workerGroup != null) {
            this.workerGroup.shutdownGracefully();
        }
        
        if (this.bossGroup != null) {
            this.bossGroup.shutdownGracefully();
        }
        
        Logger.info("Netty server stopped");
    }
    
    public void broadcastServerUpdate(AtlasServer atlasServer) {
        if (!this.running) {
            return;
        }
        
        ServerUpdatePacket packet = new ServerUpdatePacket(atlasServer);
        this.connectionManager.broadcastPacket(packet);
    }
    
    public void broadcastServerAdd(AtlasServer atlasServer) {
        if (!this.running) {
            return;
        }
        
        ServerAddPacket packet = new ServerAddPacket(atlasServer);
        this.connectionManager.broadcastPacket(packet);
    }
    
    public void broadcastServerRemove(String serverId, String reason) {
        if (!this.running) {
            return;
        }
        
        ServerRemovePacket packet = new ServerRemovePacket(serverId, reason);
        this.connectionManager.broadcastPacket(packet);
    }
    
    public void sendToServer(String serverId, Packet packet) {
        if (!this.running) {
            return;
        }
        
        this.connectionManager.sendToServer(serverId, packet);
    }

    public int getConnectionCount() {
        return this.connectionManager.getConnectionCount();
    }
    
    public int getAuthenticatedConnectionCount() {
        return this.connectionManager.getAuthenticatedConnectionCount();
    }
    
}