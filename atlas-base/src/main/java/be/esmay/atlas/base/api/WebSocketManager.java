package be.esmay.atlas.base.api;

import be.esmay.atlas.base.AtlasBase;
import be.esmay.atlas.base.api.dto.WebSocketMessage;
import be.esmay.atlas.base.provider.ServiceProvider;
import be.esmay.atlas.base.scaler.Scaler;
import be.esmay.atlas.base.utils.Logger;
import be.esmay.atlas.common.models.AtlasServer;
import be.esmay.atlas.common.models.ServerInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

public final class WebSocketManager {

    private final ApiAuthHandler authHandler;
    private final ObjectMapper objectMapper;
    private final Map<String, WebSocketConnection> connections;
    private final Vertx vertx;
    private LogStreamManager logStreamManager;

    public WebSocketManager(ApiAuthHandler authHandler) {
        this.authHandler = authHandler;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.connections = new ConcurrentHashMap<>();
        this.vertx = Vertx.vertx();
        
        this.startPeriodicAuthChallenge();
        this.startStatsCollection();
        this.initializeLogStreaming();
    }

    public void handleWebSocket(RoutingContext context) {
        String serverId = context.pathParam("id");
        if (serverId == null) {
            context.response().setStatusCode(400).end("Server ID is required");
            return;
        }

        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();
        provider.getServer(serverId)
            .thenAccept(serverOpt -> {
                if (serverOpt.isEmpty()) {
                    context.response().setStatusCode(404).end("Server not found: " + serverId);
                    return;
                }

                String authHeader = context.request().getHeader("Authorization");
                String token = this.authHandler.extractBearerToken(authHeader);
                
                if (token == null) {
                    token = this.authHandler.extractTokenFromQuery(context.request().query());
                }

                if (!this.authHandler.isValidToken(token)) {
                    context.response().setStatusCode(401).end("Unauthorized");
                    return;
                }

                context.request().toWebSocket()
                    .onSuccess(webSocket -> this.handleWebSocketConnection(webSocket, serverId))
                    .onFailure(throwable -> Logger.error("Failed to establish WebSocket connection", throwable));
            })
            .exceptionally(throwable -> {
                Logger.error("Error checking server existence", throwable);
                context.response().setStatusCode(500).end("Internal server error");
                return null;
            });
    }

    private void handleWebSocketConnection(ServerWebSocket webSocket, String serverId) {
        String connectionId = UUID.randomUUID().toString();
        WebSocketConnection connection = new WebSocketConnection(connectionId, webSocket, System.currentTimeMillis(), serverId);
        
        boolean firstConnection = this.connections.values().stream()
            .noneMatch(conn -> conn.getServerId().equals(serverId));
        
        this.connections.put(connectionId, connection);
        Logger.debug("WebSocket connected for server " + serverId + ": " + connectionId);

        webSocket.textMessageHandler(message -> this.handleMessage(connection, message));
        webSocket.closeHandler(v -> this.handleDisconnection(connectionId));
        webSocket.exceptionHandler(throwable -> {
            Logger.error("WebSocket error for connection " + connectionId, throwable);
            this.handleDisconnection(connectionId);
        });

        this.sendMessage(connection, WebSocketMessage.authResult(true));
        this.sendServerInfo(connection);
        
        if (firstConnection && this.logStreamManager != null) {
            this.logStreamManager.startServerLogStream(serverId);
        }
    }

    private void handleMessage(WebSocketConnection connection, String messageText) {
        try {
            JsonObject messageJson = new JsonObject(messageText);
            String type = messageJson.getString("type");

            if (type == null) {
                this.sendMessage(connection, WebSocketMessage.error("Message type is required"));
                return;
            }

            switch (type) {
                case "auth" -> this.handleAuth(connection, messageJson);
                case "subscribe" -> this.handleSubscribe(connection, messageJson);
                case "server-start" -> this.handleServerStart(connection, messageJson);
                case "server-stop" -> this.handleServerStop(connection, messageJson);
                case "server-restart" -> this.handleServerRestart(connection, messageJson);
                case "server-create" -> this.handleServerCreate(connection, messageJson);
                case "server-remove" -> this.handleServerRemove(connection, messageJson);
                case "scale" -> this.handleScale(connection, messageJson);
                case "get-logs-history" -> this.handleGetLogsHistory(connection, messageJson);
                default -> this.sendMessage(connection, WebSocketMessage.error("Unknown message type: " + type));
            }
        } catch (Exception e) {
            Logger.error("Error handling WebSocket message", e);
            this.sendMessage(connection, WebSocketMessage.error("Failed to process message"));
        }
    }

