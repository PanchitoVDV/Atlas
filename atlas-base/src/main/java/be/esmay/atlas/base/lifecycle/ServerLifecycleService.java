package be.esmay.atlas.base.lifecycle;

import be.esmay.atlas.base.AtlasBase;
import be.esmay.atlas.base.api.WebSocketManager;
import be.esmay.atlas.base.api.dto.WebSocketMessage;
import be.esmay.atlas.base.provider.DeletionOptions;
import be.esmay.atlas.base.provider.DeletionReason;
import be.esmay.atlas.base.provider.ServiceProvider;
import be.esmay.atlas.base.provider.StartOptions;
import be.esmay.atlas.base.scaler.Scaler;
import be.esmay.atlas.base.utils.Logger;
import be.esmay.atlas.common.enums.ServerStatus;
import be.esmay.atlas.common.enums.ServerType;
import be.esmay.atlas.common.models.AtlasServer;
import be.esmay.atlas.common.models.ServerInfo;

import java.util.HashSet;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class ServerLifecycleService {

    private final AtlasBase atlasBase;

    private static final Map<String, String> ACTIVE_WATCHES = new ConcurrentHashMap<>();

    public ServerLifecycleService(AtlasBase atlasBase) {
        this.atlasBase = atlasBase;
    }

    /**
     * Starts a server using the appropriate service provider.
     *
     * @param server The server to start
     * @return CompletableFuture that completes when the server is started
     */
    public CompletableFuture<Void> startServer(AtlasServer server) {
        if (server.getServerInfo() != null) {
            ServerInfo startingInfo = ServerInfo.builder()
                .status(ServerStatus.STARTING)
                .onlinePlayers(0)
                .maxPlayers(server.getServerInfo().getMaxPlayers())
                .onlinePlayerNames(new HashSet<>())
                .build();
            server.updateServerInfo(startingInfo);
        }

        server.setLastHeartbeat(System.currentTimeMillis());
        server.getServerInfo().setStatus(ServerStatus.STARTING);
        server.updateServerInfo(server.getServerInfo());

        Scaler scaler = this.atlasBase.getScalerManager().getScaler(server.getGroup());
        if (scaler != null) {
            scaler.clearManualStopFlag(server.getServerId());
        }

        ServiceProvider provider = this.atlasBase.getProviderManager().getProvider();

        return provider.startServerCompletely(server, StartOptions.userCommand())
                .thenAccept(startedServer -> {
                    Logger.debug("Successfully started server through unified start: " + startedServer.getName());
                    this.notifyServerUpdate(startedServer);
                })
                .exceptionally(throwable -> {
                    Logger.error("Failed to start server: " + server.getName(), throwable);
                    return null;
                });
    }

    /**
     * Stops a server gracefully.
     *
     * @param server The server to stop
     * @return CompletableFuture that completes when the server is stopped
     */
    public CompletableFuture<Void> stopServer(AtlasServer server) {
        return this.stopServer(server, DeletionOptions.userCommand());
    }

    /**
     * Stops a server with specific deletion options.
     *
     * @param server The server to stop
     * @param options The deletion options to use
     * @return CompletableFuture that completes when the server is stopped
     */
    public CompletableFuture<Void> stopServer(AtlasServer server, DeletionOptions options) {
        if (server.getServerInfo() != null && server.getServerInfo().getStatus() == ServerStatus.STOPPED) {
            Logger.info("Server is already stopped: " + server.getName());
            return CompletableFuture.completedFuture(null);
        }

        if (server.getType() == ServerType.DYNAMIC) {
            Logger.debug("Detected DYNAMIC server {}, using unified deletion", server.getName());
            return this.removeServer(server, options);
        }

        Logger.debug("Detected STATIC server {}, using stop-only mode (container will remain)", server.getName());

        ServiceProvider provider = this.atlasBase.getProviderManager().getProvider();

        if (server.getServerInfo() != null) {
            ServerInfo stoppingInfo = ServerInfo.builder()
                .status(ServerStatus.STOPPING)
                .onlinePlayers(server.getServerInfo().getOnlinePlayers())
                .maxPlayers(server.getServerInfo().getMaxPlayers())
                .onlinePlayerNames(server.getServerInfo().getOnlinePlayerNames())
                .build();
            server.updateServerInfo(stoppingInfo);
        }

        Scaler scaler = this.atlasBase.getScalerManager().getScaler(server.getGroup());
        if (scaler != null) {
            scaler.markServerAsManuallyStopped(server.getServerId());
        }

        return provider.stopServer(server)
                .thenAccept(v -> {
                    if (this.atlasBase.getNettyServer() != null) {
                        this.atlasBase.getNettyServer().broadcastServerRemove(server.getServerId(), "Static server manually stopped");
                    }
                    
                    server.setShutdown(false);
                })
                .exceptionally(throwable -> {
                    Logger.error("Failed to stop STATIC server: {}", server.getName(), throwable);
                    server.setShutdown(false);
                    return null;
                });
    }

    /**
     * Removes a server completely (stops and deletes).
     *
     * @param server The server to remove
     * @return CompletableFuture that completes when the server is removed
     */
    public CompletableFuture<Void> removeServer(AtlasServer server) {
        return this.removeServer(server, DeletionOptions.userCommand());
    }

    /**
     * Remove server with specific deletion options
     */
    public CompletableFuture<Void> removeServer(AtlasServer server, DeletionOptions options) {
        boolean wasAlreadyShutdown = server.isShutdown();
        if (wasAlreadyShutdown) {
            Logger.warn("Server {} is already marked as shutdown - using unified deletion to prevent zombie state", server.getName());
        }

        Scaler scaler = this.getScalerForServer(server);
        if (scaler == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("No scaler found for group: " + server.getGroup()));
        }

        ServiceProvider provider = this.atlasBase.getProviderManager().getProvider();

        Logger.debug("Starting deletion for server: {} with options: {}", server.getName(), options.getReason());

        if (server.getServerInfo() != null) {
            ServerInfo stoppingInfo = ServerInfo.builder()
                .status(ServerStatus.STOPPING)
                .onlinePlayers(server.getServerInfo().getOnlinePlayers())
                .maxPlayers(server.getServerInfo().getMaxPlayers())
                .onlinePlayerNames(server.getServerInfo().getOnlinePlayerNames())
                .build();
            server.updateServerInfo(stoppingInfo);

            if (this.atlasBase.getNettyServer() != null) {
                this.atlasBase.getNettyServer().broadcastServerUpdate(server);
                Logger.debug("Broadcasted STOPPING status for server {} before deletion", server.getName());
            }
        }

        return provider.deleteServerCompletely(server, options)
                .thenAccept(success -> {
                    if (success) {
                        if (options.isRemoveFromTracking()) {
                            scaler.removeServerFromTracking(server.getServerId());
                        }

                        Logger.debug("Successfully removed server: {} (reason: {})", server.getName(), options.getReason());

                        this.notifyServerRemoval(server);
                    } else {
                        Logger.error("Unified deletion reported failure for server: {}", server.getName());
                    }
                })
                .exceptionally(throwable -> {
                    Logger.error("Failed to remove server: {} - Error: {}", server.getName(), throwable.getMessage());

                    if (options.isRemoveFromTracking()) {
                        scaler.removeServerFromTracking(server.getServerId());
                        Logger.warn("Ensured server {} tracking removal after unified deletion failure", server.getName());
                    }

                    this.cleanupServerResourcesAfterStop(server);

                    return null;
                });
    }

    /**
     * Restarts a server (stops then starts).
     *
     * @param server The server to restart
     * @return CompletableFuture that completes when the server is restarted
     */
    public CompletableFuture<Void> restartServer(AtlasServer server) {
        if (server.isShutdown()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Cannot restart server that is being shutdown: " + server.getName()));
        }

        Scaler scaler = this.getScalerForServer(server);
        if (scaler == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("No scaler found for group: " + server.getGroup()));
        }

        ServiceProvider provider = this.atlasBase.getProviderManager().getProvider();

        WebSocketManager webSocketManager = this.atlasBase.getApiManager().getWebSocketManager();
        webSocketManager.handleServerRestartStart(server.getServerId());

        scaler.markServerAsRestarting(server.getServerId());

        CompletableFuture<Void> stopFuture;
        if (server.getServerInfo() != null && server.getServerInfo().getStatus() == ServerStatus.STOPPED) {
            Logger.debug("Server {} is already stopped, proceeding directly to start", server.getName());
            stopFuture = CompletableFuture.completedFuture(null);
        } else {
            stopFuture = this.stopServer(server, DeletionOptions.serverRestart());
        }
        
        return stopFuture.thenCompose(v -> {
            Logger.debug("Server stopped, now restarting: {}", server.getName());

            if (server.getServerInfo() != null) {
                ServerInfo startingInfo = ServerInfo.builder()
                    .status(ServerStatus.STARTING)
                    .onlinePlayers(0)
                    .maxPlayers(server.getServerInfo().getMaxPlayers())
                    .onlinePlayerNames(new HashSet<>())
                    .build();
                server.updateServerInfo(startingInfo);
            }
            
            return provider.startServerCompletely(server, StartOptions.restart());
                })
                .thenAccept(restartedServer -> {
                    Logger.debug("Successfully restarted server: " + restartedServer.getName());

                    scaler.clearRestartFlag(server.getServerId());

                    WebSocketMessage restartMessage = WebSocketMessage.event("restart-completed", server.getServerId());
                    webSocketManager.sendToServerConnections(server.getServerId(), restartMessage);
                    webSocketManager.restartLogStreamingForServer(server.getServerId());
                    this.notifyServerUpdate(server);
                })
                .exceptionally(throwable -> {
                    Logger.error("Failed to restart server: " + server.getName(), throwable);
                    
                    // Clear restart flag even on failure
                    scaler.clearRestartFlag(server.getServerId());
                    
                    return null;
                });
    }

    /**
     * Cleans up server resources (WebSocket connections, log streams, etc.) after server stop.
     * Public method for use by Docker provider after container stop is confirmed.
     *
     * @param server The server to clean up
     */
    public void cleanupServerResourcesAfterStop(AtlasServer server) {
        try {
            WebSocketManager webSocketManager = this.atlasBase.getApiManager().getWebSocketManager();

            webSocketManager.disconnectServerConnections(server.getServerId(), "Server was " + (server.getServerInfo() != null && server.getServerInfo().getStatus() == ServerStatus.STOPPED ? "stopped" : "removed"));
            webSocketManager.stopLogStreamingForRestart(server.getServerId());

            this.clearWatchSubscriptions(server);

            Logger.debug("Cleaned up resources for server: " + server.getName());
        } catch (Exception e) {
            Logger.error("Failed to cleanup resources for server: " + server.getName(), e);
        }
    }

    /**
     * Clears watch subscriptions for a server to prevent stale entries.
     *
     * @param server The server to clear subscriptions for
     */
    private void clearWatchSubscriptions(AtlasServer server) {
        try {
            ACTIVE_WATCHES.remove(server.getName());
            ACTIVE_WATCHES.remove(server.getServerId());

            Logger.debug("Cleared watch subscriptions for server: " + server.getName());
        } catch (Exception e) {
            Logger.debug("Failed to clear watch subscriptions for server: " + server.getName(), e);
        }
    }

    /**
     * Adds a watch subscription for a server identifier.
     *
     * @param identifier     The server identifier (name or ID)
     * @param subscriptionId The subscription ID
     */
    public static void addWatchSubscription(String identifier, String subscriptionId) {
        ACTIVE_WATCHES.put(identifier, subscriptionId);
    }

    /**
     * Removes a watch subscription for a server identifier.
     *
     * @param identifier The server identifier (name or ID)
     * @return The subscription ID that was removed, or null if none existed
     */
    public static String removeWatchSubscription(String identifier) {
        return ACTIVE_WATCHES.remove(identifier);
    }

    /**
     * Gets the active watch subscription for a server identifier.
     *
     * @param identifier The server identifier (name or ID)
     * @return The subscription ID, or null if none exists
     */
    public static String getWatchSubscription(String identifier) {
        return ACTIVE_WATCHES.get(identifier);
    }

    /**
     * Notifies other systems about server updates.
     *
     * @param server The server that was updated
     */
    private void notifyServerUpdate(AtlasServer server) {
        try {
            if (this.atlasBase.getNettyServer() != null) {
                this.atlasBase.getNettyServer().broadcastServerUpdate(server);
            }
        } catch (Exception e) {
            Logger.error("Failed to notify server update for: " + server.getName(), e);
        }
    }

    /**
     * Notifies other systems about server removal.
     *
     * @param server The server that was removed
     */
    private void notifyServerRemoval(AtlasServer server) {
        try {
            if (this.atlasBase.getNettyServer() != null) {
                this.atlasBase.getNettyServer().broadcastServerRemove(server.getServerId(), "Server was removed");
            }
        } catch (Exception e) {
            Logger.error("Failed to notify server removal for: " + server.getName(), e);
        }
    }

    /**
     * Gets the scaler for a given server.
     *
     * @param server The server to get the scaler for
     * @return The scaler, or null if not found
     */
    private Scaler getScalerForServer(AtlasServer server) {
        return this.atlasBase.getScalerManager().getScaler(server.getGroup());
    }
}