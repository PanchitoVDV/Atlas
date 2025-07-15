package be.esmay.atlas.base.lifecycle;

import be.esmay.atlas.base.AtlasBase;
import be.esmay.atlas.base.api.dto.WebSocketMessage;
import be.esmay.atlas.base.config.impl.ScalerConfig;
import be.esmay.atlas.base.directory.DirectoryManager;
import be.esmay.atlas.base.provider.DeletionOptions;
import be.esmay.atlas.base.provider.DeletionReason;
import be.esmay.atlas.base.provider.ServiceProvider;
import be.esmay.atlas.base.provider.StartOptions;
import be.esmay.atlas.base.template.TemplateManager;
import be.esmay.atlas.base.utils.Logger;
import be.esmay.atlas.common.enums.ServerType;
import be.esmay.atlas.common.models.AtlasServer;

import java.util.concurrent.CompletableFuture;

public final class ServerLifecycleManager {

    private final DirectoryManager directoryManager;

    public ServerLifecycleManager() {
        this.directoryManager = new DirectoryManager();
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
        }

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

    public boolean isValidServerId(String serverId, ServerType serverType) {
        if (serverType == ServerType.STATIC) {
            return this.directoryManager.isStaticServerIdValid(serverId);
        }

        return serverId != null && !serverId.trim().isEmpty();
    }
}