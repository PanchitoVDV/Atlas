package be.esmay.atlas.minestom.api;

import be.esmay.atlas.common.enums.ServerStatus;
import be.esmay.atlas.common.models.AtlasServer;
import be.esmay.atlas.common.models.ServerInfo;
import be.esmay.atlas.common.network.packet.packets.MetadataUpdatePacket;
import be.esmay.atlas.common.network.packet.packets.ServerControlPacket;
import be.esmay.atlas.minestom.AtlasMinestomPlugin;
import be.esmay.atlas.minestom.cache.NetworkServerCacheManager;
import be.esmay.atlas.minestom.network.AtlasNetworkClient;
import be.esmay.atlas.minestom.server.MinestomServerInfoManager;
import lombok.experimental.UtilityClass;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@UtilityClass
public final class AtlasMinestomAPI {

    private static NetworkServerCacheManager cacheManager;
    private static MinestomServerInfoManager serverInfoManager;
    private static AtlasNetworkClient networkClient;
    private static AtlasMinestomPlugin plugin;
    private static boolean initialized = false;
    

    public static void initialize(NetworkServerCacheManager cacheManager, MinestomServerInfoManager serverInfoManager, AtlasNetworkClient networkClient, AtlasMinestomPlugin plugin) {
        AtlasMinestomAPI.cacheManager = cacheManager;
        AtlasMinestomAPI.serverInfoManager = serverInfoManager;
        AtlasMinestomAPI.networkClient = networkClient;
        AtlasMinestomAPI.plugin = plugin;
        AtlasMinestomAPI.initialized = true;
    }
    

    public static void shutdown() {
        AtlasMinestomAPI.cacheManager = null;
        AtlasMinestomAPI.serverInfoManager = null;
        AtlasMinestomAPI.networkClient = null;
        AtlasMinestomAPI.plugin = null;
        AtlasMinestomAPI.initialized = false;
    }

    public static void setInitialized(boolean initialized) {
        AtlasMinestomAPI.initialized = initialized;
    }

    public static Optional<AtlasServer> getServer(String serverId) {
        if (!AtlasMinestomAPI.initialized) {
            return Optional.empty();
        }

        return AtlasMinestomAPI.cacheManager.getServer(serverId);
    }

    public static Collection<AtlasServer> getAllServers() {
        if (!AtlasMinestomAPI.initialized) {
            return List.of();
        }

        return AtlasMinestomAPI.cacheManager.getAllServers();
    }

    public static List<AtlasServer> getServersByGroup(String group) {
        if (!AtlasMinestomAPI.initialized) {
            return List.of();
        }

        return AtlasMinestomAPI.cacheManager.getServersByGroup(group);
    }

    public static List<AtlasServer> getOnlineServers() {
        if (!AtlasMinestomAPI.initialized) {
            return List.of();
        }

        return AtlasMinestomAPI.cacheManager.getOnlineServers();
    }

    public static List<AtlasServer> getBackendServers() {
        if (!AtlasMinestomAPI.initialized) {
            return List.of();
        }

        return AtlasMinestomAPI.cacheManager.getBackendServers();
    }

    public static List<AtlasServer> getProxyServers() {
        if (!AtlasMinestomAPI.initialized) {
            return List.of();
        }

        return AtlasMinestomAPI.cacheManager.getProxyServers();
    }

    public static int getNetworkPlayerCount() {
        if (!AtlasMinestomAPI.initialized) {
            return 0;
        }

        return AtlasMinestomAPI.cacheManager.getTotalPlayers();
    }

    public static int getBackendPlayerCount() {
        if (!AtlasMinestomAPI.initialized) {
            return 0;
        }

        return AtlasMinestomAPI.cacheManager.getTotalBackendPlayers();
    }

    public static int getProxyPlayerCount() {
        if (!AtlasMinestomAPI.initialized) {
            return 0;
        }

        return AtlasMinestomAPI.cacheManager.getTotalProxyPlayers();
    }

    public static boolean isServerOnline(String serverId) {
        if (!AtlasMinestomAPI.initialized) {
            return false;
        }

        Optional<AtlasServer> server = AtlasMinestomAPI.cacheManager.getServer(serverId);
        return server.isPresent() && server.get().getServerInfo() != null && server.get().getServerInfo().getStatus() == ServerStatus.RUNNING;
    }

