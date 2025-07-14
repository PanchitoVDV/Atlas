package be.esmay.atlas.base.network.security;

import be.esmay.atlas.base.config.impl.AtlasConfig;
import be.esmay.atlas.base.utils.Logger;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Set;

@ChannelHandler.Sharable
public final class ConnectionValidator extends ChannelInboundHandlerAdapter {
    
    private final AtlasConfig.Network networkConfig;
    private final Set<String> allowedNetworks;
    
    public ConnectionValidator(AtlasConfig.Network networkConfig) {
        this.networkConfig = networkConfig;
        this.allowedNetworks = new HashSet<>(networkConfig.getAllowedNetworks());
        
        this.initializeAllowedNetworks();
    }
    
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        if (this.isConnectionAllowed(ctx)) {
            super.channelActive(ctx);
        } else {
            ctx.close();
        }
    }
    
    public void addAllowedNetwork(String network) {
        if (network != null && !this.allowedNetworks.contains(network)) {
            this.allowedNetworks.add(network);
            Logger.debug("Added network {} to allowed networks", network);
        }
    }
    
    private boolean isConnectionAllowed(ChannelHandlerContext ctx) {
        if (!(ctx.channel().remoteAddress() instanceof InetSocketAddress)) {
            return false;
        }
        
        InetSocketAddress remoteAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        String clientIp = remoteAddress.getHostString();
        
        for (String allowedNetwork : this.allowedNetworks) {
            if (this.isIpInNetwork(clientIp, allowedNetwork)) {
                Logger.debug("Connection from {} allowed (matches network {})", clientIp, allowedNetwork);
                return true;
            }
        }
        
        Logger.debug("Connection from {} rejected (not in allowed networks)", clientIp);
        return false;
    }
    
    private void initializeAllowedNetworks() {
        Logger.debug("Initial allowed networks: {}", this.allowedNetworks);
    }
    
    
    private boolean isIpInNetwork(String ip, String network) {
        try {
            if (!network.contains("/")) {
                return ip.equals(network);
            }
            
            String[] parts = network.split("/");
            String networkIp = parts[0];
            int prefixLength = Integer.parseInt(parts[1]);
            
            long ipNum = this.ipToLong(ip);
            long networkNum = this.ipToLong(networkIp);
            long mask = (0xFFFFFFFFL << (32 - prefixLength)) & 0xFFFFFFFFL;
            
            return (ipNum & mask) == (networkNum & mask);
        } catch (Exception e) {
            Logger.error("Error checking IP {} against network {}", ip, network, e);
            return false;
        }
    }
    
    private long ipToLong(String ip) {
        String[] parts = ip.split("\\.");
        long result = 0;
        for (int i = 0; i < 4; i++) {
            result = (result << 8) + Integer.parseInt(parts[i]);
        }
        return result;
    }
    
}