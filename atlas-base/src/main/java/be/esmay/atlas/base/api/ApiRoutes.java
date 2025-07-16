package be.esmay.atlas.base.api;

import be.esmay.atlas.base.AtlasBase;
import be.esmay.atlas.base.api.dto.ApiResponse;
import be.esmay.atlas.base.provider.ServiceProvider;
import be.esmay.atlas.base.scaler.Scaler;
import be.esmay.atlas.base.utils.Logger;
import be.esmay.atlas.common.models.AtlasServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class ApiRoutes {

    private final Router router;
    private final ApiAuthHandler authHandler;
    private final ObjectMapper objectMapper;

    public ApiRoutes(Router router, ApiAuthHandler authHandler) {
        this.router = router;
        this.authHandler = authHandler;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    public void setupRoutes() {
        this.router.route("/api/v1/*").handler(BodyHandler.create());
        this.router.route("/api/v1/*").handler(this.authHandler.authenticate());

        this.router.get("/api/v1/status").handler(this::getStatus);
        this.router.get("/api/v1/servers").handler(this::getServers);
        this.router.get("/api/v1/servers/:id").handler(this::getServer);
        this.router.get("/api/v1/groups").handler(this::getGroups);
        this.router.get("/api/v1/scaling").handler(this::getScaling);
        this.router.get("/api/v1/metrics").handler(this::getMetrics);

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
        String group = context.request().getParam("group");
        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();

        if (group != null) {
            provider.getServersByGroup(group)
                .thenAccept(servers -> this.sendResponse(context, ApiResponse.success(servers)))
                .exceptionally(throwable -> {
                    this.sendError(context, "Failed to get servers: " + throwable.getMessage());
                    return null;
                });
        } else {
            provider.getAllServers()
                .thenAccept(servers -> this.sendResponse(context, ApiResponse.success(servers)))
                .exceptionally(throwable -> {
                    this.sendError(context, "Failed to get servers: " + throwable.getMessage());
                    return null;
                });
        }
    }

    private void getServer(RoutingContext context) {
        String serverId = context.pathParam("id");
        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();

        provider.getServer(serverId)
            .thenAccept(serverOpt -> {
                if (serverOpt.isPresent()) {
                    this.sendResponse(context, ApiResponse.success(serverOpt.get()));
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

        Scaler scaler = AtlasBase.getInstance().getScalerManager().getScaler(group);
        if (scaler == null) {
            this.sendError(context, "Group not found: " + group, 404);
            return;
        }

        scaler.upscale()
            .thenRun(() -> this.sendResponse(context, ApiResponse.success(null, "Server creation initiated")))
            .exceptionally(throwable -> {
                this.sendError(context, "Failed to create server: " + throwable.getMessage());
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
                                   java.util.function.Function<AtlasServer, java.util.concurrent.CompletableFuture<Void>> serverAction) {
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