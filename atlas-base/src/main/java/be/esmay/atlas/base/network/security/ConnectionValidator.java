package be.esmay.atlas.base.network.security;

import be.esmay.atlas.base.AtlasBase;
import be.esmay.atlas.base.config.impl.AtlasConfig;
import be.esmay.atlas.base.provider.impl.DockerServiceProvider;
import be.esmay.atlas.base.utils.Logger;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Network;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        
        Logger.warn("Connection from {} rejected (not in allowed networks)", clientIp);
        return false;
    }
    
    private void initializeAllowedNetworks() {
        AtlasBase atlasInstance = AtlasBase.getInstance();
        if (atlasInstance == null || atlasInstance.getProviderManager() == null) {
            return;
        }
        
        if (atlasInstance.getProviderManager().getProvider() instanceof DockerServiceProvider) {
            String dockerNetworkCidr = this.getDockerNetworkCidr();
            if (dockerNetworkCidr != null) {
                this.allowedNetworks.add(dockerNetworkCidr);
                Logger.info("Automatically added Docker network {} to allowed networks", dockerNetworkCidr);
            }
        }
    }
    
    private String getDockerNetworkCidr() {
        try {
            AtlasBase atlasInstance = AtlasBase.getInstance();
            DockerServiceProvider dockerProvider = (DockerServiceProvider) atlasInstance.getProviderManager().getProvider();
            
            AtlasConfig.Docker dockerConfig = atlasInstance.getConfigManager().getAtlasConfig()
                .getAtlas().getServiceProvider().getDocker();
            
            DockerClient dockerClient = this.getDockerClient(dockerProvider);
            if (dockerClient == null) {
                return null;
            }
            
            List<Network> networks = dockerClient.listNetworksCmd()
                .withNameFilter(dockerConfig.getNetwork())
                .exec();
            
            if (!networks.isEmpty()) {
                Network network = networks.get(0);
                if (network.getIpam() != null && network.getIpam().getConfig() != null 
                    && !network.getIpam().getConfig().isEmpty()) {
                    return network.getIpam().getConfig().get(0).getSubnet();
                }
            }
        } catch (Exception e) {
            Logger.error("Failed to get Docker network CIDR", e);
        }
        return null;
    }
    
    private DockerClient getDockerClient(DockerServiceProvider provider) {
        try {
            return (DockerClient) provider.getClass().getDeclaredField("dockerClient").get(provider);
        } catch (Exception e) {
            Logger.error("Failed to access Docker client", e);
            return null;
        }
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