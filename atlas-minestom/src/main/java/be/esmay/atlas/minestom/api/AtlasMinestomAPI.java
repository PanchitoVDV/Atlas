package be.esmay.atlas.minestom.api;

import be.esmay.atlas.common.enums.ServerStatus;
import be.esmay.atlas.common.models.AtlasServer;
import be.esmay.atlas.common.models.ServerInfo;
import be.esmay.atlas.minestom.AtlasMinestomPlugin;
import be.esmay.atlas.minestom.cache.NetworkServerCacheManager;
import be.esmay.atlas.minestom.network.AtlasNetworkClient;
import be.esmay.atlas.minestom.server.MinestomServerInfoManager;
import lombok.experimental.UtilityClass;

import java.util.Collection;
import java.util.List;
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
}