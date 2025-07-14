package be.esmay.atlas.velocity.cache;

import be.esmay.atlas.common.enums.ServerStatus;
import be.esmay.atlas.common.models.ServerInfo;
import be.esmay.atlas.velocity.events.AtlasServerUpdateEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import lombok.Getter;
import lombok.Setter;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Getter
public final class NetworkServerCacheManager {
    
    private final Map<String, ServerInfo> serverCache = new ConcurrentHashMap<>();

    @Setter
    private volatile ProxyServer proxyServer;
    
    public void updateServer(ServerInfo serverInfo) {
        ServerInfo previousInfo = this.serverCache.put(serverInfo.getServerId(), serverInfo);
        
        if (this.proxyServer != null) {
            AtlasServerUpdateEvent event = new AtlasServerUpdateEvent(serverInfo, previousInfo);
            this.proxyServer.getEventManager().fireAndForget(event);
        }
    }
    
    public void removeServer(String serverId) {
        ServerInfo removedInfo = this.serverCache.remove(serverId);
        
        if (this.proxyServer != null && removedInfo != null) {
            AtlasServerUpdateEvent event = new AtlasServerUpdateEvent(null, removedInfo);
            this.proxyServer.getEventManager().fireAndForget(event);
        }
    }
    
    public Optional<ServerInfo> getServer(String serverId) {
        return Optional.ofNullable(this.serverCache.get(serverId));
    }
    
    public Collection<ServerInfo> getAllServers() {
        return this.serverCache.values();
    }
    
    public List<ServerInfo> getServersByGroup(String group) {
        return this.serverCache.values().stream()
            .filter(server -> server.getGroup().equals(group))
            .collect(Collectors.toList());
    }
    
    public List<ServerInfo> getOnlineServers() {
        return this.serverCache.values().stream()
            .filter(server -> server.getStatus() == ServerStatus.RUNNING)
            .collect(Collectors.toList());
    }
    
    public List<ServerInfo> getBackendServers() {
        return this.serverCache.values().stream()
            .filter(server -> !server.getGroup().equals("proxy"))
            .collect(Collectors.toList());
    }
    
    public List<ServerInfo> getProxyServers() {
        return this.serverCache.values().stream()
            .filter(server -> server.getGroup().equals("proxy"))
            .collect(Collectors.toList());
    }
    
    public int getTotalPlayers() {
        return this.serverCache.values().stream()
            .mapToInt(ServerInfo::getOnlinePlayers)
            .sum();
    }
    
    public int getTotalBackendPlayers() {
        return this.getBackendServers().stream()
            .mapToInt(ServerInfo::getOnlinePlayers)
            .sum();
    }
    
    public int getTotalProxyPlayers() {
        return this.getProxyServers().stream()
            .mapToInt(ServerInfo::getOnlinePlayers)
            .sum();
    }
    
    public void clear() {
        this.serverCache.clear();
    }
    
    public int getServerCount() {
        return this.serverCache.size();
    }
    
    public boolean hasServer(String serverId) {
        return this.serverCache.containsKey(serverId);
    }

}