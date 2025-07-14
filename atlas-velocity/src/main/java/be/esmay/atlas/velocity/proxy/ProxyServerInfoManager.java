package be.esmay.atlas.velocity.proxy;

import be.esmay.atlas.common.enums.ServerStatus;
import be.esmay.atlas.common.enums.ServerType;
import be.esmay.atlas.common.models.ServerInfo;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import lombok.Getter;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class ProxyServerInfoManager {
    
    @Getter
    private final String serverId;
    private final ProxyServer proxyServer;
    private final AtomicInteger onlinePlayerCount = new AtomicInteger(0);
    private final Map<UUID, String> playerRouting = new ConcurrentHashMap<>();
    
    public ProxyServerInfoManager(String serverId, ProxyServer proxyServer) {
        this.serverId = serverId;
        this.proxyServer = proxyServer;
    }
    
    public void addPlayer(Player player) {
        this.onlinePlayerCount.incrementAndGet();
    }
    
    public void removePlayer(Player player) {
        this.onlinePlayerCount.decrementAndGet();
        this.playerRouting.remove(player.getUniqueId());
    }
    
    public void updatePlayerRouting(Player player, String previousServer, String newServer) {
        if (newServer != null) {
            this.playerRouting.put(player.getUniqueId(), newServer);
        } else {
            this.playerRouting.remove(player.getUniqueId());
        }
    }
    
    public int getOnlinePlayerCount() {
        return this.onlinePlayerCount.get();
    }
    
    public int getMaxPlayerCount() {
        return this.proxyServer.getConfiguration().getShowMaxPlayers();
    }
    
    public ServerInfo getServerInfo() {
        return ServerInfo.builder()
            .serverId(this.serverId)
            .name(this.serverId)
            .group(System.getenv("SERVER_GROUP"))
            .workingDirectory(null)
            .address(this.getDockerInternalIp())
            .port(this.proxyServer.getBoundAddress().getPort())
            .type(ServerType.STATIC)
            .status(ServerStatus.RUNNING)
            .onlinePlayers(this.onlinePlayerCount.get())
            .maxPlayers(this.getMaxPlayerCount())
            .onlinePlayerNames(new HashSet<>())
            .createdAt(System.currentTimeMillis())
            .lastHeartbeat(System.currentTimeMillis())
            .serviceProviderId(null)
            .isManuallyScaled(false)
            .build();
    }

    public Map<UUID, String> getPlayerRouting() {
        return Map.copyOf(this.playerRouting);
    }
    
    private String getDockerInternalIp() {
        try {
            NetworkInterface networkInterface = NetworkInterface.getByName("eth0");
            if (networkInterface != null) {
                for (InterfaceAddress address : networkInterface.getInterfaceAddresses()) {
                    InetAddress inetAddress = address.getAddress();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}

        try {
            InetAddress localHost = InetAddress.getLocalHost();
            if (!localHost.isLoopbackAddress()) {
                return localHost.getHostAddress();
            }
        } catch (Exception ignored) {}
        
        return "127.0.0.1";
    }
    
}