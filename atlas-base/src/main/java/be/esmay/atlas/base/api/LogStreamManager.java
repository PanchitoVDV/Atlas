package be.esmay.atlas.base.api;

import be.esmay.atlas.base.AtlasBase;
import be.esmay.atlas.base.api.dto.WebSocketMessage;
import be.esmay.atlas.base.provider.ServiceProvider;
import be.esmay.atlas.base.utils.Logger;
import io.vertx.core.Future;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.Map;

public final class LogStreamManager {

    private final WebSocketManager webSocketManager;
    private final Map<String, String> activeStreams;

    public LogStreamManager(WebSocketManager webSocketManager) {
        this.webSocketManager = webSocketManager;
        this.activeStreams = new ConcurrentHashMap<>();
    }

    public Future<Void> initialize() {
        return Future.succeededFuture();
    }

    public Future<Void> startServerLogStream(String serverId) {
        if (this.activeStreams.containsKey(serverId)) {
            return Future.succeededFuture();
        }

        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();
        
        provider.streamServerLogs(serverId, logLine -> {
            WebSocketMessage message = WebSocketMessage.log(serverId, logLine);
            this.webSocketManager.sendToServerConnections(serverId, message);
        })
        .thenAccept(subscriptionId -> {
            this.activeStreams.put(serverId, subscriptionId);
            Logger.debug("Started log streaming for server: " + serverId);
        })
        .exceptionally(throwable -> {
            Logger.error("Failed to start log streaming for server: " + serverId, throwable);
            return null;
        });

        return Future.succeededFuture();
    }

    public Future<Void> stopServerLogStream(String serverId) {
        String subscriptionId = this.activeStreams.remove(serverId);
        if (subscriptionId == null) {
            Logger.debug("No active log stream found for server: " + serverId);
            return Future.succeededFuture();
        }

        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();
        
        provider.stopLogStream(subscriptionId)
            .thenAccept(success -> {
                if (success) {
                    Logger.debug("Stopped log streaming for server: " + serverId);
                } else {
                    Logger.debug("Log stream was already stopped or not found for server: " + serverId);
                }
            })
            .exceptionally(throwable -> {
                Logger.debug("Error stopping log streaming for server: " + serverId + " - " + throwable.getMessage());
                return null;
            });

        return Future.succeededFuture();
    }

    private void startGlobalLogStreaming() {
        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();
        
        provider.getAllServers()
            .thenAccept(servers -> {
                for (be.esmay.atlas.common.models.AtlasServer server : servers) {
                    this.startServerLogStream(server.getServerId());
                }
            })
            .exceptionally(throwable -> {
                Logger.error("Failed to start global log streaming", throwable);
                return null;
            });
    }

    public void broadcastServerEvent(String serverId, String event) {
        WebSocketMessage message = WebSocketMessage.event(event, serverId);
        this.webSocketManager.sendToServerConnections(serverId, message);
    }

    public Future<Void> shutdown() {
        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();
        
        CompletableFuture<Void> allStops = CompletableFuture.allOf(
            this.activeStreams.values().stream()
                .map(provider::stopLogStream)
                .toArray(CompletableFuture[]::new)
        );

        allStops.thenRun(() -> {
            this.activeStreams.clear();
            Logger.debug("All log streams stopped");
        });

        return Future.succeededFuture();
    }
}