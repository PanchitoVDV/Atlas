package be.esmay.atlas.velocity.registry;

import be.esmay.atlas.common.enums.ServerStatus;
import be.esmay.atlas.common.models.AtlasServer;
import be.esmay.atlas.velocity.AtlasVelocityPlugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import lombok.RequiredArgsConstructor;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
public final class VelocityServerRegistryManager {

    private final ProxyServer proxyServer;
    private final Set<String> managedServers = ConcurrentHashMap.newKeySet();

    public void handleAtlasServerAdd(AtlasServer atlasServer) {
        if (this.isProxyServer(atlasServer) || atlasServer.getServerInfo() == null || atlasServer.getServerInfo().getStatus() != ServerStatus.RUNNING)
            return;

        this.addServerToVelocity(atlasServer);
    }

    public void handleServerRemove(String serverId) {
        this.removeServerFromVelocity(serverId);
    }

    public void handleAtlasServerUpdate(AtlasServer atlasServer) {
        if (this.isProxyServer(atlasServer))
            return;

        if (atlasServer.getServerInfo() == null || atlasServer.getServerInfo().getStatus() == ServerStatus.STOPPED || atlasServer.getServerInfo().getStatus() == ServerStatus.ERROR) {
            this.removeServerFromVelocity(atlasServer.getServerId());
            return;
        }

        if (this.managedServers.contains(atlasServer.getServerId())) return;
        this.addServerToVelocity(atlasServer);
    }

    private void addServerToVelocity(AtlasServer atlasServer) {
        String serverId = atlasServer.getServerId();
        Optional<RegisteredServer> existingServer = this.proxyServer.getServer(serverId);

        if (existingServer.isEmpty()) {
            InetSocketAddress address = new InetSocketAddress(atlasServer.getAddress(), atlasServer.getPort());
            ServerInfo velocityServerInfo = new ServerInfo(serverId, address);

            this.proxyServer.registerServer(velocityServerInfo);
            this.managedServers.add(serverId);

            AtlasVelocityPlugin.getInstance().getLogger().info("Registered server in Velocity: {} at {}", atlasServer.getName(), address);
            return;
        }

        RegisteredServer registeredServer = existingServer.get();
        ServerInfo velocityServerInfo = registeredServer.getServerInfo();

        InetSocketAddress currentAddress = velocityServerInfo.getAddress();
        InetSocketAddress newAddress = new InetSocketAddress(atlasServer.getAddress(), atlasServer.getPort());

        if (currentAddress.equals(newAddress))
            return;

        this.proxyServer.unregisterServer(velocityServerInfo);
        ServerInfo newVelocityServerInfo = new ServerInfo(serverId, newAddress);
        this.proxyServer.registerServer(newVelocityServerInfo);
    }

    private void removeServerFromVelocity(String serverId) {
        Optional<RegisteredServer> server = this.proxyServer.getServer(serverId);

        if (server.isEmpty() || !this.managedServers.contains(serverId))
            return;

        this.proxyServer.unregisterServer(server.get().getServerInfo());
        this.managedServers.remove(serverId);

        AtlasVelocityPlugin.getInstance().getLogger().info("Unregistered server in Velocity: {}", serverId);
    }

    private boolean isProxyServer(AtlasServer atlasServer) {
        return atlasServer == null || atlasServer.getGroup().equalsIgnoreCase("proxy");
    }

}