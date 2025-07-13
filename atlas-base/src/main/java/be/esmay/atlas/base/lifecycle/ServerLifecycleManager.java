package be.esmay.atlas.base.lifecycle;

import be.esmay.atlas.base.config.impl.ScalerConfig;
import be.esmay.atlas.base.directory.DirectoryManager;
import be.esmay.atlas.base.provider.ServiceProvider;
import be.esmay.atlas.base.template.TemplateManager;
import be.esmay.atlas.base.utils.Logger;
import be.esmay.atlas.common.enums.ServerStatus;
import be.esmay.atlas.common.enums.ServerType;
import be.esmay.atlas.common.models.ServerInfo;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class ServerLifecycleManager {

    private final DirectoryManager directoryManager;
    private final TemplateManager templateManager;

    public ServerLifecycleManager() {
        this.directoryManager = new DirectoryManager();
        this.templateManager = new TemplateManager();
    }

    public CompletableFuture<ServerInfo> createServer(ServiceProvider serviceProvider, ScalerConfig.Group groupConfig, String serverId, String serverName, ServerType serverType, boolean isManuallyScaled) {
        return CompletableFuture.supplyAsync(() -> {
            String uniqueUuid = UUID.randomUUID().toString();

            ServerInfo server = ServerInfo.builder()
                    .serverId(uniqueUuid)
                    .name(serverName)
                    .group(groupConfig.getName())
                    .type(serverType)
                    .status(ServerStatus.STARTING)
                    .onlinePlayers(0)
                    .maxPlayers(20)
                    .createdAt(System.currentTimeMillis())
                    .lastHeartbeat(System.currentTimeMillis())
                    .isManuallyScaled(isManuallyScaled)
                    .build();

            String workingDirectory = this.prepareServerDirectory(server, groupConfig);
            server.setWorkingDirectory(workingDirectory);

            return server;
        }).thenCompose(server -> serviceProvider.createServer(groupConfig, server));
    }

    public CompletableFuture<Void> stopServer(ServiceProvider serviceProvider, ServerInfo server, boolean isRestart) {
        boolean shouldDelete = !isRestart && server.getType() == ServerType.DYNAMIC;

        if (shouldDelete)
            return this.deleteServerCompletely(serviceProvider, server);

        return serviceProvider.stopServer(server).thenRun(() -> Logger.debug("Stopped server (preserved): " + server.getName()));
    }

    public CompletableFuture<Void> deleteServerCompletely(ServiceProvider serviceProvider, ServerInfo server) {
        boolean shouldCleanDirectory = server.getType() == ServerType.DYNAMIC;

        return serviceProvider.stopServer(server)
                .thenCompose(v -> serviceProvider.deleteServer(server.getServerId()))
                .thenAccept(deleted -> {
                    if (shouldCleanDirectory) {
                        try {
                            this.directoryManager.cleanupServerDirectory(server);
                            Logger.debug("Deleted and cleaned server: " + server.getName());
                        } catch (Exception e) {
                            Logger.warn("Server deleted but directory cleanup failed for " + server.getName() + ": " + e.getMessage());
                        }
                    } else {
                        Logger.debug("Deleted server (preserved directory): " + server.getName());
                    }
                });
    }

    public CompletableFuture<Void> restartServer(ServiceProvider serviceProvider, ScalerConfig.Group groupConfig, ServerInfo server) {
        Logger.info("Restarting server: " + server.getName());

        return this.stopServer(serviceProvider, server, true)
                .thenCompose(v -> {
                    Logger.debug("Server stopped, starting again: " + server.getName());
                    return serviceProvider.startServer(server);
                })
                .thenRun(() -> Logger.info("Server restarted: " + server.getName()));
    }

    public CompletableFuture<Void> startServer(ServiceProvider serviceProvider, ScalerConfig.Group groupConfig, ServerInfo server) {
        if (server.getWorkingDirectory() == null) {
            String workingDirectory = this.prepareServerDirectory(server, groupConfig);
            server.setWorkingDirectory(workingDirectory);
        }

        return serviceProvider.startServer(server);
    }

    private String prepareServerDirectory(ServerInfo server, ScalerConfig.Group groupConfig) {
        boolean directoryExisted = this.directoryManager.directoryExists(server);
        boolean shouldApplyTemplates = !directoryExisted || server.getType() == ServerType.DYNAMIC;

        String workingDirectory = this.directoryManager.createServerDirectory(server);

        if (shouldApplyTemplates && groupConfig.getTemplates() != null && !groupConfig.getTemplates().isEmpty()) {
            this.templateManager.applyTemplates(workingDirectory, groupConfig.getTemplates());
        }

        return workingDirectory;
    }

    public boolean isValidServerId(String serverId, ServerType serverType) {
        if (serverType == ServerType.STATIC) {
            return this.directoryManager.isStaticServerIdValid(serverId);
        }
        return serverId != null && !serverId.trim().isEmpty();
    }
}