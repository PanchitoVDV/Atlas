package be.esmay.atlas.velocity.api;

import be.esmay.atlas.common.enums.ServerStatus;
import be.esmay.atlas.common.models.ServerInfo;
import be.esmay.atlas.velocity.cache.NetworkServerCacheManager;
import be.esmay.atlas.velocity.proxy.ProxyServerInfoManager;
import lombok.experimental.UtilityClass;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@UtilityClass
public final class AtlasVelocityAPI {
    
    private static NetworkServerCacheManager cacheManager;
    private static ProxyServerInfoManager serverInfoManager;
    private static boolean initialized = false;
    
    public static void initialize(NetworkServerCacheManager cacheManager, ProxyServerInfoManager serverInfoManager) {
        AtlasVelocityAPI.cacheManager = cacheManager;
        AtlasVelocityAPI.serverInfoManager = serverInfoManager;
        AtlasVelocityAPI.initialized = true;
    }
    
    public static void shutdown() {
        AtlasVelocityAPI.cacheManager = null;
        AtlasVelocityAPI.serverInfoManager = null;
        AtlasVelocityAPI.initialized = false;
    }
    
    public static Optional<ServerInfo> getServer(String serverId) {
        if (!AtlasVelocityAPI.initialized) {
            return Optional.empty();
        }
        return AtlasVelocityAPI.cacheManager.getServer(serverId);
    }
    
    public static Collection<ServerInfo> getAllServers() {
        if (!AtlasVelocityAPI.initialized) {
            return List.of();
        }
        return AtlasVelocityAPI.cacheManager.getAllServers();
    }
    
    public static List<ServerInfo> getServersByGroup(String group) {
        if (!AtlasVelocityAPI.initialized) {
            return List.of();
        }
        return AtlasVelocityAPI.cacheManager.getServersByGroup(group);
    }
    
    public static List<ServerInfo> getOnlineServers() {
        if (!AtlasVelocityAPI.initialized) {
            return List.of();
        }
        return AtlasVelocityAPI.cacheManager.getOnlineServers();
    }
    
    public static List<ServerInfo> getBackendServers() {
        if (!AtlasVelocityAPI.initialized) {
            return List.of();
        }
        return AtlasVelocityAPI.cacheManager.getBackendServers();
    }
    
    public static List<ServerInfo> getProxyServers() {
        if (!AtlasVelocityAPI.initialized) {
            return List.of();
        }
        return AtlasVelocityAPI.cacheManager.getProxyServers();
    }
    
    public static int getNetworkPlayerCount() {
        if (!AtlasVelocityAPI.initialized) {
            return 0;
        }
        return AtlasVelocityAPI.cacheManager.getTotalPlayers();
    }
    
    public static int getBackendPlayerCount() {
        if (!AtlasVelocityAPI.initialized) {
            return 0;
        }
        return AtlasVelocityAPI.cacheManager.getTotalBackendPlayers();
    }
    
    public static int getProxyPlayerCount() {
        if (!AtlasVelocityAPI.initialized) {
            return 0;
        }
        return AtlasVelocityAPI.cacheManager.getTotalProxyPlayers();
    }
    
    public static boolean isServerOnline(String serverId) {
        if (!AtlasVelocityAPI.initialized) {
            return false;
        }
        Optional<ServerInfo> server = AtlasVelocityAPI.cacheManager.getServer(serverId);
        return server.isPresent() && server.get().getStatus() == ServerStatus.RUNNING;
    }
    
    public static ServerInfo getThisProxyInfo() {
        if (!AtlasVelocityAPI.initialized) {
            return null;
        }
        return AtlasVelocityAPI.serverInfoManager.getServerInfo();
    }
    
    public static boolean isInitialized() {
        return AtlasVelocityAPI.initialized;
    }
    
}