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
    private final AtlasBase atlasBase;

    public ServerManager(ServerLifecycleManager lifecycleManager, AtlasBase atlasBase) {
        this.lifecycleManager = lifecycleManager;
        this.atlasBase = atlasBase;
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

        ServiceProvider provider = this.atlasBase.getProviderManager().getProvider();

        CompletableFuture<Void> startFuture = this.lifecycleManager.startServer(provider, scaler.getScalerConfig().getGroup(), server);
        return startFuture.thenRun(() -> {
            Logger.info("Successfully started server: " + server.getName());

            if (this.atlasBase.getNettyServer() != null) {
                this.atlasBase.getNettyServer().broadcastServerUpdate(server);
            }
        });
    }

    public CompletableFuture<Void> stopServer(ServerInfo server) {
        if (server.getStatus() == ServerStatus.STOPPED) {
            Logger.info("Server is already stopped: " + server.getName());
            return CompletableFuture.completedFuture(null);
        }

        ServiceProvider provider = this.atlasBase.getProviderManager().getProvider();

        if (server.getType() == ServerType.DYNAMIC) {
            return this.removeServer(server);
        }

        CompletableFuture<Void> stopFuture = this.lifecycleManager.stopServer(provider, server, false);
        return stopFuture.thenRun(() -> {
            Logger.info("Successfully stopped server: " + server.getName());
            this.atlasBase.getApiManager().getWebSocketManager().disconnectServerConnections(server.getServerId(), "Server was stopped");

            if (this.atlasBase.getNettyServer() != null) {
                this.atlasBase.getNettyServer().broadcastServerUpdate(server);
            }
        });
    }

    public CompletableFuture<Void> restartServer(ServerInfo server) {
        Scaler scaler = this.getScalerForServer(server);
        if (scaler == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("No scaler found for group: " + server.getGroup()));
        }

        ServiceProvider provider = this.atlasBase.getProviderManager().getProvider();

        CompletableFuture<Void> restartFuture = this.lifecycleManager.restartServer(provider, scaler.getScalerConfig().getGroup(), server);
        return restartFuture.thenRun(() -> {
            Logger.info("Successfully restarted server: " + server.getName());

            WebSocketMessage restartMessage = WebSocketMessage.event("restart-completed", server.getServerId());
            this.atlasBase.getApiManager().getWebSocketManager().sendToServerConnections(server.getServerId(), restartMessage);
            this.atlasBase.getApiManager().getWebSocketManager().restartLogStreamingForServer(server.getServerId());

            if (this.atlasBase.getNettyServer() != null) {
                this.atlasBase.getNettyServer().broadcastServerUpdate(server);
            }

        });
    }

    public CompletableFuture<Void> removeServer(ServerInfo server) {
        Scaler scaler = this.getScalerForServer(server);
        if (scaler == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("No scaler found for group: " + server.getGroup()));
        }

        CompletableFuture<Void> removeFuture = scaler.removeAsync(server);

        if (server.isManuallyScaled()) {
            return removeFuture.thenRun(() -> {
                Logger.info("Successfully removed manual server: " + server.getName());
                this.atlasBase.getApiManager().getWebSocketManager().disconnectServerConnections(server.getServerId(), "Server was removed");

                if (this.atlasBase.getNettyServer() != null) {
                    this.atlasBase.getNettyServer().broadcastServerRemove(server.getServerId(), "Server was removed");
                }

            });
        } else {
            return removeFuture.thenRun(() -> {
                Logger.info("Successfully removed server: " + server.getName());
                this.atlasBase.getApiManager().getWebSocketManager().disconnectServerConnections(server.getServerId(), "Server was removed");

                if (this.atlasBase.getNettyServer() != null) {
                    this.atlasBase.getNettyServer().broadcastServerRemove(server.getServerId(), "Server was removed");
                }
            });
        }
    }

    private Scaler getScalerForServer(ServerInfo server) {
        return AtlasBase.getInstance().getScalerManager().getScaler(server.getGroup());
    }
}