    private void handleAuth(WebSocketConnection connection, JsonObject message) {
        String token = message.getString("token");
        boolean valid = this.authHandler.isValidToken(token);
        
        if (valid) {
            connection.setLastAuth(System.currentTimeMillis());
        }
        
        this.sendMessage(connection, WebSocketMessage.authResult(valid));
    }

    private void handleSubscribe(WebSocketConnection connection, JsonObject message) {
        Object streamsObj = message.getValue("streams");
        Object targetsObj = message.getValue("targets");
        
        JsonNode streams = null;
        JsonNode targets = null;
        
        if (streamsObj != null) {
            streams = this.objectMapper.valueToTree(streamsObj);
        }
        if (targetsObj != null) {
            targets = this.objectMapper.valueToTree(targetsObj);
        }
        
        connection.setSubscriptions(streams, targets);
        this.sendMessage(connection, WebSocketMessage.create("subscribe-result", "Subscriptions updated"));
    }

    private void handleServerStart(WebSocketConnection connection, JsonObject message) {
        String commandId = UUID.randomUUID().toString();
        
        this.executeServerAction(connection, commandId, connection.getServerId(), "start", 
            server -> AtlasBase.getInstance().getServerManager().startServer(server));
    }

    private void handleServerStop(WebSocketConnection connection, JsonObject message) {
        String commandId = UUID.randomUUID().toString();
        
        this.executeServerAction(connection, commandId, connection.getServerId(), "stop", 
            server -> AtlasBase.getInstance().getServerManager().stopServer(server));
    }

    private void handleServerRestart(WebSocketConnection connection, JsonObject message) {
        String commandId = UUID.randomUUID().toString();
        
        this.executeServerAction(connection, commandId, connection.getServerId(), "restart", 
            server -> AtlasBase.getInstance().getServerManager().restartServer(server));
    }

    private void handleServerCreate(WebSocketConnection connection, JsonObject message) {
        String group = message.getString("group");
        String commandId = UUID.randomUUID().toString();

        if (group == null) {
            this.sendMessage(connection, WebSocketMessage.commandResult(commandId, false, "Group is required"));
            return;
        }

        Scaler scaler = AtlasBase.getInstance().getScalerManager().getScaler(group);
        if (scaler == null) {
            this.sendMessage(connection, WebSocketMessage.commandResult(commandId, false, "Group not found: " + group));
            return;
        }

        scaler.upscale()
            .thenRun(() -> this.sendMessage(connection, WebSocketMessage.commandResult(commandId, true, "Server creation initiated")))
            .exceptionally(throwable -> {
                this.sendMessage(connection, WebSocketMessage.commandResult(commandId, false, "Failed to create server: " + throwable.getMessage()));
                return null;
            });
    }

    private void handleServerRemove(WebSocketConnection connection, JsonObject message) {
        String commandId = UUID.randomUUID().toString();
        
        this.executeServerAction(connection, commandId, connection.getServerId(), "remove", 
            server -> AtlasBase.getInstance().getServerManager().removeServer(server));
    }

    private void handleScale(WebSocketConnection connection, JsonObject message) {
        String group = message.getString("group");
        String direction = message.getString("direction");
        String commandId = UUID.randomUUID().toString();

        if (group == null || direction == null) {
            this.sendMessage(connection, WebSocketMessage.commandResult(commandId, false, "Group and direction are required"));
            return;
        }

        Scaler scaler = AtlasBase.getInstance().getScalerManager().getScaler(group);
        if (scaler == null) {
            this.sendMessage(connection, WebSocketMessage.commandResult(commandId, false, "Group not found: " + group));
            return;
        }

        CompletableFuture<Void> action;
        if (direction.equals("up")) {
            action = scaler.upscale();
        } else if (direction.equals("down")) {
            action = scaler.triggerScaleDown();
        } else {
            this.sendMessage(connection, WebSocketMessage.commandResult(commandId, false, "Direction must be 'up' or 'down'"));
            return;
        }

        action.thenRun(() -> this.sendMessage(connection, WebSocketMessage.commandResult(commandId, true, "Scaling " + direction + " initiated")))
            .exceptionally(throwable -> {
                this.sendMessage(connection, WebSocketMessage.commandResult(commandId, false, "Failed to scale: " + throwable.getMessage()));
                return null;
            });
    }

