package be.esmay.atlas.base.provider;

import be.esmay.atlas.common.models.AtlasServer;
import be.esmay.atlas.common.models.ServerInfo;
import be.esmay.atlas.common.models.ServerResourceMetrics;
import be.esmay.atlas.common.models.ServerStats;
import be.esmay.atlas.base.config.impl.ScalerConfig;
import lombok.Data;
import lombok.Getter;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Getter
public abstract class ServiceProvider {
    
    protected final String name;
    
    protected ServiceProvider(String name) {
        this.name = name;
    }

    /**
     * Creates a new server instance based on the provided group configuration and server info.
     * 
     * @param groupConfig the configuration for the server group
     * @param atlasServer the server information including working directory
     * @return a CompletableFuture containing the created AtlasServer
     */
    public abstract CompletableFuture<AtlasServer> createServer(ScalerConfig.Group groupConfig, AtlasServer atlasServer);
    
    
    /**
     * Stops a running server gracefully.
     * 
     * @param server the server to stop
     * @return a CompletableFuture containing void when complete
     */
    public abstract CompletableFuture<Void> stopServer(AtlasServer server);
    
    /**
     * Permanently deletes a server and releases all associated resources.
     * 
     * @param serverId the unique identifier of the server to delete
     * @return a CompletableFuture containing true if successful, false otherwise
     */
    public abstract CompletableFuture<Boolean> deleteServer(String serverId);
    
    /**
     * Retrieves information about a specific server.
     * 
     * @param serverId the unique identifier of the server
     * @return a CompletableFuture containing an Optional with the AtlasServer if found
     */
    public abstract CompletableFuture<Optional<AtlasServer>> getServer(String serverId);
    
    /**
     * Retrieves information about all servers managed by this creator.
     * 
     * @return a CompletableFuture containing a list of all AtlasServer objects
     */
    public abstract CompletableFuture<List<AtlasServer>> getAllServers();
    
    /**
     * Retrieves all servers belonging to a specific group.
     * 
     * @param group the name of the server group
     * @return a CompletableFuture containing a list of AtlasServer objects in the group
     */
    public abstract CompletableFuture<List<AtlasServer>> getServersByGroup(String group);
    
    /**
     * Checks if a server is currently running.
     * 
     * @param serverId the unique identifier of the server
     * @return a CompletableFuture containing true if running, false otherwise
     */
    public abstract CompletableFuture<Boolean> isServerRunning(String serverId);
    
    /**
     * Updates the status and information of an existing server.
     * 
     * @param serverId the unique identifier of the server to update
     * @param updatedServer the new AtlasServer to store
     * @return a CompletableFuture containing true if successful, false otherwise
     */
    public abstract CompletableFuture<Boolean> updateServerStatus(String serverId, AtlasServer updatedServer);
    
    /**
     * Retrieves real-time resource statistics for a specific server.
     * 
     * @param serverId the unique identifier of the server
     * @return a CompletableFuture containing ServerStats with CPU, memory, disk, and network usage
     */
    public abstract CompletableFuture<ServerStats> getServerStats(String serverId);
    
    /**
     * Retrieves resource metrics for a server.
     * 
     * @param serverId the unique identifier of the server
     * @return a CompletableFuture containing the server resource metrics
     */
    public abstract CompletableFuture<Optional<ServerResourceMetrics>> getServerResourceMetrics(String serverId);
    
    /**
     * Retrieves recent log lines from a server.
     * 
     * @param serverId the unique identifier of the server
     * @param lines the number of recent lines to retrieve (use -1 for all available)
     * @return a CompletableFuture containing a list of log lines
     */
    public abstract CompletableFuture<List<String>> getServerLogs(String serverId, int lines);
    
    /**
     * Registers a log consumer for streaming server logs in real-time.
     * This is designed to support WebSocket streaming for APIs.
     * 
     * @param serverId the unique identifier of the server
     * @param consumer the consumer function to receive log lines
     * @return a CompletableFuture containing a subscription ID for later unsubscription
     */
    public abstract CompletableFuture<String> streamServerLogs(String serverId, java.util.function.Consumer<String> consumer);
    
    /**
     * Unregisters a log consumer to stop receiving log updates.
     * 
     * @param subscriptionId the subscription ID returned by streamServerLogs
     * @return a CompletableFuture containing true if successful, false otherwise
     */
    public abstract CompletableFuture<Boolean> stopLogStream(String subscriptionId);
    
    /**
     * Ensures that all required resources (images, etc.) are available before scaling.
     * This method should block until all resources are ready.
     * 
     * @param groupConfig the configuration for the server group
     * @return a CompletableFuture that completes when resources are ready
     */
    public CompletableFuture<Void> ensureResourcesReady(ScalerConfig.Group groupConfig) {
        // Default implementation does nothing - providers can override
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Initializes the service provider after construction.
     * This method is called after all core services are initialized.
     * 
     * @return a CompletableFuture that completes when initialization is done
     */
    public CompletableFuture<Void> initialize() {
        // Default implementation does nothing - providers can override
        return CompletableFuture.completedFuture(null);
    }
    
    /**
     * Validates that tracked servers match actual container states.
     * This helps detect and clean up zombie servers.
     */
    public void validateServerState() {
        // Default implementation does nothing - providers can override
    }
    
    /**
     * Unified method to completely remove a server with specified options.
     * This is the single entry point for all server deletion operations.
     * 
     * @param server The server to delete
     * @param options Options controlling how the deletion is performed
     * @return CompletableFuture that completes when the server is fully removed
     */
    public abstract CompletableFuture<Boolean> deleteServerCompletely(AtlasServer server, DeletionOptions options);
    
    /**
     * Unified method to completely start a server with specified options.
     * This is the single entry point for all server start operations.
     * 
     * @param server The server to start
     * @param options Options controlling how the start is performed
     * @return CompletableFuture that completes with the started server
     */
    public abstract CompletableFuture<AtlasServer> startServerCompletely(AtlasServer server, StartOptions options);
    
    /**
     * Shuts down the service provider and releases all resources.
     * This method should be called when the application is shutting down.
     */
    public void shutdown() {
        // Default implementation does nothing - providers can override
    }
}