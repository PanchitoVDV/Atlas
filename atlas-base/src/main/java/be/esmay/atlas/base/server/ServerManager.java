package be.esmay.atlas.base.server;

import be.esmay.atlas.base.AtlasBase;
import be.esmay.atlas.base.lifecycle.ServerLifecycleService;
import be.esmay.atlas.base.network.connection.ConnectionManager;
import be.esmay.atlas.base.provider.ServiceProvider;
import be.esmay.atlas.base.utils.Logger;
import be.esmay.atlas.common.models.AtlasServer;
import be.esmay.atlas.common.network.packet.packets.ServerCommandPacket;

import java.util.concurrent.CompletableFuture;

public final class ServerManager {

    private final ServerLifecycleService lifecycleService;

    public ServerManager(AtlasBase atlasBase) {
        this.lifecycleService = new ServerLifecycleService(atlasBase);
    }

    public CompletableFuture<Void> startServer(AtlasServer server) {
        return this.lifecycleService.startServer(server);
    }

    public CompletableFuture<Void> stopServer(AtlasServer server) {
        return this.lifecycleService.stopServer(server);
    }

    public CompletableFuture<Void> restartServer(AtlasServer server) {
        return this.lifecycleService.restartServer(server);
    }

    public CompletableFuture<Void> removeServer(AtlasServer server) {
        return this.lifecycleService.removeServer(server);
    }

    public CompletableFuture<Void> sendCommand(String serverIdentifier, String command) {
        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();

        return provider.getServer(serverIdentifier).thenCompose(serverOpt -> serverOpt.map(atlasServer -> this.executeCommand(atlasServer, command)).orElseGet(() -> provider.getAllServers().thenCompose(servers -> {
            AtlasServer server = servers.stream()
                    .filter(s -> s.getName().equals(serverIdentifier))
                    .findFirst()
                    .orElse(null);

            if (server == null) {
                CompletableFuture<Void> failedFuture = new CompletableFuture<>();
                failedFuture.completeExceptionally(new IllegalArgumentException("Server not found: " + serverIdentifier));
                return failedFuture;
            }

            return this.executeCommand(server, command);
        })));
    }

    private CompletableFuture<Void> executeCommand(AtlasServer server, String command) {
        if (server.getServerInfo() == null || !server.getServerInfo().getStatus().toString().equals("RUNNING")) {
            CompletableFuture<Void> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(new IllegalStateException(
                "Server " + server.getName() + " is not running (Status: " + 
                (server.getServerInfo() != null ? server.getServerInfo().getStatus() : "UNKNOWN") + ")"
            ));
            return failedFuture;
        }

        ServerCommandPacket packet = new ServerCommandPacket(server.getServerId(), command);
        ConnectionManager connectionManager = AtlasBase.getInstance().getNettyServer().getConnectionManager();

        connectionManager.sendToServer(server.getServerId(), packet);
        Logger.info("Command sent to server " + server.getName() + ": " + command);

        return CompletableFuture.completedFuture(null);
    }
}