    private void handleGetLogsHistory(WebSocketConnection connection, JsonObject message) {
        String serverId = message.getString("serverId");
        Integer lines = message.getInteger("lines", 20);

        if (serverId == null) {
            this.sendMessage(connection, WebSocketMessage.error("serverId is required"));
            return;
        }

        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();
        
        provider.getServerLogs(serverId, lines)
            .thenAccept(logs -> {
                JsonObject data = new JsonObject().put("logs", logs);
                this.sendMessage(connection, WebSocketMessage.create("logs-history", this.objectMapper.valueToTree(data), serverId));
            })
            .exceptionally(throwable -> {
                this.sendMessage(connection, WebSocketMessage.error("Failed to get logs: " + throwable.getMessage()));
                return null;
            });
    }

    private void executeServerAction(WebSocketConnection connection, String commandId, String serverId, String action, Function<AtlasServer, CompletableFuture<Void>> serverAction) {
        if (serverId == null) {
            this.sendMessage(connection, WebSocketMessage.commandResult(commandId, false, "serverId is required"));
            return;
        }

        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();

        provider.getServer(serverId)
            .thenAccept(serverOpt -> {
                if (serverOpt.isEmpty()) {
                    this.sendMessage(connection, WebSocketMessage.commandResult(commandId, false, "Server not found: " + serverId));
                    return;
                }

                AtlasServer server = serverOpt.get();
                serverAction.apply(server)
                    .thenRun(() -> this.sendMessage(connection, WebSocketMessage.commandResult(commandId, true, "Server " + action + " initiated")))
                    .exceptionally(throwable -> {
                        this.sendMessage(connection, WebSocketMessage.commandResult(commandId, false, "Failed to " + action + " server: " + throwable.getMessage()));
                        return null;
                    });
            })
            .exceptionally(throwable -> {
                this.sendMessage(connection, WebSocketMessage.commandResult(commandId, false, "Failed to find server: " + throwable.getMessage()));
                return null;
            });
    }

    private void handleDisconnection(String connectionId) {
        WebSocketConnection connection = this.connections.remove(connectionId);
        if (connection != null) {
            String serverId = connection.getServerId();
            Logger.debug("WebSocket disconnected: " + connectionId);
            
            boolean lastConnection = this.connections.values().stream()
                .noneMatch(conn -> conn.getServerId().equals(serverId));
            
            if (lastConnection && this.logStreamManager != null) {
                this.logStreamManager.stopServerLogStream(serverId);
            }
        }
    }

    private void sendMessage(WebSocketConnection connection, WebSocketMessage message) {
        try {
            String json = this.objectMapper.writeValueAsString(message);
            connection.getWebSocket().writeTextMessage(json);
        } catch (Exception e) {
            Logger.error("Failed to send WebSocket message", e);
        }
    }

    private void sendServerInfo(WebSocketConnection connection) {
        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();
        provider.getServer(connection.getServerId())
            .thenAccept(serverOpt -> {
                if (serverOpt.isPresent()) {
                    AtlasServer server = serverOpt.get();
                    JsonObject serverData = new JsonObject();
                    serverData.put("serverId", server.getServerId());
                    serverData.put("name", server.getName());
                    serverData.put("group", server.getGroup());
                    serverData.put("status", server.getServerInfo() != null ? server.getServerInfo().getStatus().toString() : "UNKNOWN");
                    serverData.put("type", server.getType().toString());
                    serverData.put("address", server.getAddress());
                    serverData.put("port", server.getPort());
                    serverData.put("onlinePlayers", server.getServerInfo() != null ? server.getServerInfo().getOnlinePlayers() : 0);
                    serverData.put("maxPlayers", server.getServerInfo() != null ? server.getServerInfo().getMaxPlayers() : 0);
                    serverData.put("manuallyScaled", server.isManuallyScaled());
                    serverData.put("createdAt", server.getCreatedAt());
                    serverData.put("lastHeartbeat", server.getServerInfo() != null ? server.getLastHeartbeat() : 0);
                    
                    try {
                        JsonNode serverNode = this.objectMapper.valueToTree(serverData.getMap());
                        this.sendMessage(connection, WebSocketMessage.serverInfo(serverNode));
                    } catch (Exception e) {
                        Logger.error("Failed to convert server data to JsonNode", e);
                    }
                }
            })
            .exceptionally(throwable -> {
                Logger.error("Failed to send server info", throwable);
                return null;
            });
    }

