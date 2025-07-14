package be.esmay.atlas.base.network;

import be.esmay.atlas.base.network.connection.ConnectionManager;
import be.esmay.atlas.common.network.packet.PacketDecoder;
import be.esmay.atlas.common.network.packet.PacketEncoder;
import be.esmay.atlas.base.network.security.AuthenticationHandler;
import be.esmay.atlas.base.network.security.ConnectionValidator;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;

import java.util.concurrent.TimeUnit;

public final class AtlasChannelInitializer extends ChannelInitializer<SocketChannel> {
    
    private final ConnectionManager connectionManager;
    private final AuthenticationHandler authHandler;
    private final ConnectionValidator connectionValidator;
    
    public AtlasChannelInitializer(ConnectionManager connectionManager, AuthenticationHandler authHandler, ConnectionValidator connectionValidator) {
        this.connectionManager = connectionManager;
        this.authHandler = authHandler;
        this.connectionValidator = connectionValidator;
    }
    
    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();
        
        pipeline.addLast("timeout", new ReadTimeoutHandler(60, TimeUnit.SECONDS));
        pipeline.addLast("validator", this.connectionValidator);
        pipeline.addLast("decoder", new PacketDecoder());
        pipeline.addLast("encoder", new PacketEncoder());
        pipeline.addLast("handler", new AtlasChannelHandler(this.connectionManager, this.authHandler));
    }
    
}