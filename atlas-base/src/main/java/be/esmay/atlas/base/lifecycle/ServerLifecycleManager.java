package be.esmay.atlas.base.lifecycle;

import be.esmay.atlas.base.AtlasBase;
import be.esmay.atlas.base.api.dto.WebSocketMessage;
import be.esmay.atlas.base.config.impl.ScalerConfig;
import be.esmay.atlas.base.directory.DirectoryManager;
import be.esmay.atlas.base.provider.ServiceProvider;
import be.esmay.atlas.base.template.TemplateManager;
import be.esmay.atlas.base.utils.Logger;
import be.esmay.atlas.common.enums.ServerStatus;
import be.esmay.atlas.common.enums.ServerType;
import be.esmay.atlas.common.models.AtlasServer;
import be.esmay.atlas.common.models.ServerInfo;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class ServerLifecycleManager {

    private final DirectoryManager directoryManager;
    private final TemplateManager templateManager;

    private final AtlasBase atlasBase;

    public ServerLifecycleManager(AtlasBase atlasBase) {
        this.atlasBase = atlasBase;

        this.directoryManager = new DirectoryManager();
        this.templateManager = new TemplateManager();
    }

    public CompletableFuture<AtlasServer> createServer(ServiceProvider serviceProvider, ScalerConfig.Group groupConfig, String serverId, String serverName, ServerType serverType, boolean isManuallyScaled) {
        CompletableFuture<AtlasServer> supplyFuture = CompletableFuture.supplyAsync(() -> {
            String uniqueUuid = UUID.randomUUID().toString();

            ServerInfo serverInfo = ServerInfo.builder()
                    .status(ServerStatus.STARTING)
                    .onlinePlayers(0)
                    .maxPlayers(20)
                    .build();

            AtlasServer server = AtlasServer.builder()
                    .serverId(uniqueUuid)
                    .name(serverName)
                    .group(groupConfig.getName())
                    .type(serverType)
                    .createdAt(System.currentTimeMillis())
                    .isManuallyScaled(isManuallyScaled)
                    .serverInfo(serverInfo)
                    .lastHeartbeat(System.currentTimeMillis())
                    .build();

            String workingDirectory = this.prepareServerDirectory(server, groupConfig);
            server.setWorkingDirectory(workingDirectory);

            return server;
        });
        return supplyFuture.thenCompose(server -> serviceProvider.createServer(groupConfig, server));
    }

    public CompletableFuture<Void> stopServer(ServiceProvider serviceProvider, AtlasServer server, boolean isRestart) {
        boolean shouldDelete = !isRestart && server.getType() == ServerType.DYNAMIC;

        if (shouldDelete)
            return this.deleteServerCompletely(serviceProvider, server);

        CompletableFuture<Void> stopFuture = serviceProvider.stopServer(server);
        return stopFuture.thenRun(() -> Logger.debug("Stopped server (preserved): " + server.getName()));
    }

    public CompletableFuture<Void> deleteServerCompletely(ServiceProvider serviceProvider, AtlasServer server) {
        boolean cleanupDynamicOnShutdown = this.atlasBase.getConfigManager().getAtlasConfig().getAtlas().getTemplates().isCleanupDynamicOnShutdown();
        boolean shouldCleanDirectory = server.getType() == ServerType.DYNAMIC && cleanupDynamicOnShutdown;

        CompletableFuture<Void> stopFuture = serviceProvider.stopServer(server);
        CompletableFuture<Boolean> deleteFuture = stopFuture.thenCompose(v -> serviceProvider.deleteServer(server.getServerId()));
        return deleteFuture.thenAccept(deleted -> {
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

    public CompletableFuture<Void> restartServer(ServiceProvider serviceProvider, ScalerConfig.Group groupConfig, AtlasServer server) {
        Logger.info("Restarting server: " + server.getName());

        WebSocketMessage restartStartMessage = WebSocketMessage.event("restart-started", server.getServerId());
        this.atlasBase.getApiManager().getWebSocketManager().sendToServerConnections(server.getServerId(), restartStartMessage);

        CompletableFuture<Void> stopFuture = this.stopServer(serviceProvider, server, true);
        CompletableFuture<Void> startFuture = stopFuture.thenCompose(v -> {
            Logger.debug("Server stopped, starting again: " + server.getName());

            this.atlasBase.getApiManager().getWebSocketManager().stopLogStreamingForRestart(server.getServerId());

            return serviceProvider.startServer(server);
        });

        return startFuture.thenRun(() -> Logger.info("Server restarted: " + server.getName()));
    }

    public CompletableFuture<Void> startServer(ServiceProvider serviceProvider, ScalerConfig.Group groupConfig, AtlasServer server) {
        if (server.getWorkingDirectory() == null) {
            String workingDirectory = this.prepareServerDirectory(server, groupConfig);
            server.setWorkingDirectory(workingDirectory);
        }

        return serviceProvider.startServer(server);
    }

    private String prepareServerDirectory(AtlasServer server, ScalerConfig.Group groupConfig) {
        boolean directoryExisted = this.directoryManager.directoryExists(server);
        boolean shouldApplyTemplates = !directoryExisted || server.getType() == ServerType.DYNAMIC;

        String workingDirectory = this.directoryManager.createServerDirectory(server);

        boolean downloadOnStartup = this.atlasBase.getConfigManager().getAtlasConfig().getAtlas().getTemplates().isDownloadOnStartup();

        if (downloadOnStartup && shouldApplyTemplates && groupConfig.getTemplates() != null && !groupConfig.getTemplates().isEmpty()) {
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