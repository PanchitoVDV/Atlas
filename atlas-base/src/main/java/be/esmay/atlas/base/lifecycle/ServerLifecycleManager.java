package be.esmay.atlas.base.lifecycle;

import be.esmay.atlas.base.AtlasBase;
import be.esmay.atlas.base.api.dto.WebSocketMessage;
import be.esmay.atlas.base.config.impl.ScalerConfig;
import be.esmay.atlas.base.directory.DirectoryManager;
import be.esmay.atlas.base.provider.DeletionOptions;
import be.esmay.atlas.base.provider.DeletionReason;
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
        if (isRestart) {
            DeletionOptions restartOptions = DeletionOptions.builder()
                    .reason(DeletionReason.USER_COMMAND)
                    .gracefulStop(true)
                    .cleanupDirectory(false)
                    .removeFromTracking(false)
                    .build();
            return serviceProvider.deleteServerCompletely(server, restartOptions)
                    .thenAccept(success -> {
                        if (success) {
                            Logger.debug("Successfully stopped server for restart: {}", server.getName());
                            server.setShutdown(false);
                        } else {
                            Logger.warn("Unified deletion failed during restart for server: {}", server.getName());
                        }
                    });
        } else {
            DeletionOptions stopOptions = server.getType() == ServerType.DYNAMIC
                    ? DeletionOptions.userCommand()
                    : DeletionOptions.builder()
                    .reason(DeletionReason.USER_COMMAND)
                    .gracefulStop(true)
                    .cleanupDirectory(false)
                    .removeFromTracking(false)
                    .build();

            return serviceProvider.deleteServerCompletely(server, stopOptions)
                    .thenAccept(success -> {
                        if (success) {
                            Logger.debug("Successfully stopped server: {}", server.getName());
                            if (server.getType() == ServerType.STATIC) {
                                server.setShutdown(false);
                            }
                        } else {
                            Logger.warn("Unified deletion failed for server: {}", server.getName());
                        }
                    });
        }
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