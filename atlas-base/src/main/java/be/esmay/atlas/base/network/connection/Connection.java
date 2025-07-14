package be.esmay.atlas.base.network.connection;

import be.esmay.atlas.base.network.packet.Packet;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import lombok.Getter;
import lombok.Setter;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

@Getter
public final class Connection {
    
    private final Channel channel;
    private final String remoteAddress;
    private final long connectionTime;
    private final AtomicBoolean authenticated;
    
    @Setter
    private String serverId;
    @Setter
    private String pluginType;
    @Setter
    private String version;
    private long lastHeartbeat;
    
    public Connection(Channel channel) {
        this.channel = channel;
        this.remoteAddress = this.extractRemoteAddress(channel);
        this.connectionTime = System.currentTimeMillis();
        this.authenticated = new AtomicBoolean(false);
        this.lastHeartbeat = System.currentTimeMillis();
    }
    
    public void sendPacket(Packet packet) {
        if (this.channel.isActive()) {
            this.channel.writeAndFlush(packet);
        }
    }
    
    public ChannelFuture sendPacketAsync(Packet packet) {
        if (this.channel.isActive()) {
            return this.channel.writeAndFlush(packet);
        }
        return null;
    }
    
    public boolean isActive() {
        return this.channel.isActive();
    }
    
    public void disconnect() {
        if (this.channel.isActive()) {
            this.channel.close();
        }
    }
    
    public void setAuthenticated(boolean authenticated) {
        this.authenticated.set(authenticated);
    }
    
    public boolean isAuthenticated() {
        return this.authenticated.get();
    }

    public void updateHeartbeat() {
        this.lastHeartbeat = System.currentTimeMillis();
    }
    
    private String extractRemoteAddress(Channel channel) {
        if (channel.remoteAddress() instanceof InetSocketAddress) {
            InetSocketAddress socketAddress = (InetSocketAddress) channel.remoteAddress();
            return socketAddress.getHostString();
        }
        return channel.remoteAddress().toString();
    }
    
}