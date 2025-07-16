package be.esmay.atlas.velocity.modules.scaling.registry;

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

    public void handleServerRemove(String serverName) {
        this.removeServerFromVelocity(serverName);
    }

    public void handleAtlasServerUpdate(AtlasServer atlasServer) {
        if (this.isProxyServer(atlasServer))
            return;

        if (atlasServer.getServerInfo() == null || atlasServer.getServerInfo().getStatus() == ServerStatus.STOPPED || atlasServer.getServerInfo().getStatus() == ServerStatus.ERROR) {
            this.removeServerFromVelocity(atlasServer.getName());
            return;
        }

        if (this.managedServers.contains(atlasServer.getName())) return;
        this.addServerToVelocity(atlasServer);
    }

    private void addServerToVelocity(AtlasServer atlasServer) {
        String serverName = atlasServer.getName();
        Optional<RegisteredServer> existingServer = this.proxyServer.getServer(serverName);

        if (existingServer.isEmpty()) {
            InetSocketAddress address = new InetSocketAddress(atlasServer.getAddress(), atlasServer.getPort());
            ServerInfo velocityServerInfo = new ServerInfo(serverName, address);

            this.proxyServer.registerServer(velocityServerInfo);
            this.managedServers.add(serverName);

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
        ServerInfo newVelocityServerInfo = new ServerInfo(serverName, newAddress);
        this.proxyServer.registerServer(newVelocityServerInfo);
    }

    private void removeServerFromVelocity(String serverName) {
        Optional<RegisteredServer> server = this.proxyServer.getServer(serverName);

        if (server.isEmpty() || !this.managedServers.contains(serverName))
            return;

        this.proxyServer.unregisterServer(server.get().getServerInfo());
        this.managedServers.remove(serverName);

        AtlasVelocityPlugin.getInstance().getLogger().info("Unregistered server in Velocity: {}", serverName);
    }

    private boolean isProxyServer(AtlasServer atlasServer) {
        return atlasServer == null || atlasServer.getGroup().equalsIgnoreCase("proxy");
    }

}