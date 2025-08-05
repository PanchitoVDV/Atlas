package be.esmay.atlas.velocity.modules.scaling.api;

import be.esmay.atlas.common.enums.ServerStatus;
import be.esmay.atlas.common.models.AtlasServer;
import be.esmay.atlas.common.models.ServerInfo;
import be.esmay.atlas.common.network.packet.packets.MetadataUpdatePacket;
import be.esmay.atlas.common.network.packet.packets.ServerControlPacket;
import be.esmay.atlas.velocity.modules.scaling.cache.NetworkServerCacheManager;
import be.esmay.atlas.velocity.modules.scaling.network.AtlasNetworkClient;
import be.esmay.atlas.velocity.modules.scaling.proxy.ProxyServerInfoManager;
import lombok.experimental.UtilityClass;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@UtilityClass
public final class AtlasVelocityAPI {
    
    private static NetworkServerCacheManager cacheManager;
    private static ProxyServerInfoManager serverInfoManager;
    private static AtlasNetworkClient networkClient;
    private static boolean initialized = false;
    
    public static void initialize(NetworkServerCacheManager cacheManager, ProxyServerInfoManager serverInfoManager, AtlasNetworkClient networkClient) {
        AtlasVelocityAPI.cacheManager = cacheManager;
        AtlasVelocityAPI.serverInfoManager = serverInfoManager;
        AtlasVelocityAPI.networkClient = networkClient;
        AtlasVelocityAPI.initialized = true;
    }
    
    public static void shutdown() {
        AtlasVelocityAPI.cacheManager = null;
        AtlasVelocityAPI.serverInfoManager = null;
        AtlasVelocityAPI.networkClient = null;
        AtlasVelocityAPI.initialized = false;
    }
    
    public static Optional<AtlasServer> getServer(String serverId) {
        if (!AtlasVelocityAPI.initialized) {
            return Optional.empty();
        }

        return AtlasVelocityAPI.cacheManager.getServer(serverId);
    }

    public static Optional<AtlasServer> getServerByName(String serverName) {
        if (!AtlasVelocityAPI.initialized) {
            return Optional.empty();
        }

        return AtlasVelocityAPI.cacheManager.getServerByName(serverName);
    }
    
    public static Collection<AtlasServer> getAllServers() {
        if (!AtlasVelocityAPI.initialized) {
            return List.of();
        }

        return AtlasVelocityAPI.cacheManager.getAllServers();
    }
    
    public static List<AtlasServer> getServersByGroup(String group) {
        if (!AtlasVelocityAPI.initialized) {
            return List.of();
        }

        return AtlasVelocityAPI.cacheManager.getServersByGroup(group);
    }
    
    public static List<AtlasServer> getOnlineServers() {
        if (!AtlasVelocityAPI.initialized) {
            return List.of();
        }

        return AtlasVelocityAPI.cacheManager.getOnlineServers();
    }
    
    public static List<AtlasServer> getBackendServers() {
        if (!AtlasVelocityAPI.initialized) {
            return List.of();
        }

        return AtlasVelocityAPI.cacheManager.getBackendServers();
    }
    
    public static List<AtlasServer> getProxyServers() {
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

        Optional<AtlasServer> server = AtlasVelocityAPI.cacheManager.getServer(serverId);
        return server.isPresent() && server.get().getServerInfo() != null && server.get().getServerInfo().getStatus() == ServerStatus.RUNNING;
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

    public static void startServer(String serverIdentifier) {
        if (!AtlasVelocityAPI.initialized || AtlasVelocityAPI.networkClient == null) {
            return;
        }

        AtlasVelocityAPI.networkClient.sendServerControl(serverIdentifier, ServerControlPacket.ControlAction.START);
    }

    public static void stopServer(String serverIdentifier) {
        if (!AtlasVelocityAPI.initialized || AtlasVelocityAPI.networkClient == null) {
            return;
        }

        AtlasVelocityAPI.networkClient.sendServerControl(serverIdentifier, ServerControlPacket.ControlAction.STOP);
    }

    public static void restartServer(String serverIdentifier) {
        if (!AtlasVelocityAPI.initialized || AtlasVelocityAPI.networkClient == null) {
            return;
        }

        AtlasVelocityAPI.networkClient.sendServerControl(serverIdentifier, ServerControlPacket.ControlAction.RESTART);
    }

    public static void setMetadata(String key, String value) {
        if (!AtlasVelocityAPI.initialized || AtlasVelocityAPI.networkClient == null) {
            return;
        }

        Map<String, String> metadata = new HashMap<>();
        metadata.put(key, value);
        AtlasVelocityAPI.sendMetadataUpdatePacket(metadata);
    }

    public static void setMetadata(Map<String, String> metadata) {
        if (!AtlasVelocityAPI.initialized || AtlasVelocityAPI.networkClient == null || metadata == null) {
            return;
        }

        AtlasVelocityAPI.sendMetadataUpdatePacket(metadata);
    }

    public static String getMetadata(String key) {
        if (!AtlasVelocityAPI.initialized || key == null) {
            return null;
        }

        String serverId = AtlasVelocityAPI.networkClient.getServerId();
        Optional<AtlasServer> server = AtlasVelocityAPI.getServer(serverId);
        return server.map(s -> s.getMetadata(key)).orElse(null);
    }

    public static Map<String, String> getAllMetadata() {
        if (!AtlasVelocityAPI.initialized) {
            return new HashMap<>();
        }

        String serverId = AtlasVelocityAPI.networkClient.getServerId();
        Optional<AtlasServer> server = AtlasVelocityAPI.getServer(serverId);
        return server.map(AtlasServer::getMetadata).orElse(new HashMap<>());
    }

    public static void removeMetadata(String key) {
        if (!AtlasVelocityAPI.initialized || AtlasVelocityAPI.networkClient == null || key == null) {
            return;
        }

        Map<String, String> metadata = new HashMap<>();
        metadata.put(key, null);
        AtlasVelocityAPI.sendMetadataUpdatePacket(metadata);
    }

    private static void sendMetadataUpdatePacket(Map<String, String> metadata) {
        String serverId = AtlasVelocityAPI.networkClient.getServerId();
        MetadataUpdatePacket packet = new MetadataUpdatePacket(serverId, metadata);
        AtlasVelocityAPI.networkClient.sendPacket(packet);
    }
}