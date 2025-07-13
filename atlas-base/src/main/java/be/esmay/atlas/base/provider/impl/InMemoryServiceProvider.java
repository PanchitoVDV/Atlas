package be.esmay.atlas.base.provider.impl;

import be.esmay.atlas.base.config.impl.AtlasConfig;
import be.esmay.atlas.base.provider.ServiceProvider;
import be.esmay.atlas.common.models.ServerInfo;
import be.esmay.atlas.common.enums.ServerStatus;
import be.esmay.atlas.common.enums.ServerType;
import be.esmay.atlas.base.config.impl.ScalerConfig;
import be.esmay.atlas.base.utils.Logger;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class InMemoryServiceProvider extends ServiceProvider {
    
    private final Map<String, ServerInfo> servers;
    private final Map<String, Deque<String>> serverLogs;
    private final Map<String, Map<String, Consumer<String>>> logSubscribers;
    private int portCounter = 25565;
    private static final int MAX_LOG_LINES = 1000;
    
    public InMemoryServiceProvider(AtlasConfig.ServiceProvider serviceProviderConfig) {
        super("in-memory");

        this.servers = new ConcurrentHashMap<>();
        this.serverLogs = new ConcurrentHashMap<>();
        this.logSubscribers = new ConcurrentHashMap<>();
    }
    
    /**
     * Creates a new in-memory server instance with simulated startup delay.
     * Automatically assigns ports starting from 25565.
     */
    @Override
    public CompletableFuture<ServerInfo> createServer(ScalerConfig.Group groupConfig, String serverName) {
        return CompletableFuture.supplyAsync(() -> {
            String serverId = UUID.randomUUID().toString();
            
            ServerInfo serverInfo = ServerInfo.builder()
                    .serverId(serverId)
                    .name(serverName)
                    .group(groupConfig.getName())
                    .address("localhost")
                    .port(portCounter++)
                    .type(ServerType.valueOf(groupConfig.getServer().getType().toUpperCase()))
                    .status(ServerStatus.STARTING)
                    .onlinePlayers(0)
                    .maxPlayers(0)
                    .onlinePlayerNames(new HashSet<>())
                    .createdAt(System.currentTimeMillis())
                    .lastHeartbeat(System.currentTimeMillis())
                    .serviceProviderId(this.name)
                    .isManuallyScaled(false)
                    .build();
            
            servers.put(serverId, serverInfo);
            serverLogs.put(serverId, new ConcurrentLinkedDeque<>());
            logSubscribers.put(serverId, new ConcurrentHashMap<>());
            
            addLog(serverId, "Server created: " + serverName);
            Logger.info("Created in-memory server: {} (ID: {})", serverName, serverId);
            
            CompletableFuture.delayedExecutor(2, java.util.concurrent.TimeUnit.SECONDS).execute(() -> {
                serverInfo.setStatus(ServerStatus.RUNNING);
                serverInfo.setLastHeartbeat(System.currentTimeMillis());
                addLog(serverId, "Server started successfully");
                Logger.info("Server {} is now running", serverName);
            });
            
            return serverInfo;
        });
    }
    
    /**
     * Starts a stopped server with a 2-second simulated startup delay.
     */
    @Override
    public CompletableFuture<Boolean> startServer(String serverId) {
        return CompletableFuture.supplyAsync(() -> {
            ServerInfo server = servers.get(serverId);
            if (server == null) {
                Logger.warn("Server not found: {}", serverId);
                return false;
            }
            
            if (server.getStatus() == ServerStatus.RUNNING) {
                Logger.warn("Server already running: {}", serverId);
                return false;
            }
            
            server.setStatus(ServerStatus.STARTING);
            
            CompletableFuture.delayedExecutor(2, java.util.concurrent.TimeUnit.SECONDS).execute(() -> {
                server.setStatus(ServerStatus.RUNNING);
                server.setLastHeartbeat(System.currentTimeMillis());
                Logger.info("Started server: {}", server.getName());
            });
            
            return true;
        });
    }
    
    /**
     * Stops a running server with a 1-second simulated shutdown delay.
     * Clears player data when stopped.
     */
    @Override
    public CompletableFuture<Boolean> stopServer(String serverId) {
        return CompletableFuture.supplyAsync(() -> {
            ServerInfo server = servers.get(serverId);
            if (server == null) {
                Logger.warn("Server not found: {}", serverId);
                return false;
            }
            
            server.setStatus(ServerStatus.STOPPING);
            
            CompletableFuture.delayedExecutor(1, java.util.concurrent.TimeUnit.SECONDS).execute(() -> {
                server.setStatus(ServerStatus.STOPPED);
                server.setOnlinePlayers(0);
                server.setOnlinePlayerNames(new HashSet<>());
                Logger.info("Stopped server: {}", server.getName());
            });
            
            return true;
        });
    }
    
    /**
     * Removes a server from the in-memory storage permanently.
     */
    @Override
    public CompletableFuture<Boolean> deleteServer(String serverId) {
        return CompletableFuture.supplyAsync(() -> {
            ServerInfo removed = servers.remove(serverId);
            if (removed != null) {
                Logger.info("Deleted server: {} (ID: {})", removed.getName(), serverId);
                return true;
            }
            Logger.warn("Server not found for deletion: {}", serverId);
            return false;
        });
    }
    
    /**
     * Retrieves a server by its ID from the in-memory storage.
     */
    @Override
    public CompletableFuture<Optional<ServerInfo>> getServer(String serverId) {
        return CompletableFuture.completedFuture(Optional.ofNullable(servers.get(serverId)));
    }
    
    /**
     * Returns all servers currently stored in memory.
     */
    @Override
    public CompletableFuture<List<ServerInfo>> getAllServers() {
        return CompletableFuture.completedFuture(new ArrayList<>(servers.values()));
    }
    
    /**
     * Filters and returns servers belonging to the specified group.
     */
    @Override
    public CompletableFuture<List<ServerInfo>> getServersByGroup(String group) {
        return CompletableFuture.supplyAsync(() -> 
            servers.values().stream()
                .filter(server -> server.getGroup().equals(group))
                .collect(Collectors.toList())
        );
    }
    
    /**
     * Checks if a server exists and has RUNNING status.
     */
    @Override
    public CompletableFuture<Boolean> isServerRunning(String serverId) {
        return CompletableFuture.supplyAsync(() -> {
            ServerInfo server = servers.get(serverId);
            return server != null && server.getStatus() == ServerStatus.RUNNING;
        });
    }
    
    /**
     * Replaces the stored server information with updated data.
     */
    @Override
    public CompletableFuture<Boolean> updateServerStatus(String serverId, ServerInfo updatedInfo) {
        return CompletableFuture.supplyAsync(() -> {
            if (!servers.containsKey(serverId)) {
                Logger.warn("Server not found for update: {}", serverId);
                return false;
            }
            
            servers.put(serverId, updatedInfo);
            Logger.debug("Updated server status for: {}", updatedInfo.getName());
            return true;
        });
    }
    
    /**
     * Returns recent log lines from the simulated server logs.
     */
    @Override
    public CompletableFuture<List<String>> getServerLogs(String serverId, int lines) {
        return CompletableFuture.supplyAsync(() -> {
            Deque<String> logs = serverLogs.get(serverId);
            if (logs == null) {
                return new ArrayList<>();
            }
            
            if (lines <= 0) {
                return new ArrayList<>(logs);
            }
            
            return logs.stream()
                    .skip(Math.max(0, logs.size() - lines))
                    .collect(Collectors.toList());
        });
    }
    
    /**
     * Registers a consumer for real-time log streaming.
     */
    @Override
    public CompletableFuture<String> streamServerLogs(String serverId, Consumer<String> consumer) {
        return CompletableFuture.supplyAsync(() -> {
            if (!servers.containsKey(serverId)) {
                return null;
            }
            
            String subscriptionId = UUID.randomUUID().toString();
            logSubscribers.computeIfAbsent(serverId, k -> new ConcurrentHashMap<>())
                    .put(subscriptionId, consumer);
            
            addLog(serverId, "Log stream started for subscription: " + subscriptionId);
            return subscriptionId;
        });
    }
    
    /**
     * Unsubscribes from log streaming.
     */
    @Override
    public CompletableFuture<Boolean> stopLogStream(String subscriptionId) {
        return CompletableFuture.supplyAsync(() -> {
            for (Map<String, Consumer<String>> subscribers : logSubscribers.values()) {
                if (subscribers.remove(subscriptionId) != null) {
                    return true;
                }
            }
            return false;
        });
    }
    
    /**
     * Helper method to add a log line and notify subscribers.
     */
    private void addLog(String serverId, String logLine) {
        String timestampedLog = String.format("[%s] %s", 
                java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")), 
                logLine);
        
        Deque<String> logs = serverLogs.get(serverId);
        if (logs != null) {
            logs.addLast(timestampedLog);

            while (logs.size() > MAX_LOG_LINES) {
                logs.removeFirst();
            }

            Map<String, Consumer<String>> subscribers = logSubscribers.get(serverId);
            if (subscribers != null) {
                subscribers.values().forEach(consumer -> {
                    try {
                        consumer.accept(timestampedLog);
                    } catch (Exception e) {
                        Logger.warn("Error notifying log subscriber: {}", e.getMessage());
                    }
                });
            }
        }
    }
}