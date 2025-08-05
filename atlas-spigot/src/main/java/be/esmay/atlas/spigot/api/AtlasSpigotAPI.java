package be.esmay.atlas.spigot.api;

import be.esmay.atlas.common.enums.ServerStatus;
import be.esmay.atlas.common.models.AtlasServer;
import be.esmay.atlas.common.models.ServerInfo;
import be.esmay.atlas.common.network.packet.packets.MetadataUpdatePacket;
import be.esmay.atlas.common.network.packet.packets.ServerControlPacket;
import be.esmay.atlas.spigot.cache.NetworkServerCacheManager;
import be.esmay.atlas.spigot.network.AtlasNetworkClient;
import be.esmay.atlas.spigot.server.SpigotServerInfoManager;
import lombok.experimental.UtilityClass;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@UtilityClass
public final class AtlasSpigotAPI {
    
    private static NetworkServerCacheManager cacheManager;
    private static SpigotServerInfoManager serverInfoManager;
    private static AtlasNetworkClient networkClient;
    private static boolean initialized = false;
    
    public static void initialize(NetworkServerCacheManager cacheManager, SpigotServerInfoManager serverInfoManager, AtlasNetworkClient networkClient) {
        AtlasSpigotAPI.cacheManager = cacheManager;
        AtlasSpigotAPI.serverInfoManager = serverInfoManager;
        AtlasSpigotAPI.networkClient = networkClient;
        AtlasSpigotAPI.initialized = true;
    }
    
    public static void shutdown() {
        AtlasSpigotAPI.cacheManager = null;
        AtlasSpigotAPI.serverInfoManager = null;
        AtlasSpigotAPI.networkClient = null;
        AtlasSpigotAPI.initialized = false;
    }
    
    public static void setInitialized(boolean initialized) {
        AtlasSpigotAPI.initialized = initialized;
    }
    
    public static Optional<AtlasServer> getServer(String serverId) {
        if (!AtlasSpigotAPI.initialized) {
            return Optional.empty();
        }

        return AtlasSpigotAPI.cacheManager.getServer(serverId);
    }
    
    public static Collection<AtlasServer> getAllServers() {
        if (!AtlasSpigotAPI.initialized) {
            return List.of();
        }

        return AtlasSpigotAPI.cacheManager.getAllServers();
    }
    
    public static List<AtlasServer> getServersByGroup(String group) {
        if (!AtlasSpigotAPI.initialized) {
            return List.of();
        }

        return AtlasSpigotAPI.cacheManager.getServersByGroup(group);
    }
    
    public static List<AtlasServer> getOnlineServers() {
        if (!AtlasSpigotAPI.initialized) {
            return List.of();
        }

        return AtlasSpigotAPI.cacheManager.getOnlineServers();
    }
    
    public static List<AtlasServer> getBackendServers() {
        if (!AtlasSpigotAPI.initialized) {
            return List.of();
        }

        return AtlasSpigotAPI.cacheManager.getBackendServers();
    }
    
    public static List<AtlasServer> getProxyServers() {
        if (!AtlasSpigotAPI.initialized) {
            return List.of();
        }

        return AtlasSpigotAPI.cacheManager.getProxyServers();
    }
    
    public static int getNetworkPlayerCount() {
        if (!AtlasSpigotAPI.initialized) {
            return 0;
        }

        return AtlasSpigotAPI.cacheManager.getTotalPlayers();
    }
    
    public static int getBackendPlayerCount() {
        if (!AtlasSpigotAPI.initialized) {
            return 0;
        }

        return AtlasSpigotAPI.cacheManager.getTotalBackendPlayers();
    }
    
    public static int getProxyPlayerCount() {
        if (!AtlasSpigotAPI.initialized) {
            return 0;
        }

        return AtlasSpigotAPI.cacheManager.getTotalProxyPlayers();
    }
    
    public static boolean isServerOnline(String serverId) {
        if (!AtlasSpigotAPI.initialized) {
            return false;
        }

        Optional<AtlasServer> server = AtlasSpigotAPI.cacheManager.getServer(serverId);
        return server.isPresent() && server.get().getServerInfo() != null && server.get().getServerInfo().getStatus() == ServerStatus.RUNNING;
    }
    
