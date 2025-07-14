package be.esmay.atlas.velocity.registry;

import be.esmay.atlas.common.enums.ServerStatus;
import be.esmay.atlas.common.models.ServerInfo;
import be.esmay.atlas.velocity.AtlasVelocityPlugin;
import be.esmay.atlas.velocity.cache.NetworkServerCacheManager;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class VelocityServerRegistryManager {

    private final ProxyServer proxyServer;
    private final Set<String> managedServers = ConcurrentHashMap.newKeySet();

    public VelocityServerRegistryManager(ProxyServer proxyServer) {
        this.proxyServer = proxyServer;
    }

    public void handleServerAdd(ServerInfo serverInfo) {
        if (!this.isBackendServer(serverInfo) || serverInfo.getStatus() != ServerStatus.RUNNING)
            return;

        this.addServerToVelocity(serverInfo);
    }

    public void handleServerRemove(String serverId) {
        this.removeServerFromVelocity(serverId);
    }

    public void handleServerInfoUpdate(ServerInfo serverInfo) {
        if (!this.isBackendServer(serverInfo))
            return;

        if (serverInfo.getStatus() == ServerStatus.STOPPED || serverInfo.getStatus() == ServerStatus.ERROR) {
            this.removeServerFromVelocity(serverInfo.getServerId());
            return;
        }

        if (this.managedServers.contains(serverInfo.getServerId())) return;
        this.addServerToVelocity(serverInfo);
    }

    private void addServerToVelocity(ServerInfo serverInfo) {
        String serverId = serverInfo.getServerId();
        Optional<RegisteredServer> existingServer = this.proxyServer.getServer(serverId);

        if (existingServer.isEmpty()) {
            InetSocketAddress address = new InetSocketAddress(serverInfo.getAddress(), serverInfo.getPort());
            com.velocitypowered.api.proxy.server.ServerInfo velocityServerInfo = new com.velocitypowered.api.proxy.server.ServerInfo(serverId, address);

            this.proxyServer.registerServer(velocityServerInfo);
            this.managedServers.add(serverId);

            AtlasVelocityPlugin.getInstance().getLogger().info("Added server to Velocity registry: {} at {}", serverInfo.getName(), address);
            return;
        }

        RegisteredServer registeredServer = existingServer.get();
        com.velocitypowered.api.proxy.server.ServerInfo velocityServerInfo = registeredServer.getServerInfo();

        InetSocketAddress currentAddress = velocityServerInfo.getAddress();
        InetSocketAddress newAddress = new InetSocketAddress(serverInfo.getAddress(), serverInfo.getPort());

        if (!currentAddress.equals(newAddress)) {
            this.proxyServer.unregisterServer(velocityServerInfo);
            com.velocitypowered.api.proxy.server.ServerInfo newVelocityServerInfo = new com.velocitypowered.api.proxy.server.ServerInfo(serverId, newAddress);
            this.proxyServer.registerServer(newVelocityServerInfo);
        }
    }

    private void removeServerFromVelocity(String serverId) {
        Optional<RegisteredServer> server = this.proxyServer.getServer(serverId);

        if (server.isPresent() && this.managedServers.contains(serverId)) {
            this.proxyServer.unregisterServer(server.get().getServerInfo());
            this.managedServers.remove(serverId);

            AtlasVelocityPlugin.getInstance().getLogger().info("Removed server from Velocity registry: {}", serverId);
        }
    }

    private boolean isBackendServer(ServerInfo serverInfo) {
        return serverInfo != null && !serverInfo.getGroup().equalsIgnoreCase("proxy");
    }

}