    public void broadcastMessage(WebSocketMessage message) {
        String json;
        try {
            json = this.objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            Logger.error("Failed to serialize broadcast message", e);
            return;
        }

        for (WebSocketConnection connection : this.connections.values()) {
            if (this.shouldReceiveMessage(connection, message)) {
                connection.getWebSocket().writeTextMessage(json);
            }
        }
    }

    public void sendToServerConnections(String serverId, WebSocketMessage message) {
        String json;
        try {
            json = this.objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            Logger.error("Failed to serialize server message", e);
            return;
        }

        for (WebSocketConnection connection : this.connections.values()) {
            if (connection.getServerId().equals(serverId)) {
                connection.getWebSocket().writeTextMessage(json);
            }
        }
    }

    public void disconnectServerConnections(String serverId, String reason) {
        for (WebSocketConnection connection : this.connections.values()) {
            if (connection.getServerId().equals(serverId)) {
                try {
                    WebSocketMessage message = WebSocketMessage.error("Server stopped: " + reason);
                    this.sendMessage(connection, message);
                    
                    connection.getWebSocket().close((short) 1000, reason);
                    Logger.debug("Disconnected WebSocket for stopped server " + serverId + ": " + connection.getId());
                } catch (Exception e) {
                    Logger.error("Error disconnecting WebSocket for server " + serverId, e);
                }
            }
        }
    }

    public void handleServerRestartStart(String serverId) {
        WebSocketMessage message = WebSocketMessage.event("restart-started", serverId);
        this.sendToServerConnections(serverId, message);
        
        if (this.logStreamManager != null) {
            this.logStreamManager.stopServerLogStream(serverId);
        }
        
        Logger.debug("Stopped log streaming for server restart: " + serverId);
    }

    public void stopLogStreamingForRestart(String serverId) {
        if (this.logStreamManager != null) {
            this.logStreamManager.stopServerLogStream(serverId);
            Logger.debug("Stopped log streaming for server restart: " + serverId);
        }
    }

    public void restartLogStreamingForServer(String serverId) {
        boolean hasConnections = this.connections.values().stream()
            .anyMatch(conn -> serverId.equals(conn.getServerId()));
        
        if (hasConnections && this.logStreamManager != null) {
            this.logStreamManager.startServerLogStream(serverId);
            Logger.debug("Restarted log streaming after server restart: " + serverId);
        }
    }

    private boolean shouldReceiveMessage(WebSocketConnection connection, WebSocketMessage message) {
        Set<String> streams = connection.getSubscribedStreams();
        Set<String> targets = connection.getSubscribedTargets();

        if (streams.isEmpty()) {
            return true;
        }

        String messageType = message.getType();
        if (messageType.equals("log") && !streams.contains("logs")) {
            return false;
        }
        if (messageType.equals("stats") && !streams.contains("stats")) {
            return false;
        }
        if (messageType.equals("event") && !streams.contains("events")) {
            return false;
        }

        if (!targets.isEmpty() && message.getServerId() != null) {
            return targets.contains(message.getServerId()) || targets.contains("global");
        }

        return true;
    }

    private void startPeriodicAuthChallenge() {
        this.vertx.setPeriodic(300000, timerId -> {
            long now = System.currentTimeMillis();
            
            for (WebSocketConnection connection : this.connections.values()) {
                if (now - connection.getLastAuth() > 300000) {
                    this.sendMessage(connection, WebSocketMessage.authChallenge());
                }
            }
        });
    }

    private void startStatsCollection() {
        this.vertx.setPeriodic(30000, timerId -> this.collectAndBroadcastStats());
    }

