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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class InMemoryServiceProvider extends ServiceProvider {
    
    private final Map<String, ServerInfo> servers;
    private final Map<String, Deque<String>> serverLogs;
    private final Map<String, Map<String, Consumer<String>>> logSubscribers;
    private final ScheduledExecutorService heartbeatExecutor;
    private int portCounter = 25565;
    private int instanceCounter = 1;
    private static final int MAX_LOG_LINES = 1000;
    private static final int HEARTBEAT_INTERVAL_SECONDS = 5;
    
    public InMemoryServiceProvider(AtlasConfig.ServiceProvider serviceProviderConfig) {
        super("in-memory");

        this.servers = new ConcurrentHashMap<>();
        this.serverLogs = new ConcurrentHashMap<>();
        this.logSubscribers = new ConcurrentHashMap<>();
        this.heartbeatExecutor = Executors.newScheduledThreadPool(1, r -> {
            Thread thread = new Thread(r, "InMemory-Heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        
        // Start the heartbeat system
        this.startHeartbeatSystem();
    }
    
    /**
     * Creates a new in-memory server instance with simulated startup delay.
     * Automatically assigns ports starting from 25565.
     */
    @Override
    public CompletableFuture<ServerInfo> createServer(ScalerConfig.Group groupConfig, ServerInfo serverInfo) {
        return CompletableFuture.supplyAsync(() -> {
            String serverId = serverInfo.getServerId();

            String serviceProviderId = "inmem-server-" + (this.instanceCounter++);

            ServerInfo updatedServer = ServerInfo.builder()
                    .serverId(serverInfo.getServerId())
                    .name(serverInfo.getName())
                    .group(serverInfo.getGroup())
                    .workingDirectory(serverInfo.getWorkingDirectory())
                    .address("localhost")
                    .port(portCounter++)
                    .type(serverInfo.getType())
                    .status(ServerStatus.STARTING)
                    .onlinePlayers(0)
                    .maxPlayers(serverInfo.getMaxPlayers())
                    .onlinePlayerNames(new HashSet<>())
                    .createdAt(serverInfo.getCreatedAt())
                    .lastHeartbeat(System.currentTimeMillis())
                    .serviceProviderId(serviceProviderId)
                    .isManuallyScaled(serverInfo.isManuallyScaled())
                    .build();
            
            this.servers.put(serverId, updatedServer);
            this.serverLogs.put(serverId, new ConcurrentLinkedDeque<>());
            this.logSubscribers.put(serverId, new ConcurrentHashMap<>());
            
            this.addLog(serverId, "Server created: " + serverInfo.getName());
            this.addLog(serverId, "Working directory: " + serverInfo.getWorkingDirectory());
            this.addLog(serverId, "Service provider instance: " + serviceProviderId);
            Logger.info("Created in-memory server: {} (ID: {}, Instance: {})", serverInfo.getName(), serverId, serviceProviderId);
            
            CompletableFuture.delayedExecutor(2, java.util.concurrent.TimeUnit.SECONDS).execute(() -> {
                updatedServer.setStatus(ServerStatus.RUNNING);
                updatedServer.setLastHeartbeat(System.currentTimeMillis());
                this.addLog(serverId, "Server started successfully - max players: " + updatedServer.getMaxPlayers());
                this.addLog(serverId, "Listening on " + updatedServer.getAddress() + ":" + updatedServer.getPort());
                Logger.info("Server {} is now running with capacity for {} players", serverInfo.getName(), updatedServer.getMaxPlayers());
            });
            
            return updatedServer;
        });
    }
    
    /**
     * Starts a stopped server with a 2-second simulated startup delay.
     */
    @Override
    public CompletableFuture<Void> startServer(ServerInfo serverInfo) {
        return CompletableFuture.runAsync(() -> {
            String serverId = serverInfo.getServerId();
            ServerInfo server = this.servers.get(serverId);
            if (server == null) {
                Logger.warn("Server not found: {}", serverId);
                return;
            }
            
            if (server.getStatus() == ServerStatus.RUNNING) {
                Logger.warn("Server already running: {}", serverId);
                return;
            }
            
            server.setStatus(ServerStatus.STARTING);
            this.addLog(serverId, "Starting server...");
            
            CompletableFuture.delayedExecutor(2, java.util.concurrent.TimeUnit.SECONDS).execute(() -> {
                server.setStatus(ServerStatus.RUNNING);
                server.setLastHeartbeat(System.currentTimeMillis());
                
                this.addLog(serverId, "Server startup completed - ready for players");
                this.addLog(serverId, "Working directory: " + server.getWorkingDirectory());
                Logger.info("Started server: {}", server.getName());
            });
        });
    }
    
    /**
     * Stops a running server with a 1-second simulated shutdown delay.
     * Clears player data when stopped.
     */
    @Override
    public CompletableFuture<Void> stopServer(ServerInfo serverInfo) {
        return CompletableFuture.runAsync(() -> {
            String serverId = serverInfo.getServerId();
            ServerInfo server = this.servers.get(serverId);
            if (server == null) {
                Logger.warn("Server not found: {}", serverId);
                return;
            }
            
            server.setStatus(ServerStatus.STOPPING);
            this.addLog(serverId, "Stopping server...");
            
            CompletableFuture.delayedExecutor(1, java.util.concurrent.TimeUnit.SECONDS).execute(() -> {
                server.setStatus(ServerStatus.STOPPED);
                server.setOnlinePlayers(0);
                server.setOnlinePlayerNames(new HashSet<>());
                this.addLog(serverId, "Server stopped");
                Logger.debug("Stopped server: {}", server.getName());
            });
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
    
    /**
     * Starts the heartbeat system that simulates servers sending regular heartbeats
     * and occasionally simulates player activity.
     */
    private void startHeartbeatSystem() {
        this.heartbeatExecutor.scheduleWithFixedDelay(() -> {
            try {
                this.sendHeartbeats();
            } catch (Exception e) {
                Logger.error("Error in heartbeat system: {}", e.getMessage());
            }
        }, HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
        
        Logger.info("Started heartbeat system with {}s interval", HEARTBEAT_INTERVAL_SECONDS);
    }
    
    /**
     * Sends heartbeats for all running servers and simulates player activity.
     */
    private void sendHeartbeats() {
        for (ServerInfo server : this.servers.values()) {
            if (server.getStatus() == ServerStatus.RUNNING) {
                server.setLastHeartbeat(System.currentTimeMillis());

                if (Math.random() < 0.2) {
                    this.simulatePlayerActivity(server);
                }

                if (Math.random() < 0.1) {
                    this.addLog(server.getServerId(), "Heartbeat sent - Server healthy");
                }
            }
        }
    }
    
    /**
     * Simulates random player activity (joins/leaves) to make the server feel alive.
     */
    private void simulatePlayerActivity(ServerInfo server) {
        Random random = new Random();
        Set<String> playerNames = new HashSet<>(server.getOnlinePlayerNames());

        if (server.getMaxPlayers() == 0) {
            server.setMaxPlayers(20 + random.nextInt(81));
        }

        if (random.nextBoolean() && random.nextBoolean() && random.nextBoolean()) {
            if (playerNames.size() < server.getMaxPlayers() && (playerNames.isEmpty() || random.nextBoolean())) {
                String playerName = "Player" + (1000 + random.nextInt(9000));
                if (playerNames.add(playerName)) {
                    server.setOnlinePlayerNames(playerNames);
                    server.setOnlinePlayers(playerNames.size());
                    this.addLog(server.getServerId(), "Player " + playerName + " joined the server");
                }
            } else if (!playerNames.isEmpty()) {
                String playerName = playerNames.iterator().next();
                if (playerNames.remove(playerName)) {
                    server.setOnlinePlayerNames(playerNames);
                    server.setOnlinePlayers(playerNames.size());
                    this.addLog(server.getServerId(), "Player " + playerName + " left the server");
                }
            }
        }

        if (random.nextDouble() < 0.05) {
            String[] activities = {
                "Server tick completed (TPS: " + (18.5 + random.nextDouble() * 1.5) + ")",
                "Garbage collection completed in " + (10 + random.nextInt(50)) + "ms",
                "Chunk loaded at " + random.nextInt(1000) + ", " + random.nextInt(1000),
                "Auto-save completed",
                "Memory usage: " + (40 + random.nextInt(40)) + "%"
            };
            this.addLog(server.getServerId(), activities[random.nextInt(activities.length)]);
        }
    }
    
    /**
     * Shuts down the heartbeat system when the provider is no longer needed.
     */
    public void shutdown() {
        Logger.info("Shutting down InMemoryServiceProvider heartbeat system");
        this.heartbeatExecutor.shutdown();
        try {
            if (!this.heartbeatExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                this.heartbeatExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            this.heartbeatExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}