package be.esmay.atlas.base.server;

import be.esmay.atlas.base.AtlasBase;
import be.esmay.atlas.base.lifecycle.ServerLifecycleService;
import be.esmay.atlas.common.models.AtlasServer;

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
}