    private void collectAndBroadcastStats() {
        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();

        for (WebSocketConnection connection : this.connections.values()) {
            String serverId = connection.getServerId();
            
            provider.getServer(serverId)
                .thenAccept(serverOpt -> {
                    if (serverOpt.isPresent()) {
                        AtlasServer server = serverOpt.get();
                        
                        provider.getServerStats(serverId)
                            .thenAccept(serverStats -> {
                                JsonObject ramObject = new JsonObject();
                                ramObject.put("used", serverStats.getMemoryUsedBytes() / (1024 * 1024));
                                ramObject.put("total", serverStats.getMemoryTotalBytes() / (1024 * 1024));
                                ramObject.put("percentage", serverStats.getMemoryUsagePercent());
                                
                                JsonObject diskObject = new JsonObject();
                                diskObject.put("used", serverStats.getDiskUsedBytes() / (1024 * 1024 * 1024));
                                diskObject.put("total", serverStats.getDiskTotalBytes() / (1024 * 1024 * 1024));
                                diskObject.put("percentage", serverStats.getDiskUsagePercent());
                                
                                JsonObject networkObject = new JsonObject();
                                networkObject.put("rx", serverStats.getNetworkRxBytes());
                                networkObject.put("tx", serverStats.getNetworkTxBytes());
                                
                                JsonObject stats = new JsonObject();
                                stats.put("cpu", serverStats.getCpuUsagePercent());
                                stats.put("ram", ramObject);
                                stats.put("disk", diskObject);
                                stats.put("network", networkObject);
                                stats.put("players", server.getServerInfo() != null ? server.getServerInfo().getOnlinePlayers() : 0);
                                stats.put("maxPlayers", server.getServerInfo() != null ? server.getServerInfo().getMaxPlayers() : 0);
                                stats.put("status", server.getServerInfo() != null ? server.getServerInfo().getStatus().toString() : "UNKNOWN");
                                stats.put("timestamp", serverStats.getTimestamp());

                                try {
                                    WebSocketMessage message = WebSocketMessage.create("stats", this.objectMapper.valueToTree(stats));
                                    this.sendMessage(connection, message);
                                } catch (Exception e) {
                                    Logger.error("Failed to send server stats", e);
                                }
                            })
                            .exceptionally(statsThrowable -> {
                                Logger.error("Failed to get server stats for " + serverId, statsThrowable);
                                return null;
                            });
                    }
                })
                .exceptionally(throwable -> {
                    Logger.error("Failed to get server for stats: " + serverId, throwable);
                    return null;
                });
        }
    }

    private void initializeLogStreaming() {
        this.logStreamManager = new LogStreamManager(this);
        this.logStreamManager.initialize();
    }

    public void broadcastServerEvent(String serverId, String event) {
        if (this.logStreamManager != null) {
            this.logStreamManager.broadcastServerEvent(serverId, event);
        }
    }

    public Future<Void> stop() {
        for (WebSocketConnection connection : this.connections.values()) {
            connection.getWebSocket().close();
        }
        this.connections.clear();
        
        if (this.logStreamManager != null) {
            return this.logStreamManager.shutdown();
        }
        
        return Future.succeededFuture();
    }

    @Getter
    private static class WebSocketConnection {
        private final String id;
        private final ServerWebSocket webSocket;
        private final String serverId;

        @Setter
        private volatile long lastAuth;
        private volatile Set<String> subscribedStreams = new HashSet<>();
        private volatile Set<String> subscribedTargets = new HashSet<>();

        public WebSocketConnection(String id, ServerWebSocket webSocket, long lastAuth, String serverId) {
            this.id = id;
            this.webSocket = webSocket;
            this.lastAuth = lastAuth;
            this.serverId = serverId;
        }

        public void setSubscriptions(JsonNode streams, JsonNode targets) {
            if (streams != null && streams.isArray()) {
                this.subscribedStreams = new HashSet<>();
                for (JsonNode stream : streams) {
                    this.subscribedStreams.add(stream.asText());
                }
            }
            
            if (targets != null && targets.isArray()) {
                this.subscribedTargets = new HashSet<>();
                for (JsonNode target : targets) {
                    this.subscribedTargets.add(target.asText());
                }
            }
        }
    }
}