    public static ServerInfo getThisServerInfo() {
        if (!AtlasSpigotAPI.initialized) {
            return null;
        }

        return AtlasSpigotAPI.serverInfoManager.getCurrentServerInfo();
    }
    
    public static boolean isInitialized() {
        return AtlasSpigotAPI.initialized;
    }
    
    public static boolean isConnectedToAtlas() {
        if (!AtlasSpigotAPI.initialized) {
            return false;
        }

        return AtlasSpigotAPI.networkClient.isConnected();
    }
    
    public static boolean isAuthenticatedWithAtlas() {
        if (!AtlasSpigotAPI.initialized) {
            return false;
        }

        return AtlasSpigotAPI.networkClient.isAuthenticated();
    }
    
    public static void requestServerListUpdate() {
        if (!AtlasSpigotAPI.initialized) {
            return;
        }

        AtlasSpigotAPI.networkClient.requestServerList();
    }
    
    public static void sendServerInfoUpdate() {
        if (!AtlasSpigotAPI.initialized) {
            return;
        }

        AtlasSpigotAPI.networkClient.sendServerInfoUpdate();
    }

    public static void startServer(String serverIdentifier) {
        if (!AtlasSpigotAPI.initialized || AtlasSpigotAPI.networkClient == null) {
            return;
        }

        AtlasSpigotAPI.networkClient.sendServerControl(serverIdentifier, ServerControlPacket.ControlAction.START);
    }

    public static void stopServer(String serverIdentifier) {
        if (!AtlasSpigotAPI.initialized || AtlasSpigotAPI.networkClient == null) {
            return;
        }

        AtlasSpigotAPI.networkClient.sendServerControl(serverIdentifier, ServerControlPacket.ControlAction.STOP);
    }

    public static void restartServer(String serverIdentifier) {
        if (!AtlasSpigotAPI.initialized || AtlasSpigotAPI.networkClient == null) {
            return;
        }

        AtlasSpigotAPI.networkClient.sendServerControl(serverIdentifier, ServerControlPacket.ControlAction.RESTART);
    }

    public static void setMetadata(String key, String value) {
        if (!AtlasSpigotAPI.initialized || AtlasSpigotAPI.networkClient == null) {
            return;
        }

        Map<String, String> metadata = new HashMap<>();
        metadata.put(key, value);
        AtlasSpigotAPI.sendMetadataUpdatePacket(metadata);
    }

    public static void setMetadata(Map<String, String> metadata) {
        if (!AtlasSpigotAPI.initialized || AtlasSpigotAPI.networkClient == null || metadata == null) {
            return;
        }

        AtlasSpigotAPI.sendMetadataUpdatePacket(metadata);
    }

    public static String getMetadata(String key) {
        if (!AtlasSpigotAPI.initialized || key == null) {
            return null;
        }

        String serverId = AtlasSpigotAPI.networkClient.getServerId();
        Optional<AtlasServer> server = AtlasSpigotAPI.getServer(serverId);
        return server.map(s -> s.getMetadata(key)).orElse(null);
    }

    public static Map<String, String> getAllMetadata() {
        if (!AtlasSpigotAPI.initialized) {
            return new HashMap<>();
        }

        String serverId = AtlasSpigotAPI.networkClient.getServerId();
        Optional<AtlasServer> server = AtlasSpigotAPI.getServer(serverId);
        return server.map(AtlasServer::getMetadata).orElse(new HashMap<>());
    }

    public static void removeMetadata(String key) {
        if (!AtlasSpigotAPI.initialized || AtlasSpigotAPI.networkClient == null || key == null) {
            return;
        }

        Map<String, String> metadata = new HashMap<>();
        metadata.put(key, null);
        AtlasSpigotAPI.sendMetadataUpdatePacket(metadata);
    }

    private static void sendMetadataUpdatePacket(Map<String, String> metadata) {
        String serverId = AtlasSpigotAPI.networkClient.getServerId();
        MetadataUpdatePacket packet = new MetadataUpdatePacket(serverId, metadata);
        AtlasSpigotAPI.networkClient.sendPacket(packet);
    }
}