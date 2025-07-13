package be.esmay.atlas.base.provider;

import be.esmay.atlas.common.models.ServerInfo;
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
     * @param serverInfo the server information including working directory
     * @return a CompletableFuture containing the created ServerInfo
     */
    public abstract CompletableFuture<ServerInfo> createServer(ScalerConfig.Group groupConfig, ServerInfo serverInfo);
    
    /**
     * Starts an existing server that is currently stopped.
     * 
     * @param server the server to start
     * @return a CompletableFuture containing void when complete
     */
    public abstract CompletableFuture<Void> startServer(ServerInfo server);
    
    /**
     * Stops a running server gracefully.
     * 
     * @param server the server to stop
     * @return a CompletableFuture containing void when complete
     */
    public abstract CompletableFuture<Void> stopServer(ServerInfo server);
    
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
     * @return a CompletableFuture containing an Optional with the ServerInfo if found
     */
    public abstract CompletableFuture<Optional<ServerInfo>> getServer(String serverId);
    
    /**
     * Retrieves information about all servers managed by this creator.
     * 
     * @return a CompletableFuture containing a list of all ServerInfo objects
     */
    public abstract CompletableFuture<List<ServerInfo>> getAllServers();
    
    /**
     * Retrieves all servers belonging to a specific group.
     * 
     * @param group the name of the server group
     * @return a CompletableFuture containing a list of ServerInfo objects in the group
     */
    public abstract CompletableFuture<List<ServerInfo>> getServersByGroup(String group);
    
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
     * @param updatedInfo the new ServerInfo to store
     * @return a CompletableFuture containing true if successful, false otherwise
     */
    public abstract CompletableFuture<Boolean> updateServerStatus(String serverId, ServerInfo updatedInfo);
    
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
     * Shuts down the service provider and releases all resources.
     * This method should be called when the application is shutting down.
     */
    public void shutdown() {
        // Default implementation does nothing - providers can override
    }
}