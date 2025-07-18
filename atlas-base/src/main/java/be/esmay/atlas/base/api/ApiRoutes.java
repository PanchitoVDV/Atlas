package be.esmay.atlas.base.api;

import be.esmay.atlas.base.AtlasBase;
import be.esmay.atlas.base.api.dto.ApiResponse;
import be.esmay.atlas.base.metrics.NetworkBandwidthMonitor;
import be.esmay.atlas.base.provider.ServiceProvider;
import be.esmay.atlas.base.scaler.Scaler;
import be.esmay.atlas.base.utils.Logger;
import be.esmay.atlas.common.models.AtlasServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import be.esmay.atlas.common.enums.ServerStatus;
import be.esmay.atlas.common.models.ServerResourceMetrics;
import java.lang.management.ManagementFactory;
import com.sun.management.OperatingSystemMXBean;
import java.io.File;
import java.text.DecimalFormat;

public final class ApiRoutes {

    private final Router router;
    private final ApiAuthHandler authHandler;
    private final ObjectMapper objectMapper;

    public ApiRoutes(Router router, ApiAuthHandler authHandler) {
        this.router = router;
        this.authHandler = authHandler;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public void setupRoutes() {
        this.router.route("/api/v1/*").handler(BodyHandler.create());
        this.router.route("/api/v1/*").handler(this.authHandler.authenticate());

        this.router.get("/api/v1/status").handler(this::getStatus);
        this.router.get("/api/v1/servers").handler(this::getServers);
        this.router.get("/api/v1/servers/count").handler(this::getServerCount);
        this.router.get("/api/v1/players/count").handler(this::getPlayerCount);
        this.router.get("/api/v1/servers/:id").handler(this::getServer);
        this.router.get("/api/v1/groups").handler(this::getGroups);
        this.router.get("/api/v1/scaling").handler(this::getScaling);
        this.router.get("/api/v1/metrics").handler(this::getMetrics);
        this.router.get("/api/v1/utilization").handler(this::getUtilization);

        this.router.post("/api/v1/servers").handler(this::createServers);
        this.router.post("/api/v1/servers/:id/start").handler(this::startServer);
        this.router.post("/api/v1/servers/:id/stop").handler(this::stopServer);
        this.router.post("/api/v1/servers/:id/command").handler(this::executeServerCommand);
        this.router.delete("/api/v1/servers/:id").handler(this::removeServer);
        this.router.post("/api/v1/groups/:group/scale").handler(this::scaleGroup);
    }

    private void getStatus(RoutingContext context) {
        AtlasBase atlasBase = AtlasBase.getInstance();
        
        JsonObject status = new JsonObject()
            .put("running", atlasBase.isRunning())
            .put("debugMode", atlasBase.isDebugMode())
            .put("uptime", System.currentTimeMillis());

        this.sendResponse(context, ApiResponse.success(status));
    }

    private void getServers(RoutingContext context) {
        String groupFilter = context.request().getParam("group");
        String statusFilter = context.request().getParam("status");
        String searchFilter = context.request().getParam("search");
        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();

        CompletableFuture<List<AtlasServer>> serversFuture;
        if (groupFilter != null) {
            serversFuture = provider.getServersByGroup(groupFilter);
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
        List<String> groups = scalers.stream()
            .map(scaler -> scaler.getScalerConfig().getGroup().getName())
            .collect(Collectors.toList());

        this.sendResponse(context, ApiResponse.success(groups));
    }

    private void getScaling(RoutingContext context) {
        Set<Scaler> scalers = AtlasBase.getInstance().getScalerManager().getScalers();
        
        JsonObject scaling = new JsonObject();
        for (Scaler scaler : scalers) {
            JsonObject scalerInfo = new JsonObject()
                .put("group", scaler.getScalerConfig().getGroup().getName())
                .put("type", scaler.getClass().getSimpleName());
            scaling.put(scaler.getGroupName(), scalerInfo);
        }

        this.sendResponse(context, ApiResponse.success(scaling));
    }

    private void getMetrics(RoutingContext context) {
        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();
        
        provider.getAllServers()
            .thenAccept(servers -> {
                JsonObject metrics = new JsonObject()
                    .put("totalServers", servers.size())
                    .put("totalPlayers", servers.stream().mapToInt(server -> server.getServerInfo() != null ? server.getServerInfo().getOnlinePlayers() : 0).sum())
                    .put("serversByStatus", servers.stream()
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

    private void getServerCount(RoutingContext context) {
        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();
        
        provider.getAllServers()
            .thenAccept(servers -> {
                JsonObject count = new JsonObject()
                    .put("total", servers.size())
                    .put("byStatus", servers.stream()
                        .filter(server -> server.getServerInfo() != null)
                        .collect(Collectors.groupingBy(
                            server -> server.getServerInfo().getStatus().toString(),
                            Collectors.counting())))
                    .put("byGroup", servers.stream()
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
                    .mapToInt(server -> server.getServerInfo().getOnlinePlayers())
                    .sum();
                
                int totalCapacity = servers.stream()
                    .filter(server -> server.getServerInfo() != null)
                    .mapToInt(server -> server.getServerInfo().getMaxPlayers())
                    .sum();
                
                Map<String, Integer> playersByGroup = servers.stream()
                    .filter(server -> server.getServerInfo() != null)
                    .collect(Collectors.groupingBy(
                        AtlasServer::getGroup,
                        Collectors.summingInt(server -> server.getServerInfo().getOnlinePlayers())));
                
                Map<String, Integer> playersByStatus = servers.stream()
                    .filter(server -> server.getServerInfo() != null)
                    .collect(Collectors.groupingBy(
                        server -> server.getServerInfo().getStatus().toString(),
                        Collectors.summingInt(server -> server.getServerInfo().getOnlinePlayers())));
                
                JsonObject playerCount = new JsonObject()
                    .put("total", totalPlayers)
                    .put("capacity", totalCapacity)
                    .put("percentage", totalCapacity > 0 ? (double) totalPlayers / totalCapacity * 100 : 0)
                    .put("byGroup", playersByGroup)
                    .put("byStatus", playersByStatus);
                
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
            
            JsonObject utilization = new JsonObject()
                .put("cpu", new JsonObject()
                    .put("cores", availableProcessors)
                    .put("usage", Double.parseDouble(df.format(cpuLoad)))
                    .put("formatted", df.format(cpuLoad) + "%"))
                .put("memory", new JsonObject()
                    .put("used", usedMemory)
                    .put("total", totalMemory)
                    .put("percentage", Double.parseDouble(df.format(memoryPercentage)))
                    .put("usedFormatted", this.formatBytes(usedMemory))
                    .put("totalFormatted", this.formatBytes(totalMemory)))
                .put("disk", new JsonObject()
                    .put("used", usedDisk)
                    .put("total", totalDisk)
                    .put("percentage", Double.parseDouble(df.format(diskPercentage)))
                    .put("usedFormatted", this.formatBytes(usedDisk))
                    .put("totalFormatted", this.formatBytes(totalDisk)))
                .put("bandwidth", new JsonObject()
                    .put("used", bandwidthUsed)
                    .put("total", bandwidthCapacity)
                    .put("percentage", bandwidthPercentage)
                    .put("receiveRate", bandwidthStats != null ? bandwidthStats.receiveBps : 0)
                    .put("sendRate", bandwidthStats != null ? bandwidthStats.sendBps : 0)
                    .put("usedFormatted", this.formatBytes(bandwidthUsed) + "/s")
                    .put("totalFormatted", this.formatBytes(bandwidthCapacity) + "/s")
                    .put("receiveFormatted", this.formatBytes(bandwidthStats != null ? (long) bandwidthStats.receiveBps : 0) + "/s")
                    .put("sendFormatted", this.formatBytes(bandwidthStats != null ? (long) bandwidthStats.sendBps : 0) + "/s"));
            
            this.sendResponse(context, ApiResponse.success(utilization));
        } catch (Exception e) {
            Logger.error("Failed to get system utilization", e);
            this.sendError(context, "Failed to get system utilization: " + e.getMessage());
        }
    }
    
    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp-1) + "";
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
}