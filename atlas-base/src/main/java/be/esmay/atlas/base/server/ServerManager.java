package be.esmay.atlas.base.server;

import be.esmay.atlas.base.AtlasBase;
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

        return this.lifecycleManager.stopServer(provider, server, false).thenRun(() -> Logger.info("Successfully stopped server: " + server.getName()));

    }

    public CompletableFuture<Void> restartServer(ServerInfo server) {
        Scaler scaler = this.getScalerForServer(server);
        if (scaler == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("No scaler found for group: " + server.getGroup()));
        }

        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();

        return this.lifecycleManager.restartServer(provider, scaler.getScalerConfig().getGroup(), server).thenRun(() -> Logger.info("Successfully restarted server: " + server.getName()));
    }

    public CompletableFuture<Void> removeServer(ServerInfo server) {
        Scaler scaler = this.getScalerForServer(server);
        if (scaler == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("No scaler found for group: " + server.getGroup()));
        }

        return CompletableFuture.runAsync(() -> {
            if (server.isManuallyScaled()) {
                scaler.removeManualServer(server.getServerId());
                Logger.debug("Manual server removal initiated: " + server.getName());
            } else {
                scaler.remove(server);
                Logger.debug("Server removal initiated: " + server.getName());
            }
        });
    }

    public CompletableFuture<Void> forceDeleteServer(ServerInfo server) {
        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();
        return this.lifecycleManager.deleteServerCompletely(provider, server);
    }

    private Scaler getScalerForServer(ServerInfo server) {
        return AtlasBase.getInstance().getScalerManager().getScaler(server.getGroup());
    }
}