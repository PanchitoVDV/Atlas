package be.esmay.atlas.base.server;

import be.esmay.atlas.base.AtlasBase;
import be.esmay.atlas.base.api.dto.WebSocketMessage;
import be.esmay.atlas.base.lifecycle.ServerLifecycleManager;
import be.esmay.atlas.base.provider.ServiceProvider;
import be.esmay.atlas.base.scaler.Scaler;
import be.esmay.atlas.base.utils.Logger;
import be.esmay.atlas.common.enums.ServerStatus;
import be.esmay.atlas.common.enums.ServerType;
import be.esmay.atlas.common.models.ServerInfo;

import java.util.concurrent.CompletableFuture;

public final class ServerManager {

    private final ServerLifecycleManager lifecycleManager;

    public ServerManager(ServerLifecycleManager lifecycleManager) {
        this.lifecycleManager = lifecycleManager;
    }

    public CompletableFuture<Void> startServer(ServerInfo server) {
        if (server.getStatus() == ServerStatus.RUNNING) {
            Logger.info("Server is already running: " + server.getName());
            return CompletableFuture.completedFuture(null);
        }

        Scaler scaler = this.getScalerForServer(server);
        if (scaler == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("No scaler found for group: " + server.getGroup()));
        }

        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();

        return this.lifecycleManager.startServer(provider, scaler.getScalerConfig().getGroup(), server).thenRun(() -> Logger.info("Successfully started server: " + server.getName()));
    }

    public CompletableFuture<Void> stopServer(ServerInfo server) {
        if (server.getStatus() == ServerStatus.STOPPED) {
            Logger.info("Server is already stopped: " + server.getName());
            return CompletableFuture.completedFuture(null);
        }

        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();

        if (server.getType() == ServerType.DYNAMIC) {
            return this.removeServer(server);
        }

        CompletableFuture<Void> stopFuture = this.lifecycleManager.stopServer(provider, server, false);
        return stopFuture.thenRun(() -> {
            Logger.info("Successfully stopped server: " + server.getName());
            AtlasBase atlasInstance = AtlasBase.getInstance();
            atlasInstance.getApiManager().getWebSocketManager().disconnectServerConnections(
                server.getServerId(), "Server was stopped"
            );
        });

    }

    public CompletableFuture<Void> restartServer(ServerInfo server) {
        Scaler scaler = this.getScalerForServer(server);
        if (scaler == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("No scaler found for group: " + server.getGroup()));
        }

        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();

        CompletableFuture<Void> restartFuture = this.lifecycleManager.restartServer(provider, scaler.getScalerConfig().getGroup(), server);
        return restartFuture.thenRun(() -> {
            Logger.info("Successfully restarted server: " + server.getName());
            
            WebSocketMessage restartMessage = WebSocketMessage.event("restart-completed", server.getServerId());
            AtlasBase atlasInstance = AtlasBase.getInstance();
            atlasInstance.getApiManager().getWebSocketManager().sendToServerConnections(server.getServerId(), restartMessage);
            
            atlasInstance.getApiManager().getWebSocketManager().restartLogStreamingForServer(server.getServerId());
        });
    }

    public CompletableFuture<Void> removeServer(ServerInfo server) {
        Scaler scaler = this.getScalerForServer(server);
        if (scaler == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("No scaler found for group: " + server.getGroup()));
        }

        if (server.isManuallyScaled()) {
            CompletableFuture<Void> removeFuture = scaler.removeAsync(server);
            return removeFuture.thenRun(() -> {
                Logger.info("Successfully removed manual server: " + server.getName());
                AtlasBase atlasInstance = AtlasBase.getInstance();
                atlasInstance.getApiManager().getWebSocketManager().disconnectServerConnections(
                    server.getServerId(), "Server was removed"
                );
            });
        } else {
            CompletableFuture<Void> removeFuture = scaler.removeAsync(server);
            return removeFuture.thenRun(() -> {
                Logger.info("Successfully removed server: " + server.getName());
                AtlasBase atlasInstance = AtlasBase.getInstance();
                atlasInstance.getApiManager().getWebSocketManager().disconnectServerConnections(
                    server.getServerId(), "Server was removed"
                );
            });
        }
    }

    private Scaler getScalerForServer(ServerInfo server) {
        return AtlasBase.getInstance().getScalerManager().getScaler(server.getGroup());
    }
}