    public static ServerInfo getThisServerInfo() {
        if (!AtlasMinestomAPI.initialized) {
            return null;
        }

        return AtlasMinestomAPI.serverInfoManager.getCurrentServerInfo();
    }

    public static boolean isInitialized() {
        return AtlasMinestomAPI.initialized;
    }

    public static boolean isConnectedToAtlas() {
        if (!AtlasMinestomAPI.initialized) {
            return false;
        }

        return AtlasMinestomAPI.networkClient.isConnected();
    }

    public static boolean isAuthenticatedWithAtlas() {
        if (!AtlasMinestomAPI.initialized) {
            return false;
        }

        return AtlasMinestomAPI.networkClient.isAuthenticated();
    }

    public static void requestServerListUpdate() {
        if (!AtlasMinestomAPI.initialized) {
            return;
        }

        AtlasMinestomAPI.networkClient.requestServerList();
    }

    public static void sendServerInfoUpdate() {
        if (!AtlasMinestomAPI.initialized) {
            return;
        }

        AtlasMinestomAPI.networkClient.sendServerInfoUpdate();
    }

    public static int getMaxPlayers() {
        if (!AtlasMinestomAPI.initialized) {
            return 0;
        }

        return AtlasMinestomAPI.plugin.getMaxPlayers();
    }

    public static void setMaxPlayers(int maxPlayers) {
        if (!AtlasMinestomAPI.initialized) {
            return;
        }

        AtlasMinestomAPI.plugin.setMaxPlayers(maxPlayers);
        sendServerInfoUpdate();
    }

    public static void startServer(String serverIdentifier) {
        if (!AtlasMinestomAPI.initialized || AtlasMinestomAPI.networkClient == null) {
            return;
        }

        AtlasMinestomAPI.networkClient.sendServerControl(serverIdentifier, ServerControlPacket.ControlAction.START);
    }

    public static void stopServer(String serverIdentifier) {
        if (!AtlasMinestomAPI.initialized || AtlasMinestomAPI.networkClient == null) {
            return;
        }

        AtlasMinestomAPI.networkClient.sendServerControl(serverIdentifier, ServerControlPacket.ControlAction.STOP);
    }

    public static void restartServer(String serverIdentifier) {
        if (!AtlasMinestomAPI.initialized || AtlasMinestomAPI.networkClient == null) {
            return;
        }

        AtlasMinestomAPI.networkClient.sendServerControl(serverIdentifier, ServerControlPacket.ControlAction.RESTART);
    }

    public static void setMetadata(String key, String value) {
        if (!AtlasMinestomAPI.initialized || AtlasMinestomAPI.networkClient == null) {
            return;
        }

        Map<String, String> metadata = new HashMap<>();
        metadata.put(key, value);
        AtlasMinestomAPI.sendMetadataUpdatePacket(metadata);
    }

    public static void setMetadata(Map<String, String> metadata) {
        if (!AtlasMinestomAPI.initialized || AtlasMinestomAPI.networkClient == null || metadata == null) {
            return;
        }

        AtlasMinestomAPI.sendMetadataUpdatePacket(metadata);
    }

    public static String getMetadata(String key) {
        if (!AtlasMinestomAPI.initialized || key == null) {
            return null;
        }

        String serverId = AtlasMinestomAPI.networkClient.getServerId();
        Optional<AtlasServer> server = AtlasMinestomAPI.getServer(serverId);
        return server.map(s -> s.getMetadata(key)).orElse(null);
    }

    public static Map<String, String> getAllMetadata() {
        if (!AtlasMinestomAPI.initialized) {
            return new HashMap<>();
        }

        String serverId = AtlasMinestomAPI.networkClient.getServerId();
        Optional<AtlasServer> server = AtlasMinestomAPI.getServer(serverId);
        return server.map(AtlasServer::getMetadata).orElse(new HashMap<>());
    }

    public static void removeMetadata(String key) {
        if (!AtlasMinestomAPI.initialized || AtlasMinestomAPI.networkClient == null || key == null) {
            return;
        }

        Map<String, String> metadata = new HashMap<>();
        metadata.put(key, null);
        AtlasMinestomAPI.sendMetadataUpdatePacket(metadata);
    }

    private static void sendMetadataUpdatePacket(Map<String, String> metadata) {
        String serverId = AtlasMinestomAPI.networkClient.getServerId();
        MetadataUpdatePacket packet = new MetadataUpdatePacket(serverId, metadata);
        AtlasMinestomAPI.networkClient.sendPacket(packet);
    }
}