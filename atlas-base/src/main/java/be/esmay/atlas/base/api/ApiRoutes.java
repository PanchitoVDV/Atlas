package be.esmay.atlas.base.api;

import be.esmay.atlas.base.AtlasBase;
import be.esmay.atlas.base.activity.ActivityService;
import be.esmay.atlas.base.activity.ActivityType;
import be.esmay.atlas.base.activity.ServerActivity;
import be.esmay.atlas.base.api.dto.ActivityResponse;
import be.esmay.atlas.base.api.dto.ApiResponse;
import be.esmay.atlas.base.api.dto.FileListResponse;
import be.esmay.atlas.base.api.dto.UploadSession;
import be.esmay.atlas.base.config.impl.ScalerConfig;
import be.esmay.atlas.base.files.FileManager;
import be.esmay.atlas.base.metrics.NetworkBandwidthMonitor;
import be.esmay.atlas.base.provider.ServiceProvider;
import be.esmay.atlas.base.scaler.Scaler;
import be.esmay.atlas.base.utils.Logger;
import be.esmay.atlas.common.enums.ServerStatus;
import be.esmay.atlas.common.models.AtlasServer;
import be.esmay.atlas.common.models.ServerResourceMetrics;
import be.esmay.atlas.common.network.packet.packets.MetadataUpdatePacket;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.management.OperatingSystemMXBean;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public final class ApiRoutes {

    private final Router router;
    private final ApiAuthHandler authHandler;
    private final ObjectMapper objectMapper;
    private final FileManager fileManager;

    public ApiRoutes(Router router, ApiAuthHandler authHandler) {
        this.router = router;
        this.authHandler = authHandler;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.fileManager = new FileManager();
    }

    public void setupRoutes() {
        this.router.post("/api/v1/servers/:id/files/upload").handler(this::uploadFileWithAuth);

        this.router.put("/api/v1/servers/:id/files/upload/:uploadId/chunk/:chunkNumber").handler(this::uploadChunkWithAuth);

        this.router.post("/api/v1/templates/files/upload").handler(this::uploadTemplateFileWithAuth);

        this.router.route("/api/v1/*").handler(BodyHandler.create());
        this.router.route("/api/v1/*").handler(this.authHandler.authenticate());

        this.router.get("/api/v1/status").handler(this::getStatus);
        this.router.get("/api/v1/servers").handler(this::getServers);
        this.router.get("/api/v1/servers/count").handler(this::getServerCount);
        this.router.get("/api/v1/players/count").handler(this::getPlayerCount);
        this.router.get("/api/v1/servers/:id").handler(this::getServer);
        this.router.get("/api/v1/servers/:id/logs").handler(this::getServerLogs);
        this.router.get("/api/v1/servers/:id/files").handler(this::getServerFiles);
        this.router.get("/api/v1/servers/:id/files/contents").handler(this::getFileContents);
        this.router.put("/api/v1/servers/:id/files/contents").handler(this::writeFileContents);
        this.router.delete("/api/v1/servers/:id/files/contents").handler(this::deleteFile);
        this.router.get("/api/v1/servers/:id/files/download").handler(this::downloadFile);
        this.router.post("/api/v1/servers/:id/files/upload/start").handler(this::startChunkedUpload);
        this.router.post("/api/v1/servers/:id/files/upload/:uploadId/complete").handler(this::completeChunkedUpload);
        this.router.post("/api/v1/servers/:id/files/mkdir").handler(this::createDirectory);
        this.router.post("/api/v1/servers/:id/files/rename").handler(this::renameFile);
        this.router.get("/api/v1/groups").handler(this::getGroups);
        this.router.get("/api/v1/groups/:name").handler(this::getGroup);
        this.router.get("/api/v1/scaling").handler(this::getScaling);
        this.router.get("/api/v1/metrics").handler(this::getMetrics);
        this.router.get("/api/v1/utilization").handler(this::getUtilization);
        this.router.get("/api/v1/activity/recent").handler(this::getRecentActivity);
        this.router.get("/api/v1/activity/servers/:id").handler(this::getServerActivity);
        this.router.get("/api/v1/activity/groups/:name").handler(this::getGroupActivity);
        
        this.router.get("/api/v1/templates/files").handler(this::getTemplateFiles);
        this.router.get("/api/v1/templates/files/contents").handler(this::getTemplateFileContents);
        this.router.put("/api/v1/templates/files/contents").handler(this::writeTemplateFileContents);
        this.router.delete("/api/v1/templates/files/contents").handler(this::deleteTemplateFile);
        this.router.get("/api/v1/templates/files/download").handler(this::downloadTemplateFile);
        this.router.post("/api/v1/templates/files/mkdir").handler(this::createTemplateDirectory);
        this.router.post("/api/v1/templates/files/rename").handler(this::renameTemplateFile);

        this.router.get("/api/v1/servers/:id/metadata").handler(this::getServerMetadata);
        this.router.put("/api/v1/servers/:id/metadata").handler(this::setServerMetadata);
        this.router.put("/api/v1/servers/:id/metadata/:key").handler(this::setServerMetadataKey);
        this.router.delete("/api/v1/servers/:id/metadata/:key").handler(this::removeServerMetadataKey);

        this.router.post("/api/v1/servers").handler(this::createServers);
        this.router.post("/api/v1/servers/:id/start").handler(this::startServer);
        this.router.post("/api/v1/servers/:id/stop").handler(this::stopServer);
        this.router.post("/api/v1/servers/:id/restart").handler(this::restartServer);
        this.router.post("/api/v1/servers/:id/command").handler(this::executeServerCommand);
        this.router.post("/api/v1/servers/:id/ws/token").handler(this::generateWebSocketToken);
        this.router.delete("/api/v1/servers/:id").handler(this::removeServer);
        this.router.post("/api/v1/groups/:group/scale").handler(this::scaleGroup);
    }

    private void getStatus(RoutingContext context) {
        AtlasBase atlasBase = AtlasBase.getInstance();

        Map<String, Object> status = new HashMap<>();
        status.put("running", atlasBase.isRunning());
        status.put("debugMode", atlasBase.isDebugMode());
        status.put("uptime", System.currentTimeMillis());

        this.sendResponse(context, ApiResponse.success(status));
    }

    private void getServers(RoutingContext context) {
        String groupFilter = context.request().getParam("group");
        String statusFilter = context.request().getParam("status");
        String searchFilter = context.request().getParam("search");
        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();

        CompletableFuture<List<AtlasServer>> serversFuture;
        if (groupFilter != null && !groupFilter.isEmpty()) {
            serversFuture = provider.getAllServers()
                    .thenApply(allServers -> allServers.stream()
                            .filter(server -> server.getGroup() != null && server.getGroup().equalsIgnoreCase(groupFilter))
                            .collect(Collectors.toList()));
        } else {
            serversFuture = provider.getAllServers();
        }

        serversFuture
                .thenAccept(servers -> {
                    if (AtlasBase.getInstance().getResourceMetricsManager() != null) {
                        for (AtlasServer server : servers) {
                            ServerResourceMetrics metrics = AtlasBase.getInstance()
                                    .getResourceMetricsManager()
                                    .getMetrics(server.getServerId());
                            if (metrics != null) {
                                server.updateResourceMetrics(metrics);
                            }
                        }
                    }

                    List<AtlasServer> filteredServers = servers.stream()
                            .filter(server -> {
                                if (statusFilter != null && !statusFilter.isEmpty()) {
                                    ServerStatus requestedStatus;
                                    try {
                                        requestedStatus = ServerStatus.valueOf(statusFilter.toUpperCase());
                                    } catch (IllegalArgumentException e) {
                                        return false;
                                    }
                                    return server.getServerInfo() != null &&
                                            server.getServerInfo().getStatus() == requestedStatus;
                                }
                                return true;
                            })
                            .filter(server -> {
                                if (searchFilter != null && !searchFilter.isEmpty()) {
                                    String search = searchFilter.toLowerCase();
                                    return server.getName().toLowerCase().contains(search) ||
                                            server.getServerId().toLowerCase().contains(search) ||
                                            server.getGroup().toLowerCase().contains(search);
                                }
                                return true;
                            })
                            .collect(Collectors.toList());

                    this.sendResponse(context, ApiResponse.success(filteredServers));
                })
                .exceptionally(throwable -> {
                    this.sendError(context, "Failed to get servers: " + throwable.getMessage());
                    return null;
                });
    }

    private void getServer(RoutingContext context) {
        String serverId = context.pathParam("id");
        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();

        provider.getServer(serverId)
                .thenAccept(serverOpt -> {
                    if (serverOpt.isPresent()) {
                        AtlasServer server = serverOpt.get();

                        if (AtlasBase.getInstance().getResourceMetricsManager() != null) {
                            ServerResourceMetrics metrics = AtlasBase.getInstance()
                                    .getResourceMetricsManager()
                                    .getMetrics(server.getServerId());
                            if (metrics != null) {
                                server.updateResourceMetrics(metrics);
                            }
                        }

                        this.sendResponse(context, ApiResponse.success(server));
                    } else {
                        this.sendError(context, "Server not found: " + serverId, 404);
                    }
                })
                .exceptionally(throwable -> {
                    this.sendError(context, "Failed to get server: " + throwable.getMessage());
                    return null;
                });
    }

    private void getGroups(RoutingContext context) {
        Set<Scaler> scalers = AtlasBase.getInstance().getScalerManager().getScalers();

        List<Map<String, Object>> groups = new ArrayList<>();

        for (Scaler scaler : scalers) {
            ScalerConfig.Group groupConfig = scaler.getScalerConfig().getGroup();
            List<AtlasServer> servers = scaler.getServers();

            int onlineServers = (int) servers.stream()
                    .filter(server -> server.getServerInfo() != null &&
                            server.getServerInfo().getStatus() == ServerStatus.RUNNING)
                    .count();

            int totalPlayers = servers.stream()
                    .filter(server -> server.getServerInfo() != null)
                    .mapToInt(server -> server.getServerInfo().getOnlinePlayers())
                    .sum();

            int totalCapacity = servers.stream()
                    .filter(server -> server.getServerInfo() != null &&
                            server.getServerInfo().getStatus() == ServerStatus.RUNNING)
                    .mapToInt(server -> server.getServerInfo().getMaxPlayers())
                    .sum();

            Map<String, Object> groupMap = new HashMap<>();
            groupMap.put("name", groupConfig.getName());
            groupMap.put("displayName", groupConfig.getDisplayName());
            groupMap.put("priority", groupConfig.getPriority());
            groupMap.put("type", groupConfig.getServer().getType());
            groupMap.put("scalerType", scaler.getClass().getSimpleName());
            groupMap.put("minServers", groupConfig.getServer().getMinServers());
            groupMap.put("maxServers", groupConfig.getServer().getMaxServers());
            groupMap.put("currentServers", servers.size());
            groupMap.put("onlineServers", onlineServers);
            groupMap.put("totalPlayers", totalPlayers);
            groupMap.put("totalCapacity", totalCapacity);
            groupMap.put("templates", groupConfig.getTemplates());

            if (groupConfig.getScaling() != null && groupConfig.getScaling().getConditions() != null) {
                Map<String, Object> scalingMap = new HashMap<>();
                scalingMap.put("type", groupConfig.getScaling().getType());
                scalingMap.put("scaleUpThreshold", groupConfig.getScaling().getConditions().getScaleUpThreshold());
                scalingMap.put("scaleDownThreshold", groupConfig.getScaling().getConditions().getScaleDownThreshold());
                groupMap.put("scaling", scalingMap);
            }

            groups.add(groupMap);
        }

        List<Map<String, Object>> sortedGroups = groups.stream()
                .sorted((a, b) -> Integer.compare((Integer) b.get("priority"), (Integer) a.get("priority")))
                .collect(Collectors.toList());

        this.sendResponse(context, ApiResponse.success(sortedGroups));
    }

    private void getGroup(RoutingContext context) {
        String groupName = context.pathParam("name");
        Set<Scaler> scalers = AtlasBase.getInstance().getScalerManager().getScalers();

        Scaler scaler = scalers.stream()
                .filter(s -> {
                    String name = s.getScalerConfig().getGroup().getName();
                    return name != null && name.equalsIgnoreCase(groupName);
                })
                .findFirst()
                .orElse(null);

        if (scaler == null) {
            this.sendError(context, "Group not found: " + groupName, 404);
            return;
        }

        ScalerConfig.Group groupConfig = scaler.getScalerConfig().getGroup();
        List<AtlasServer> servers = scaler.getServers();

        int onlineServers = (int) servers.stream()
                .filter(server -> server.getServerInfo() != null &&
                        server.getServerInfo().getStatus() == ServerStatus.RUNNING)
                .count();

        int totalPlayers = servers.stream()
                .filter(server -> server.getServerInfo() != null)
                .mapToInt(server -> server.getServerInfo().getOnlinePlayers())
                .sum();

        int totalCapacity = servers.stream()
                .filter(server -> server.getServerInfo() != null &&
                        server.getServerInfo().getStatus() == ServerStatus.RUNNING)
                .mapToInt(server -> server.getServerInfo().getMaxPlayers())
                .sum();

        Map<String, Object> groupMap = new HashMap<>();
        groupMap.put("name", groupConfig.getName());
        groupMap.put("displayName", groupConfig.getDisplayName());
        groupMap.put("priority", groupConfig.getPriority());
        groupMap.put("type", groupConfig.getServer().getType());
        groupMap.put("scalerType", scaler.getClass().getSimpleName());
        groupMap.put("minServers", groupConfig.getServer().getMinServers());
        groupMap.put("maxServers", groupConfig.getServer().getMaxServers());
        groupMap.put("currentServers", servers.size());
        groupMap.put("onlineServers", onlineServers);
        groupMap.put("totalPlayers", totalPlayers);
        groupMap.put("totalCapacity", totalCapacity);
        groupMap.put("templates", groupConfig.getTemplates());

        if (groupConfig.getScaling() != null && groupConfig.getScaling().getConditions() != null) {
            Map<String, Object> scalingMap = new HashMap<>();
            scalingMap.put("type", groupConfig.getScaling().getType());
            scalingMap.put("scaleUpThreshold", groupConfig.getScaling().getConditions().getScaleUpThreshold());
            scalingMap.put("scaleDownThreshold", groupConfig.getScaling().getConditions().getScaleDownThreshold());
            groupMap.put("scaling", scalingMap);
        }

        this.sendResponse(context, ApiResponse.success(groupMap));
    }

    private void getScaling(RoutingContext context) {
        Set<Scaler> scalers = AtlasBase.getInstance().getScalerManager().getScalers();

        Map<String, Object> scaling = new HashMap<>();
        for (Scaler scaler : scalers) {
            Map<String, Object> scalerInfo = new HashMap<>();
            scalerInfo.put("group", scaler.getScalerConfig().getGroup().getName());
            scalerInfo.put("type", scaler.getClass().getSimpleName());
            scaling.put(scaler.getGroupName(), scalerInfo);
        }

        this.sendResponse(context, ApiResponse.success(scaling));
    }

    private void getMetrics(RoutingContext context) {
        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();

        provider.getAllServers()
                .thenAccept(servers -> {
                    Map<String, Object> metrics = new HashMap<>();
                    metrics.put("totalServers", servers.size());
                    metrics.put("totalPlayers", servers.stream().mapToInt(server -> server.getServerInfo() != null ? server.getServerInfo().getOnlinePlayers() : 0).sum());
                    metrics.put("serversByStatus", servers.stream()
                            .collect(Collectors.groupingBy(
                                    server -> server.getServerInfo() != null ? server.getServerInfo().getStatus().toString() : "UNKNOWN",
                                    Collectors.counting())));

                    this.sendResponse(context, ApiResponse.success(metrics));
                })
                .exceptionally(throwable -> {
                    this.sendError(context, "Failed to get metrics: " + throwable.getMessage());
                    return null;
                });
    }

    private void createServers(RoutingContext context) {
        JsonObject body = context.body().asJsonObject();
        if (body == null) {
            this.sendError(context, "Request body is required", 400);
            return;
        }

        String group = body.getString("group");
        Integer count = body.getInteger("count", 1);

        if (group == null) {
            this.sendError(context, "Group is required", 400);
            return;
        }

        if (count <= 0) {
            this.sendError(context, "Count must be greater than 0", 400);
            return;
        }

        Scaler scaler = AtlasBase.getInstance().getScalerManager().getScaler(group);
        if (scaler == null) {
            this.sendError(context, "Group not found: " + group, 404);
            return;
        }

        List<CompletableFuture<Void>> createFutures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            createFutures.add(scaler.upscale());
        }

        CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                createFutures.toArray(new CompletableFuture[0]));

        allFutures
                .thenRun(() -> {
                    String message = count == 1 ? "Server creation initiated" : count + " server creations initiated";
                    this.sendResponse(context, ApiResponse.success(null, message));
                })
                .exceptionally(throwable -> {
                    this.sendError(context, "Failed to create servers: " + throwable.getMessage());
                    return null;
                });
    }

    private void startServer(RoutingContext context) {
        String serverId = context.pathParam("id");
        this.executeServerAction(context, serverId, "start",
                server -> AtlasBase.getInstance().getServerManager().startServer(server));
    }

    private void stopServer(RoutingContext context) {
        String serverId = context.pathParam("id");
        this.executeServerAction(context, serverId, "stop",
                server -> AtlasBase.getInstance().getServerManager().stopServer(server));
    }

    private void restartServer(RoutingContext context) {
        String serverId = context.pathParam("id");
        this.executeServerAction(context, serverId, "restart",
                server -> AtlasBase.getInstance().getServerManager().restartServer(server));
    }

    private void removeServer(RoutingContext context) {
        String serverId = context.pathParam("id");
        this.executeServerAction(context, serverId, "remove",
                server -> AtlasBase.getInstance().getServerManager().removeServer(server));
    }

    private void executeServerCommand(RoutingContext context) {
        String serverId = context.pathParam("id");
        JsonObject body = context.body().asJsonObject();

        if (body == null || !body.containsKey("command")) {
            this.sendError(context, "Missing required field: command", 400);
            return;
        }

        String command = body.getString("command");
        if (command == null || command.trim().isEmpty()) {
            this.sendError(context, "Command cannot be empty", 400);
            return;
        }

        AtlasBase.getInstance().getServerManager().sendCommand(serverId, command)
                .thenRun(() -> this.sendResponse(context, ApiResponse.success(null, "Command executed successfully")))
                .exceptionally(throwable -> {
                    this.sendError(context, "Failed to execute command: " + throwable.getMessage());
                    return null;
                });
    }

    private void generateWebSocketToken(RoutingContext context) {
        String serverId = context.pathParam("id");
        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();

        provider.getServer(serverId)
                .thenAccept(serverOpt -> {
                    if (serverOpt.isEmpty()) {
                        this.sendError(context, "Server not found: " + serverId, 404);
                        return;
                    }

                    WebSocketTokenManager tokenManager = this.authHandler.getTokenManager();
                    if (tokenManager == null) {
                        this.sendError(context, "Token generation not available", 500);
                        return;
                    }

                    try {
                        String authHeader = context.request().getHeader("Authorization");
                        String apiKey = this.authHandler.extractBearerToken(authHeader);

                        if (apiKey == null || !this.authHandler.isValidToken(apiKey)) {
                            this.sendError(context, "Invalid API key", 401);
                            return;
                        }

                        WebSocketTokenManager.TokenInfo tokenInfo = tokenManager.generateToken(apiKey, serverId);

                        Map<String, Object> response = new HashMap<>();
                        response.put("token", tokenInfo.getToken());
                        response.put("expiresAt", tokenInfo.getExpiresAt());

                        this.sendResponse(context, ApiResponse.success(response));
                    } catch (SecurityException e) {
                        this.sendError(context, e.getMessage(), 429);
                    } catch (Exception e) {
                        Logger.error("Failed to generate WebSocket token", e);
                        this.sendError(context, "Failed to generate token: " + e.getMessage());
                    }
                })
                .exceptionally(throwable -> {
                    this.sendError(context, "Failed to check server: " + throwable.getMessage());
                    return null;
                });
    }

    private void getServerCount(RoutingContext context) {
        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();

        provider.getAllServers()
                .thenAccept(servers -> {
                    Map<String, Object> count = new HashMap<>();
                    count.put("total", servers.size());
                    count.put("byStatus", servers.stream()
                            .filter(server -> server.getServerInfo() != null)
                            .collect(Collectors.groupingBy(
                                    server -> server.getServerInfo().getStatus().toString(),
                                    Collectors.counting())));
                    count.put("byGroup", servers.stream()
                            .collect(Collectors.groupingBy(
                                    AtlasServer::getGroup,
                                    Collectors.counting())));

                    this.sendResponse(context, ApiResponse.success(count));
                })
                .exceptionally(throwable -> {
                    this.sendError(context, "Failed to get server count: " + throwable.getMessage());
                    return null;
                });
    }

    private void getPlayerCount(RoutingContext context) {
        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();

        provider.getAllServers()
                .thenAccept(servers -> {
                    int totalPlayers = servers.stream()
                            .filter(server -> server.getServerInfo() != null)
                            .filter(server -> server.getGroup().equalsIgnoreCase("proxy"))
                            .mapToInt(server -> server.getServerInfo().getOnlinePlayers())
                            .sum();

                    int totalCapacity = servers.stream()
                            .filter(server -> server.getServerInfo() != null)
                            .filter(server -> server.getGroup().equalsIgnoreCase("proxy"))
                            .mapToInt(server -> server.getServerInfo().getMaxPlayers())
                            .sum();

                    Map<String, Integer> playersByGroup = servers.stream()
                            .filter(server -> server.getServerInfo() != null)
                            .filter(server -> server.getGroup().equalsIgnoreCase("proxy"))
                            .collect(Collectors.groupingBy(
                                    AtlasServer::getGroup,
                                    Collectors.summingInt(server -> server.getServerInfo().getOnlinePlayers())));

                    Map<String, Integer> playersByStatus = servers.stream()
                            .filter(server -> server.getServerInfo() != null)
                            .filter(server -> server.getGroup().equalsIgnoreCase("proxy"))
                            .collect(Collectors.groupingBy(
                                    server -> server.getServerInfo().getStatus().toString(),
                                    Collectors.summingInt(server -> server.getServerInfo().getOnlinePlayers())));

                    Map<String, Object> playerCount = new HashMap<>();
                    playerCount.put("total", totalPlayers);
                    playerCount.put("capacity", totalCapacity);
                    playerCount.put("percentage", totalCapacity > 0 ? (double) totalPlayers / totalCapacity * 100 : 0);
                    playerCount.put("byGroup", playersByGroup);
                    playerCount.put("byStatus", playersByStatus);

                    this.sendResponse(context, ApiResponse.success(playerCount));
                })
                .exceptionally(throwable -> {
                    this.sendError(context, "Failed to get player count: " + throwable.getMessage());
                    return null;
                });
    }

    private void getUtilization(RoutingContext context) {
        try {
            OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            DecimalFormat df = new DecimalFormat("#.##");

            double cpuLoad = osBean.getCpuLoad() * 100;
            if (cpuLoad < 0) cpuLoad = 0;
            int availableProcessors = osBean.getAvailableProcessors();

            long[] memoryInfo = this.getMemoryInfo();
            long totalMemory = memoryInfo[0];
            long usedMemory = memoryInfo[1];
            double memoryPercentage = totalMemory > 0 ? (double) usedMemory / totalMemory * 100 : 0;

            File root = new File("/");
            long totalDisk = root.getTotalSpace();
            long freeDisk = root.getFreeSpace();
            long usedDisk = totalDisk - freeDisk;
            double diskPercentage = (double) usedDisk / totalDisk * 100;

            NetworkBandwidthMonitor.BandwidthStats bandwidthStats = null;
            if (AtlasBase.getInstance().getNetworkBandwidthMonitor() != null) {
                bandwidthStats = AtlasBase.getInstance().getNetworkBandwidthMonitor().getCurrentStats();
            }

            long bandwidthCapacity = bandwidthStats != null ? bandwidthStats.maxBps : 1024 * 1024 * 1024; // 1 Gbps default
            long bandwidthUsed = bandwidthStats != null ? (long) bandwidthStats.usedBps : 0;
            double bandwidthPercentage = bandwidthStats != null ? bandwidthStats.getPercentage() : 0.0;

            Map<String, Object> cpuMap = new HashMap<>();
            cpuMap.put("cores", availableProcessors);
            cpuMap.put("usage", Double.parseDouble(df.format(cpuLoad)));
            cpuMap.put("formatted", df.format(cpuLoad) + "%");

            Map<String, Object> memoryMap = new HashMap<>();
            memoryMap.put("used", usedMemory);
            memoryMap.put("total", totalMemory);
            memoryMap.put("percentage", Double.parseDouble(df.format(memoryPercentage)));
            memoryMap.put("usedFormatted", this.formatBytes(usedMemory));
            memoryMap.put("totalFormatted", this.formatBytes(totalMemory));

            Map<String, Object> diskMap = new HashMap<>();
            diskMap.put("used", usedDisk);
            diskMap.put("total", totalDisk);
            diskMap.put("percentage", Double.parseDouble(df.format(diskPercentage)));
            diskMap.put("usedFormatted", this.formatBytes(usedDisk));
            diskMap.put("totalFormatted", this.formatBytes(totalDisk));

            Map<String, Object> bandwidthMap = new HashMap<>();
            bandwidthMap.put("used", bandwidthUsed);
            bandwidthMap.put("total", bandwidthCapacity);
            bandwidthMap.put("percentage", bandwidthPercentage);
            bandwidthMap.put("receiveRate", bandwidthStats != null ? bandwidthStats.receiveBps : 0);
            bandwidthMap.put("sendRate", bandwidthStats != null ? bandwidthStats.sendBps : 0);
            bandwidthMap.put("usedFormatted", this.formatBytes(bandwidthUsed) + "/s");
            bandwidthMap.put("totalFormatted", this.formatBytes(bandwidthCapacity) + "/s");
            bandwidthMap.put("receiveFormatted", this.formatBytes(bandwidthStats != null ? (long) bandwidthStats.receiveBps : 0) + "/s");
            bandwidthMap.put("sendFormatted", this.formatBytes(bandwidthStats != null ? (long) bandwidthStats.sendBps : 0) + "/s");

            Map<String, Object> utilization = new HashMap<>();
            utilization.put("cpu", cpuMap);
            utilization.put("memory", memoryMap);
            utilization.put("disk", diskMap);
            utilization.put("bandwidth", bandwidthMap);

            this.sendResponse(context, ApiResponse.success(utilization));
        } catch (Exception e) {
            Logger.error("Failed to get system utilization", e);
            this.sendError(context, "Failed to get system utilization: " + e.getMessage());
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    private long[] getMemoryInfo() {
        try {
            java.nio.file.Path meminfoPath = java.nio.file.Paths.get("/proc/meminfo");
            if (!java.nio.file.Files.exists(meminfoPath)) {
                // Fallback to Java API if /proc/meminfo doesn't exist
                OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
                long totalMemory = osBean.getTotalMemorySize();
                long freeMemory = osBean.getFreeMemorySize();
                return new long[]{totalMemory, totalMemory - freeMemory};
            }

            java.util.List<String> lines = java.nio.file.Files.readAllLines(meminfoPath);

            long memTotal = 0;
            long memFree = 0;
            long buffers = 0;
            long cached = 0;

            for (String line : lines) {
                if (line.startsWith("MemTotal:")) {
                    memTotal = Long.parseLong(line.split("\\s+")[1]) * 1024; // Convert KB to bytes
                } else if (line.startsWith("MemFree:")) {
                    memFree = Long.parseLong(line.split("\\s+")[1]) * 1024;
                } else if (line.startsWith("Buffers:")) {
                    buffers = Long.parseLong(line.split("\\s+")[1]) * 1024;
                } else if (line.startsWith("Cached:")) {
                    cached = Long.parseLong(line.split("\\s+")[1]) * 1024;
                }
            }

            // Calculate used memory like free -m does: Total - Free - Buffers - Cached
            long usedMemory = memTotal - memFree - buffers - cached;

            return new long[]{memTotal, usedMemory};
        } catch (Exception e) {
            Logger.debug("Failed to read /proc/meminfo, falling back to Java API: " + e.getMessage());
            // Fallback to Java API
            OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            long totalMemory = osBean.getTotalMemorySize();
            long freeMemory = osBean.getFreeMemorySize();
            return new long[]{totalMemory, totalMemory - freeMemory};
        }
    }

    private void scaleGroup(RoutingContext context) {
        String group = context.pathParam("group");
        JsonObject body = context.body().asJsonObject();

        if (body == null) {
            this.sendError(context, "Request body is required", 400);
            return;
        }

        String direction = body.getString("direction");
        if (!direction.equals("up") && !direction.equals("down")) {
            this.sendError(context, "Direction must be 'up' or 'down'", 400);
            return;
        }

        Scaler scaler = AtlasBase.getInstance().getScalerManager().getScaler(group);
        if (scaler == null) {
            this.sendError(context, "Group not found: " + group, 404);
            return;
        }

        if (direction.equals("up")) {
            scaler.upscale()
                    .thenRun(() -> this.sendResponse(context, ApiResponse.success(null, "Upscale initiated")))
                    .exceptionally(throwable -> {
                        this.sendError(context, "Failed to upscale: " + throwable.getMessage());
                        return null;
                    });
        } else {
            scaler.triggerScaleDown()
                    .thenRun(() -> this.sendResponse(context, ApiResponse.success(null, "Downscale initiated")))
                    .exceptionally(throwable -> {
                        this.sendError(context, "Failed to downscale: " + throwable.getMessage());
                        return null;
                    });
        }
    }

    private void executeServerAction(RoutingContext context, String serverId, String action,
                                     java.util.function.Function<AtlasServer, CompletableFuture<Void>> serverAction) {
        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();

        provider.getServer(serverId)
                .thenAccept(serverOpt -> {
                    if (serverOpt.isEmpty()) {
                        this.sendError(context, "Server not found: " + serverId, 404);
                        return;
                    }

                    AtlasServer server = serverOpt.get();
                    serverAction.apply(server)
                            .thenRun(() -> this.sendResponse(context, ApiResponse.success(null, "Server " + action + " initiated")))
                            .exceptionally(throwable -> {
                                this.sendError(context, "Failed to " + action + " server: " + throwable.getMessage());
                                return null;
                            });
                })
                .exceptionally(throwable -> {
                    this.sendError(context, "Failed to find server: " + throwable.getMessage());
                    return null;
                });
    }

    private void sendResponse(RoutingContext context, ApiResponse<?> response) {
        try {
            String json = this.objectMapper.writeValueAsString(response);
            context.response()
                    .putHeader("Content-Type", "application/json")
                    .end(json);
        } catch (Exception e) {
            Logger.error("Failed to serialize response", e);
            this.sendError(context, "Internal server error");
        }
    }

    private void sendError(RoutingContext context, String message) {
        this.sendError(context, message, 500);
    }

    private void sendError(RoutingContext context, String message, int statusCode) {
        try {
            ApiResponse<Object> error = ApiResponse.error(message);
            String json = this.objectMapper.writeValueAsString(error);
            context.response()
                    .setStatusCode(statusCode)
                    .putHeader("Content-Type", "application/json")
                    .end(json);
        } catch (Exception e) {
            Logger.error("Failed to serialize error response", e);
            context.response()
                    .setStatusCode(500)
                    .end("{\"status\":\"error\",\"message\":\"Internal server error\"}");
        }
    }

    private void getServerLogs(RoutingContext context) {
        String serverId = context.pathParam("id");
        String linesParam = context.request().getParam("lines");
        int lines = -1;

        if (linesParam != null) {
            try {
                lines = Integer.parseInt(linesParam);
            } catch (NumberFormatException e) {
                this.sendError(context, "Invalid lines parameter: " + linesParam, 400);
                return;
            }
        }

        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();
        CompletableFuture<Optional<AtlasServer>> serverFuture = provider.getServer(serverId);

        int finalLines = lines;
        serverFuture.thenAccept(serverOpt -> {
                    if (serverOpt.isEmpty()) {
                        this.sendError(context, "Server not found: " + serverId, 404);
                        return;
                    }

                    if (serverOpt.get().getServerInfo() == null || serverOpt.get().getServerInfo().getStatus() == ServerStatus.STOPPED) {
                        Map<String, Object> response = new HashMap<>();
                        response.put("serverId", serverId);
                        response.put("lines", 0);
                        response.put("logs", Collections.emptyList());
                        this.sendResponse(context, ApiResponse.success(response, "Server logs retrieved"));
                        return;
                    }

                    provider.getServerLogs(serverId, finalLines)
                            .thenAccept(logLines -> {
                                Map<String, Object> response = new HashMap<>();
                                response.put("serverId", serverId);
                                response.put("lines", logLines.size());
                                response.put("logs", logLines);
                                this.sendResponse(context, ApiResponse.success(response, "Server logs retrieved"));
                            })
                            .exceptionally(throwable -> {
                                this.sendError(context, "Failed to get server logs: " + throwable.getMessage());
                                return null;
                            });
                })
                .exceptionally(throwable -> {
                    this.sendError(context, "Failed to find server: " + throwable.getMessage());
                    return null;
                });
    }

    private void getServerFiles(RoutingContext context) {
        String serverId = context.pathParam("id");
        String path = context.request().getParam("path");

        if (path == null || path.isEmpty()) {
            path = "/";
        }

        final String finalPath = path;

        if (!this.fileManager.isValidPath(finalPath)) {
            this.sendError(context, "Invalid path: directory traversal not allowed", 400);
            return;
        }

        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();

        provider.getServer(serverId)
                .thenAccept(serverOpt -> {
                    if (serverOpt.isEmpty()) {
                        this.sendError(context, "Server not found: " + serverId, 404);
                        return;
                    }

                    AtlasServer server = serverOpt.get();
                    String workingDirectory = server.getWorkingDirectory();

                    if (workingDirectory == null || workingDirectory.isEmpty()) {
                        this.sendError(context, "Server working directory not set: " + serverId, 500);
                        return;
                    }

                    try {
                        FileListResponse response = this.fileManager.listFiles(workingDirectory, finalPath);
                        this.sendResponse(context, ApiResponse.success(response));
                    } catch (SecurityException e) {
                        Logger.warn("Security violation: attempted directory traversal for server {} at path {}: {}", serverId, finalPath, e.getMessage());
                        this.sendError(context, "Security violation: " + e.getMessage(), 403);
                    } catch (Exception e) {
                        Logger.error("Failed to list server files for " + serverId + " at path " + finalPath, e);
                        this.sendError(context, "Failed to list server files: " + e.getMessage());
                    }
                })
                .exceptionally(throwable -> {
                    this.sendError(context, "Failed to find server: " + throwable.getMessage());
                    return null;
                });
    }

    private void getFileContents(RoutingContext context) {
        String serverId = context.pathParam("id");
        String filePath = context.request().getParam("file");

        if (filePath == null || filePath.isEmpty()) {
            this.sendError(context, "File parameter is required", 400);
            return;
        }

        if (!this.fileManager.isValidPath(filePath)) {
            this.sendError(context, "Invalid file path: directory traversal not allowed", 400);
            return;
        }

        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();

        provider.getServer(serverId)
                .thenAccept(serverOpt -> {
                    if (serverOpt.isEmpty()) {
                        this.sendError(context, "Server not found: " + serverId, 404);
                        return;
                    }

                    AtlasServer server = serverOpt.get();
                    String workingDirectory = server.getWorkingDirectory();

                    if (workingDirectory == null || workingDirectory.isEmpty()) {
                        this.sendError(context, "Server working directory not set: " + serverId, 500);
                        return;
                    }

                    try {
                        String fileContents = this.fileManager.readFileContents(workingDirectory, filePath);

                        context.response()
                                .putHeader("Content-Type", "text/plain; charset=utf-8")
                                .end(fileContents);

                    } catch (SecurityException e) {
                        Logger.warn("Security violation: attempted file access outside server directory for server {} at path {}: {}", serverId, filePath, e.getMessage());
                        this.sendError(context, "Security violation: " + e.getMessage(), 403);
                    } catch (Exception e) {
                        Logger.error("Failed to read file contents for server {} at path {}", serverId, filePath, e);
                        this.sendError(context, "Failed to read file: " + e.getMessage());
                    }
                })
                .exceptionally(throwable -> {
                    this.sendError(context, "Failed to find server: " + throwable.getMessage());
                    return null;
                });
    }

    private void writeFileContents(RoutingContext context) {
        String serverId = context.pathParam("id");
        String filePath = context.request().getParam("file");

        if (filePath == null || filePath.isEmpty()) {
            this.sendError(context, "File parameter is required", 400);
            return;
        }

        if (!this.fileManager.isValidPath(filePath)) {
            this.sendError(context, "Invalid file path: directory traversal not allowed", 400);
            return;
        }

        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();

        provider.getServer(serverId)
                .thenAccept(serverOpt -> {
                    if (serverOpt.isEmpty()) {
                        this.sendError(context, "Server not found: " + serverId, 404);
                        return;
                    }

                    AtlasServer server = serverOpt.get();
                    String workingDirectory = server.getWorkingDirectory();

                    if (workingDirectory == null || workingDirectory.isEmpty()) {
                        this.sendError(context, "Server working directory not set: " + serverId, 500);
                        return;
                    }

                    String content = context.body().asString();
                    if (content == null) {
                        content = "";
                    }

                    try {
                        this.fileManager.writeFileContents(workingDirectory, filePath, content);
                        this.sendResponse(context, ApiResponse.success(null, "File written successfully"));

                    } catch (SecurityException e) {
                        Logger.warn("Security violation: attempted file write outside server directory for server {} at path {}: {}", serverId, filePath, e.getMessage());
                        this.sendError(context, "Security violation: " + e.getMessage(), 403);
                    } catch (Exception e) {
                        Logger.error("Failed to write file contents for server {} at path {}", serverId, filePath, e);
                        this.sendError(context, "Failed to write file: " + e.getMessage());
                    }
                })
                .exceptionally(throwable -> {
                    this.sendError(context, "Failed to find server: " + throwable.getMessage());
                    return null;
                });
    }

    private void deleteFile(RoutingContext context) {
        String serverId = context.pathParam("id");
        String filePath = context.request().getParam("file");

        if (filePath == null || filePath.isEmpty()) {
            this.sendError(context, "File parameter is required", 400);
            return;
        }

        if (!this.fileManager.isValidPath(filePath)) {
            this.sendError(context, "Invalid file path: directory traversal not allowed", 400);
            return;
        }

        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();

        provider.getServer(serverId)
                .thenAccept(serverOpt -> {
                    if (serverOpt.isEmpty()) {
                        this.sendError(context, "Server not found: " + serverId, 404);
                        return;
                    }

                    AtlasServer server = serverOpt.get();
                    String workingDirectory = server.getWorkingDirectory();

                    if (workingDirectory == null || workingDirectory.isEmpty()) {
                        this.sendError(context, "Server working directory not set: " + serverId, 500);
                        return;
                    }

                    try {
                        this.fileManager.deleteFile(workingDirectory, filePath);
                        this.sendResponse(context, ApiResponse.success(null, "File deleted successfully"));

                    } catch (SecurityException e) {
                        Logger.warn("Security violation: attempted file deletion outside server directory for server {} at path {}: {}", serverId, filePath, e.getMessage());
                        this.sendError(context, "Security violation: " + e.getMessage(), 403);
                    } catch (Exception e) {
                        Logger.error("Failed to delete file for server {} at path {}", serverId, filePath, e);
                        this.sendError(context, "Failed to delete file: " + e.getMessage());
                    }
                })
                .exceptionally(throwable -> {
                    this.sendError(context, "Failed to find server: " + throwable.getMessage());
                    return null;
                });
    }

    private void downloadFile(RoutingContext context) {
        String serverId = context.pathParam("id");
        String filePath = context.request().getParam("file");

        if (filePath == null || filePath.isEmpty()) {
            this.sendError(context, "File parameter is required", 400);
            return;
        }

        if (!this.fileManager.isValidPath(filePath)) {
            this.sendError(context, "Invalid file path: directory traversal not allowed", 400);
            return;
        }

        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();

        provider.getServer(serverId)
                .thenAccept(serverOpt -> {
                    if (serverOpt.isEmpty()) {
                        this.sendError(context, "Server not found: " + serverId, 404);
                        return;
                    }

                    AtlasServer server = serverOpt.get();
                    String workingDirectory = server.getWorkingDirectory();

                    if (workingDirectory == null || workingDirectory.isEmpty()) {
                        this.sendError(context, "Server working directory not set: " + serverId, 500);
                        return;
                    }

                    try {
                        this.fileManager.downloadFile(context, workingDirectory, filePath);

                    } catch (SecurityException e) {
                        Logger.warn("Security violation: attempted file download outside server directory for server {} at path {}: {}", serverId, filePath, e.getMessage());
                        this.sendError(context, "Security violation: " + e.getMessage(), 403);
                    } catch (Exception e) {
                        Logger.error("Failed to download file for server {} at path {}", serverId, filePath, e);
                        this.sendError(context, "Failed to download file: " + e.getMessage());
                    }
                })
                .exceptionally(throwable -> {
                    this.sendError(context, "Failed to find server: " + throwable.getMessage());
                    return null;
                });
    }

    private void renameFile(RoutingContext context) {
        String serverId = context.pathParam("id");
        JsonObject body = context.body().asJsonObject();

        if (body == null) {
            this.sendError(context, "Request body is required", 400);
            return;
        }

        String oldPath = body.getString("oldPath");
        String newPath = body.getString("newPath");

        if (oldPath == null || oldPath.isEmpty()) {
            this.sendError(context, "oldPath is required", 400);
            return;
        }

        if (newPath == null || newPath.isEmpty()) {
            this.sendError(context, "newPath is required", 400);
            return;
        }

        if (!this.fileManager.isValidPath(oldPath) || !this.fileManager.isValidPath(newPath)) {
            this.sendError(context, "Invalid file path: directory traversal not allowed", 400);
            return;
        }

        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();

        provider.getServer(serverId)
                .thenAccept(serverOpt -> {
                    if (serverOpt.isEmpty()) {
                        this.sendError(context, "Server not found: " + serverId, 404);
                        return;
                    }

                    AtlasServer server = serverOpt.get();
                    String workingDirectory = server.getWorkingDirectory();

                    if (workingDirectory == null || workingDirectory.isEmpty()) {
                        this.sendError(context, "Server working directory not set: " + serverId, 500);
                        return;
                    }

                    try {
                        this.fileManager.renameFile(workingDirectory, oldPath, newPath);
                        this.sendResponse(context, ApiResponse.success(null, "File renamed successfully"));

                    } catch (SecurityException e) {
                        Logger.warn("Security violation: attempted file rename outside server directory for server {} from {} to {}: {}", serverId, oldPath, newPath, e.getMessage());
                        this.sendError(context, "Security violation: " + e.getMessage(), 403);
                    } catch (Exception e) {
                        Logger.error("Failed to rename file for server {} from {} to {}", serverId, oldPath, newPath, e);
                        this.sendError(context, "Failed to rename file: " + e.getMessage());
                    }
                })
                .exceptionally(throwable -> {
                    this.sendError(context, "Failed to find server: " + throwable.getMessage());
                    return null;
                });
    }

    private void uploadFileWithAuth(RoutingContext context) {
        String authHeader = context.request().getHeader("Authorization");
        String token = this.authHandler.extractBearerToken(authHeader);

        if (!this.authHandler.isValidToken(token)) {
            this.sendError(context, "Unauthorized: Invalid or missing API key", 401);
            return;
        }

        this.uploadFile(context);
    }

    private void uploadFile(RoutingContext context) {
        String serverId = context.pathParam("id");
        String targetPath = context.request().getParam("path");

        if (targetPath == null || targetPath.isEmpty()) {
            this.sendError(context, "Path parameter is required", 400);
            return;
        }

        if (!this.fileManager.isValidPath(targetPath)) {
            this.sendError(context, "Invalid file path: directory traversal not allowed", 400);
            return;
        }

        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();

        provider.getServer(serverId)
                .thenAccept(serverOpt -> {
                    if (serverOpt.isEmpty()) {
                        this.sendError(context, "Server not found: " + serverId, 404);
                        return;
                    }

                    AtlasServer server = serverOpt.get();
                    String workingDirectory = server.getWorkingDirectory();

                    if (workingDirectory == null || workingDirectory.isEmpty()) {
                        this.sendError(context, "Server working directory not set: " + serverId, 500);
                        return;
                    }

                    try {
                        this.fileManager.uploadFileStream(workingDirectory, targetPath, context.request())
                                .thenAccept(fileSize -> {
                                    Map<String, Object> response = new HashMap<>();
                                    response.put("path", targetPath);
                                    response.put("size", fileSize);

                                    this.sendResponse(context, ApiResponse.success(response, "File uploaded successfully"));
                                })
                                .exceptionally(throwable -> {
                                    if (throwable.getCause() instanceof SecurityException) {
                                        Logger.warn("Security violation: attempted file upload outside server directory for server {} at path {}: {}", serverId, targetPath, throwable.getMessage());
                                        this.sendError(context, "Security violation: " + throwable.getCause().getMessage(), 403);
                                    } else {
                                        Logger.error("Failed to upload file for server {} at path {}", serverId, targetPath, throwable);
                                        this.sendError(context, "Failed to upload file: " + throwable.getMessage());
                                    }
                                    return null;
                                });

                    } catch (SecurityException e) {
                        Logger.warn("Security violation: attempted file upload outside server directory for server {} at path {}: {}", serverId, targetPath, e.getMessage());
                        this.sendError(context, "Security violation: " + e.getMessage(), 403);
                    } catch (Exception e) {
                        Logger.error("Failed to upload file for server {} at path {}", serverId, targetPath, e);
                        this.sendError(context, "Failed to upload file: " + e.getMessage());
                    }
                })
                .exceptionally(throwable -> {
                    this.sendError(context, "Failed to find server: " + throwable.getMessage());
                    return null;
                });
    }

    private void createDirectory(RoutingContext context) {
        String serverId = context.pathParam("id");
        JsonObject body = context.body().asJsonObject();

        if (body == null) {
            this.sendError(context, "Request body is required", 400);
            return;
        }

        String directoryPath = body.getString("path");

        if (directoryPath == null || directoryPath.isEmpty()) {
            this.sendError(context, "Path is required", 400);
            return;
        }

        if (!this.fileManager.isValidPath(directoryPath)) {
            this.sendError(context, "Invalid directory path: directory traversal not allowed", 400);
            return;
        }

        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();

        provider.getServer(serverId)
                .thenAccept(serverOpt -> {
                    if (serverOpt.isEmpty()) {
                        this.sendError(context, "Server not found: " + serverId, 404);
                        return;
                    }

                    AtlasServer server = serverOpt.get();
                    String workingDirectory = server.getWorkingDirectory();

                    if (workingDirectory == null || workingDirectory.isEmpty()) {
                        this.sendError(context, "Server working directory not set: " + serverId, 500);
                        return;
                    }

                    try {
                        this.fileManager.createDirectory(workingDirectory, directoryPath);

                        Map<String, Object> response = new HashMap<>();
                        response.put("path", directoryPath);

                        this.sendResponse(context, ApiResponse.success(response, "Directory created successfully"));

                    } catch (SecurityException e) {
                        Logger.warn("Security violation: attempted directory creation outside server directory for server {} at path {}: {}", serverId, directoryPath, e.getMessage());
                        this.sendError(context, "Security violation: " + e.getMessage(), 403);
                    } catch (Exception e) {
                        Logger.error("Failed to create directory for server {} at path {}", serverId, directoryPath, e);
                        this.sendError(context, "Failed to create directory: " + e.getMessage());
                    }
                })
                .exceptionally(throwable -> {
                    this.sendError(context, "Failed to find server: " + throwable.getMessage());
                    return null;
                });
    }

    private void startChunkedUpload(RoutingContext context) {
        String serverId = context.pathParam("id");
        JsonObject body = context.body().asJsonObject();

        if (body == null) {
            this.sendError(context, "Request body is required", 400);
            return;
        }

        String targetPath = body.getString("path");
        Long totalSize = body.getLong("totalSize");
        Integer chunkSize = body.getInteger("chunkSize");

        if (targetPath == null || targetPath.isEmpty()) {
            this.sendError(context, "Path is required", 400);
            return;
        }

        if (totalSize == null || totalSize <= 0) {
            this.sendError(context, "totalSize is required and must be positive", 400);
            return;
        }

        if (totalSize > 8L * 1024 * 1024 * 1024) { // 8GB limit
            this.sendError(context, "File size exceeds maximum limit of 8GB", 413);
            return;
        }

        if (!this.fileManager.isValidPath(targetPath)) {
            this.sendError(context, "Invalid file path: directory traversal not allowed", 400);
            return;
        }

        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();

        provider.getServer(serverId)
                .thenAccept(serverOpt -> {
                    if (serverOpt.isEmpty()) {
                        this.sendError(context, "Server not found: " + serverId, 404);
                        return;
                    }

                    AtlasServer server = serverOpt.get();
                    String workingDirectory = server.getWorkingDirectory();

                    if (workingDirectory == null || workingDirectory.isEmpty()) {
                        this.sendError(context, "Server working directory not set: " + serverId, 500);
                        return;
                    }

                    try {
                        UploadSession session = this.fileManager.startChunkedUpload(workingDirectory, targetPath, totalSize, chunkSize);

                        Map<String, Object> response = new HashMap<>();
                        response.put("uploadId", session.getUploadId());
                        response.put("chunkSize", session.getChunkSize());
                        response.put("totalChunks", session.getTotalChunks());
                        response.put("path", targetPath);

                        this.sendResponse(context, ApiResponse.success(response, "Upload session started"));

                    } catch (SecurityException e) {
                        Logger.warn("Security violation: attempted upload start outside server directory for server {} at path {}: {}", serverId, targetPath, e.getMessage());
                        this.sendError(context, "Security violation: " + e.getMessage(), 403);
                    } catch (Exception e) {
                        Logger.error("Failed to start upload session for server {} at path {}", serverId, targetPath, e);
                        this.sendError(context, "Failed to start upload session: " + e.getMessage());
                    }
                })
                .exceptionally(throwable -> {
                    this.sendError(context, "Failed to find server: " + throwable.getMessage());
                    return null;
                });
    }

    private void uploadChunkWithAuth(RoutingContext context) {
        String authHeader = context.request().getHeader("Authorization");
        String token = this.authHandler.extractBearerToken(authHeader);

        if (!this.authHandler.isValidToken(token)) {
            this.sendError(context, "Unauthorized: Invalid or missing API key", 401);
            return;
        }

        this.uploadChunk(context);
    }

    private void uploadChunk(RoutingContext context) {
        String uploadId = context.pathParam("uploadId");
        String chunkNumberStr = context.pathParam("chunkNumber");

        if (uploadId == null || uploadId.isEmpty()) {
            this.sendError(context, "Upload ID is required", 400);
            return;
        }

        int chunkNumber;
        try {
            chunkNumber = Integer.parseInt(chunkNumberStr);
        } catch (NumberFormatException e) {
            this.sendError(context, "Invalid chunk number: " + chunkNumberStr, 400);
            return;
        }

        UploadSession session = this.fileManager.getUploadSession(uploadId);
        if (session == null) {
            this.sendError(context, "Upload session not found: " + uploadId, 404);
            return;
        }

        context.request().bodyHandler(buffer -> {
            if (buffer == null || buffer.length() == 0) {
                this.sendError(context, "No chunk data provided", 400);
                return;
            }

            byte[] chunkData = buffer.getBytes();

            try {
                this.fileManager.uploadChunk(uploadId, chunkNumber, chunkData);

                Map<String, Object> response = new HashMap<>();
                response.put("uploadId", uploadId);
                response.put("chunkNumber", chunkNumber);
                response.put("receivedChunks", session.getReceivedChunks().size());
                response.put("totalChunks", session.getTotalChunks());
                response.put("progress", session.getProgress());
                response.put("isComplete", session.isComplete());

                this.sendResponse(context, ApiResponse.success(response, "Chunk uploaded successfully"));

            } catch (Exception e) {
                Logger.error("Failed to upload chunk {} for session {}", chunkNumber, uploadId, e);
                this.sendError(context, "Failed to upload chunk: " + e.getMessage());
            }
        });

        context.request().exceptionHandler(throwable -> {
            Logger.error("Failed to read chunk data for session {}", uploadId, throwable);
            this.sendError(context, "Failed to read chunk data: " + throwable.getMessage());
        });
    }

    private void completeChunkedUpload(RoutingContext context) {
        String uploadId = context.pathParam("uploadId");

        if (uploadId == null || uploadId.isEmpty()) {
            this.sendError(context, "Upload ID is required", 400);
            return;
        }

        UploadSession session = this.fileManager.getUploadSession(uploadId);
        if (session == null) {
            this.sendError(context, "Upload session not found: " + uploadId, 404);
            return;
        }

        try {
            long finalSize = this.fileManager.completeChunkedUpload(uploadId);

            Map<String, Object> response = new HashMap<>();
            response.put("uploadId", uploadId);
            response.put("path", session.getTargetPath());
            response.put("size", finalSize);
            response.put("totalChunks", session.getTotalChunks());

            this.sendResponse(context, ApiResponse.success(response, "Upload completed successfully"));

        } catch (Exception e) {
            Logger.error("Failed to complete upload session {}", uploadId, e);
            this.sendError(context, "Failed to complete upload: " + e.getMessage());
        }
    }

    private void getRecentActivity(RoutingContext context) {
        try {
            ActivityService activityService = AtlasBase.getInstance().getActivityService();
            if (activityService == null) {
                this.sendError(context, "Activity service not available", 503);
                return;
            }

            String limitParam = context.request().getParam("limit");
            String groupParam = context.request().getParam("group");
            String serverParam = context.request().getParam("server");
            String typeParam = context.request().getParam("type");

            int limit = 50; // default
            if (limitParam != null) {
                try {
                    limit = Integer.parseInt(limitParam);
                    limit = Math.min(Math.max(limit, 1), 500); // between 1 and 500
                } catch (NumberFormatException e) {
                    this.sendError(context, "Invalid limit parameter: " + limitParam, 400);
                    return;
                }
            }

            ActivityType activityType = null;
            if (typeParam != null && !typeParam.trim().isEmpty()) {
                try {
                    activityType = ActivityType.valueOf(typeParam.toUpperCase());
                } catch (IllegalArgumentException e) {
                    this.sendError(context, "Invalid activity type: " + typeParam, 400);
                    return;
                }
            }

            List<ServerActivity> activities;
            if (groupParam != null || serverParam != null || activityType != null) {
                activities = activityService.getFilteredActivities(serverParam, groupParam, activityType, limit);
            } else {
                activities = activityService.getRecentActivities(limit);
            }

            List<ActivityResponse> response = activities.stream()
                .map(this::mapActivityToResponse)
                .collect(Collectors.toList());

            this.sendResponse(context, ApiResponse.success(response));

        } catch (Exception e) {
            Logger.error("Failed to get recent activities", e);
            this.sendError(context, "Failed to get activities: " + e.getMessage());
        }
    }

    private void getServerActivity(RoutingContext context) {
        try {
            ActivityService activityService = AtlasBase.getInstance().getActivityService();
            if (activityService == null) {
                this.sendError(context, "Activity service not available", 503);
                return;
            }

            String serverId = context.pathParam("id");
            String limitParam = context.request().getParam("limit");

            int limit = 50; // default
            if (limitParam != null) {
                try {
                    limit = Integer.parseInt(limitParam);
                    limit = Math.min(Math.max(limit, 1), 200); // between 1 and 200
                } catch (NumberFormatException e) {
                    this.sendError(context, "Invalid limit parameter: " + limitParam, 400);
                    return;
                }
            }

            List<ServerActivity> activities = activityService.getActivitiesByServer(serverId, limit);
            List<ActivityResponse> response = activities.stream()
                .map(this::mapActivityToResponse)
                .collect(Collectors.toList());

            this.sendResponse(context, ApiResponse.success(response));

        } catch (Exception e) {
            Logger.error("Failed to get server activities for {}", context.pathParam("id"), e);
            this.sendError(context, "Failed to get server activities: " + e.getMessage());
        }
    }

    private void getGroupActivity(RoutingContext context) {
        try {
            ActivityService activityService = AtlasBase.getInstance().getActivityService();
            if (activityService == null) {
                this.sendError(context, "Activity service not available", 503);
                return;
            }

            String groupName = context.pathParam("name");
            String limitParam = context.request().getParam("limit");

            int limit = 50;
            if (limitParam != null) {
                try {
                    limit = Integer.parseInt(limitParam);
                    limit = Math.min(Math.max(limit, 1), 200);
                } catch (NumberFormatException e) {
                    this.sendError(context, "Invalid limit parameter: " + limitParam, 400);
                    return;
                }
            }

            List<ServerActivity> activities = activityService.getActivitiesByGroup(groupName, limit);
            List<ActivityResponse> response = activities.stream()
                .map(this::mapActivityToResponse)
                .collect(Collectors.toList());

            this.sendResponse(context, ApiResponse.success(response));

        } catch (Exception e) {
            Logger.error("Failed to get group activities for {}", context.pathParam("name"), e);
            this.sendError(context, "Failed to get group activities: " + e.getMessage());
        }
    }

    private ActivityResponse mapActivityToResponse(ServerActivity activity) {
        Map<String, Object> metadata = null;
        if (activity.getMetadata() != null && !activity.getMetadata().trim().isEmpty()) {
            try {
                metadata = this.objectMapper.readValue(activity.getMetadata(), Map.class);
            } catch (Exception e) {
                Logger.debug("Failed to parse activity metadata for {}: {}", activity.getId(), e.getMessage());
            }
        }

        return ActivityResponse.builder()
            .id(activity.getId())
            .serverId(activity.getServerId())
            .serverName(activity.getServerName())
            .groupName(activity.getGroupName())
            .activityType(activity.getActivityType())
            .timestamp(activity.getTimestamp())
            .triggeredBy(activity.getTriggeredBy())
            .description(activity.getDescription())
            .metadata(metadata)
            .build();
    }

    private void getTemplateFiles(RoutingContext context) {
        String path = context.request().getParam("path");

        if (path == null || path.isEmpty()) {
            path = "/";
        }

        final String finalPath = path;

        if (!this.fileManager.isValidPath(finalPath)) {
            this.sendError(context, "Invalid path: directory traversal not allowed", 400);
            return;
        }

        try {
            FileListResponse response = this.fileManager.listTemplateFiles(finalPath);
            this.sendResponse(context, ApiResponse.success(response));
        } catch (SecurityException e) {
            Logger.warn("Security violation: attempted directory traversal in templates at path {}: {}", finalPath, e.getMessage());
            this.sendError(context, "Security violation: " + e.getMessage(), 403);
        } catch (Exception e) {
            Logger.error("Failed to list template files at path " + finalPath, e);
            this.sendError(context, "Failed to list template files: " + e.getMessage());
        }
    }

    private void getTemplateFileContents(RoutingContext context) {
        String filePath = context.request().getParam("file");

        if (filePath == null || filePath.isEmpty()) {
            this.sendError(context, "File parameter is required", 400);
            return;
        }

        if (!this.fileManager.isValidPath(filePath)) {
            this.sendError(context, "Invalid file path: directory traversal not allowed", 400);
            return;
        }

        try {
            String fileContents = this.fileManager.readTemplateFileContents(filePath);

            context.response()
                    .putHeader("Content-Type", "text/plain; charset=utf-8")
                    .end(fileContents);

        } catch (SecurityException e) {
            Logger.warn("Security violation: attempted file access outside templates directory at path {}: {}", filePath, e.getMessage());
            this.sendError(context, "Security violation: " + e.getMessage(), 403);
        } catch (Exception e) {
            Logger.error("Failed to read template file contents at path {}", filePath, e);
            this.sendError(context, "Failed to read template file: " + e.getMessage());
        }
    }

    private void writeTemplateFileContents(RoutingContext context) {
        String filePath = context.request().getParam("file");

        if (filePath == null || filePath.isEmpty()) {
            this.sendError(context, "File parameter is required", 400);
            return;
        }

        if (!this.fileManager.isValidPath(filePath)) {
            this.sendError(context, "Invalid file path: directory traversal not allowed", 400);
            return;
        }

        String content = context.body().asString();
        if (content == null) {
            content = "";
        }

        try {
            this.fileManager.writeTemplateFileContents(filePath, content);
            this.sendResponse(context, ApiResponse.success(null, "Template file written successfully"));

        } catch (SecurityException e) {
            Logger.warn("Security violation: attempted file write outside templates directory at path {}: {}", filePath, e.getMessage());
            this.sendError(context, "Security violation: " + e.getMessage(), 403);
        } catch (Exception e) {
            Logger.error("Failed to write template file contents at path {}", filePath, e);
            this.sendError(context, "Failed to write template file: " + e.getMessage());
        }
    }

    private void deleteTemplateFile(RoutingContext context) {
        String filePath = context.request().getParam("file");

        if (filePath == null || filePath.isEmpty()) {
            this.sendError(context, "File parameter is required", 400);
            return;
        }

        if (!this.fileManager.isValidPath(filePath)) {
            this.sendError(context, "Invalid file path: directory traversal not allowed", 400);
            return;
        }

        try {
            this.fileManager.deleteTemplateFile(filePath);
            this.sendResponse(context, ApiResponse.success(null, "Template file deleted successfully"));

        } catch (SecurityException e) {
            Logger.warn("Security violation: attempted file deletion outside templates directory at path {}: {}", filePath, e.getMessage());
            this.sendError(context, "Security violation: " + e.getMessage(), 403);
        } catch (Exception e) {
            Logger.error("Failed to delete template file at path {}", filePath, e);
            this.sendError(context, "Failed to delete template file: " + e.getMessage());
        }
    }

    private void downloadTemplateFile(RoutingContext context) {
        String filePath = context.request().getParam("file");

        if (filePath == null || filePath.isEmpty()) {
            this.sendError(context, "File parameter is required", 400);
            return;
        }

        if (!this.fileManager.isValidPath(filePath)) {
            this.sendError(context, "Invalid file path: directory traversal not allowed", 400);
            return;
        }

        try {
            this.fileManager.downloadTemplateFile(context, filePath);

        } catch (SecurityException e) {
            Logger.warn("Security violation: attempted file download outside templates directory at path {}: {}", filePath, e.getMessage());
            this.sendError(context, "Security violation: " + e.getMessage(), 403);
        } catch (Exception e) {
            Logger.error("Failed to download template file at path {}", filePath, e);
            this.sendError(context, "Failed to download template file: " + e.getMessage());
        }
    }

    private void createTemplateDirectory(RoutingContext context) {
        JsonObject body = context.body().asJsonObject();

        if (body == null) {
            this.sendError(context, "Request body is required", 400);
            return;
        }

        String directoryPath = body.getString("path");

        if (directoryPath == null || directoryPath.isEmpty()) {
            this.sendError(context, "Path is required", 400);
            return;
        }

        if (!this.fileManager.isValidPath(directoryPath)) {
            this.sendError(context, "Invalid directory path: directory traversal not allowed", 400);
            return;
        }

        try {
            this.fileManager.createTemplateDirectory(directoryPath);

            Map<String, Object> response = new HashMap<>();
            response.put("path", directoryPath);

            this.sendResponse(context, ApiResponse.success(response, "Template directory created successfully"));

        } catch (SecurityException e) {
            Logger.warn("Security violation: attempted directory creation outside templates directory at path {}: {}", directoryPath, e.getMessage());
            this.sendError(context, "Security violation: " + e.getMessage(), 403);
        } catch (Exception e) {
            Logger.error("Failed to create template directory at path {}", directoryPath, e);
            this.sendError(context, "Failed to create template directory: " + e.getMessage());
        }
    }

    private void renameTemplateFile(RoutingContext context) {
        JsonObject body = context.body().asJsonObject();

        if (body == null) {
            this.sendError(context, "Request body is required", 400);
            return;
        }

        String oldPath = body.getString("oldPath");
        String newPath = body.getString("newPath");

        if (oldPath == null || oldPath.isEmpty()) {
            this.sendError(context, "oldPath is required", 400);
            return;
        }

        if (newPath == null || newPath.isEmpty()) {
            this.sendError(context, "newPath is required", 400);
            return;
        }

        if (!this.fileManager.isValidPath(oldPath) || !this.fileManager.isValidPath(newPath)) {
            this.sendError(context, "Invalid file path: directory traversal not allowed", 400);
            return;
        }

        try {
            this.fileManager.renameTemplateFile(oldPath, newPath);
            this.sendResponse(context, ApiResponse.success(null, "Template file renamed successfully"));

        } catch (SecurityException e) {
            Logger.warn("Security violation: attempted file rename outside templates directory from {} to {}: {}", oldPath, newPath, e.getMessage());
            this.sendError(context, "Security violation: " + e.getMessage(), 403);
        } catch (Exception e) {
            Logger.error("Failed to rename template file from {} to {}", oldPath, newPath, e);
            this.sendError(context, "Failed to rename template file: " + e.getMessage());
        }
    }

    private void uploadTemplateFileWithAuth(RoutingContext context) {
        String authHeader = context.request().getHeader("Authorization");
        String token = this.authHandler.extractBearerToken(authHeader);

        if (!this.authHandler.isValidToken(token)) {
            this.sendError(context, "Unauthorized: Invalid or missing API key", 401);
            return;
        }

        this.uploadTemplateFile(context);
    }

    private void uploadTemplateFile(RoutingContext context) {
        String targetPath = context.request().getParam("path");

        if (targetPath == null || targetPath.isEmpty()) {
            this.sendError(context, "Path parameter is required", 400);
            return;
        }

        if (!this.fileManager.isValidPath(targetPath)) {
            this.sendError(context, "Invalid file path: directory traversal not allowed", 400);
            return;
        }

        try {
            this.fileManager.uploadTemplateFileStream(targetPath, context.request())
                    .thenAccept(fileSize -> {
                        Map<String, Object> response = new HashMap<>();
                        response.put("path", targetPath);
                        response.put("size", fileSize);

                        this.sendResponse(context, ApiResponse.success(response, "Template file uploaded successfully"));
                    })
                    .exceptionally(throwable -> {
                        if (throwable.getCause() instanceof SecurityException) {
                            Logger.warn("Security violation: attempted file upload outside templates directory at path {}: {}", targetPath, throwable.getMessage());
                            this.sendError(context, "Security violation: " + throwable.getCause().getMessage(), 403);
                        } else {
                            Logger.error("Failed to upload template file at path {}", targetPath, throwable);
                            this.sendError(context, "Failed to upload template file: " + throwable.getMessage());
                        }
                        return null;
                    });

        } catch (SecurityException e) {
            Logger.warn("Security violation: attempted file upload outside templates directory at path {}: {}", targetPath, e.getMessage());
            this.sendError(context, "Security violation: " + e.getMessage(), 403);
        } catch (Exception e) {
            Logger.error("Failed to upload template file at path {}", targetPath, e);
            this.sendError(context, "Failed to upload template file: " + e.getMessage());
        }
    }

    private void getServerMetadata(RoutingContext context) {
        String serverId = context.pathParam("id");
        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();

        provider.getServer(serverId)
                .thenAccept(serverOpt -> {
                    if (serverOpt.isEmpty()) {
                        this.sendError(context, "Server not found: " + serverId, 404);
                        return;
                    }

                    AtlasServer server = serverOpt.get();
                    this.sendResponse(context, ApiResponse.success(server.getMetadata()));
                })
                .exceptionally(throwable -> {
                    this.sendError(context, "Failed to get server metadata: " + throwable.getMessage());
                    return null;
                });
    }

    private void setServerMetadata(RoutingContext context) {
        String serverId = context.pathParam("id");
        JsonObject body = context.body().asJsonObject();

        if (body == null) {
            this.sendError(context, "Request body is required", 400);
            return;
        }

        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();

        provider.getServer(serverId)
                .thenAccept(serverOpt -> {
                    if (serverOpt.isEmpty()) {
                        this.sendError(context, "Server not found: " + serverId, 404);
                        return;
                    }

                    AtlasServer server = serverOpt.get();
                    Map<String, String> newMetadata = new HashMap<>();

                    for (String key : body.fieldNames()) {
                        if (key == null || key.trim().isEmpty() || key.length() > 100) {
                            continue;
                        }
                        Object value = body.getValue(key);
                        if (value != null) {
                            String valueStr = value.toString();
                            if (valueStr.length() > 1000) {
                                continue;
                            }
                            newMetadata.put(key, valueStr);
                        }
                    }

                    server.setMetadata(newMetadata);
                    
                    this.broadcastMetadataUpdate(server.getServerId(), server.getMetadata());

                    this.sendResponse(context, ApiResponse.success(server.getMetadata(), "Metadata updated successfully"));
                })
                .exceptionally(throwable -> {
                    this.sendError(context, "Failed to set server metadata: " + throwable.getMessage());
                    return null;
                });
    }

    private void setServerMetadataKey(RoutingContext context) {
        String serverId = context.pathParam("id");
        String key = context.pathParam("key");
        String value = context.body().asString();

        if (key == null || key.trim().isEmpty()) {
            this.sendError(context, "Metadata key is required", 400);
            return;
        }

        if (key.length() > 100) {
            this.sendError(context, "Metadata key too long (max 100 characters)", 400);
            return;
        }

        if (value == null) {
            value = "";
        }

        if (value.length() > 1000) {
            this.sendError(context, "Metadata value too long (max 1000 characters)", 400);
            return;
        }

        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();
        String finalValue = value;

        provider.getServer(serverId)
                .thenAccept(serverOpt -> {
                    if (serverOpt.isEmpty()) {
                        this.sendError(context, "Server not found: " + serverId, 404);
                        return;
                    }

                    AtlasServer server = serverOpt.get();
                    server.setMetadata(key, finalValue);
                    
                    this.broadcastMetadataUpdate(server.getServerId(), server.getMetadata());

                    Map<String, String> response = new HashMap<>();
                    response.put("key", key);
                    response.put("value", finalValue);

                    this.sendResponse(context, ApiResponse.success(response, "Metadata key updated successfully"));
                })
                .exceptionally(throwable -> {
                    this.sendError(context, "Failed to set server metadata key: " + throwable.getMessage());
                    return null;
                });
    }

    private void removeServerMetadataKey(RoutingContext context) {
        String serverId = context.pathParam("id");
        String key = context.pathParam("key");

        if (key == null || key.trim().isEmpty()) {
            this.sendError(context, "Metadata key is required", 400);
            return;
        }

        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();

        provider.getServer(serverId)
                .thenAccept(serverOpt -> {
                    if (serverOpt.isEmpty()) {
                        this.sendError(context, "Server not found: " + serverId, 404);
                        return;
                    }

                    AtlasServer server = serverOpt.get();
                    server.removeMetadata(key);
                    
                    this.broadcastMetadataUpdate(server.getServerId(), server.getMetadata());

                    this.sendResponse(context, ApiResponse.success(null, "Metadata key removed successfully"));
                })
                .exceptionally(throwable -> {
                    this.sendError(context, "Failed to remove server metadata key: " + throwable.getMessage());
                    return null;
                });
    }
    
    private void broadcastMetadataUpdate(String serverId, Map<String, String> metadata) {
        try {
            AtlasBase atlasInstance = AtlasBase.getInstance();
            if (atlasInstance != null && atlasInstance.getNettyServer() != null && 
                atlasInstance.getNettyServer().getConnectionManager() != null) {
                
                MetadataUpdatePacket packet = new MetadataUpdatePacket(serverId, metadata);
                atlasInstance.getNettyServer().getConnectionManager().broadcastPacket(packet);
                
                Logger.debug("Broadcasted metadata update for server {} to all connected plugins", serverId);
            }
        } catch (Exception e) {
            Logger.error("Failed to broadcast metadata update for server {}: {}", serverId, e.getMessage());
        }
    }
}
