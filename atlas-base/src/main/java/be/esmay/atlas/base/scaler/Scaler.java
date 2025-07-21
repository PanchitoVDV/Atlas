package be.esmay.atlas.base.scaler;

import be.esmay.atlas.base.AtlasBase;
import be.esmay.atlas.base.config.impl.ScalerConfig;
import be.esmay.atlas.base.lifecycle.ServerLifecycleManager;
import be.esmay.atlas.base.lifecycle.ServerLifecycleService;
import be.esmay.atlas.base.provider.DeletionOptions;
import be.esmay.atlas.base.provider.StartOptions;
import be.esmay.atlas.base.provider.ServiceProvider;
import be.esmay.atlas.base.utils.Logger;
import be.esmay.atlas.common.enums.ScaleType;
import be.esmay.atlas.common.enums.ServerStatus;
import be.esmay.atlas.common.enums.ServerType;
import be.esmay.atlas.common.models.AtlasServer;
import be.esmay.atlas.common.models.ServerInfo;
import lombok.Getter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Getter
public abstract class Scaler {

    protected final String groupName;
    protected final ScalerConfig scalerConfig;
    protected final ServiceProvider serviceProvider;
    protected final ServerLifecycleManager lifecycleManager;
    protected final ServerLifecycleService lifecycleService;
    protected final Map<String, AtlasServer> servers = new ConcurrentHashMap<>();
    protected final Set<String> pendingRemovals = ConcurrentHashMap.newKeySet();
    protected final Set<String> reservedNames = ConcurrentHashMap.newKeySet();
    /**
     * -- GETTER --
     *  Gets the set of manually stopped server IDs.
     *  Used by zombie detection to skip manually stopped servers.
     *
     * @return Set of manually stopped server IDs
     */
    @Getter
    protected final Set<String> manuallyStopped = ConcurrentHashMap.newKeySet();
    protected final Set<String> currentlyRestarting = ConcurrentHashMap.newKeySet();

    protected volatile boolean shutdown = false;
    protected volatile boolean paused = false;
    protected volatile Instant lastScaleUpTime = Instant.MIN;
    protected volatile Instant lastScaleDownTime = Instant.MIN;

    public Scaler(String groupName, ScalerConfig scalerConfig) {
        this.groupName = groupName;
        this.scalerConfig = scalerConfig;
        this.serviceProvider = AtlasBase.getInstance().getProviderManager().getProvider();
        this.lifecycleManager = new ServerLifecycleManager();
        this.lifecycleService = new ServerLifecycleService(AtlasBase.getInstance());
    }

    public abstract ScaleType needsScaling();

    public int getLowestAvailableNumber() {
        if (this.isUuidIdentifier()) return 1;

        Set<Integer> usedNumbers = this.servers.values().stream()
                .map(server -> this.extractServerNumber(server.getName()))
                .filter(num -> num > 0)
                .collect(Collectors.toSet());

        Set<Integer> reservedNumbers = this.reservedNames.stream()
                .map(this::extractServerNumber)
                .filter(num -> num > 0)
                .collect(Collectors.toSet());

        for (int i = 1; i <= this.getMaxServers(); i++) {
            if (!usedNumbers.contains(i) && !reservedNumbers.contains(i)) {
                return i;
            }
        }

        return usedNumbers.size() + reservedNumbers.size() + 1;
    }

    public void scaleServers() {
        this.checkHeartbeats();

        if (this.paused) {
            Logger.debug("Scaling is paused for group: {}", this.groupName);
            return;
        }

        if (!this.pendingRemovals.isEmpty()) {
            Logger.debug("Waiting for {} pending removals to complete before scaling group: {}", 
                    this.pendingRemovals.size(), this.groupName);
            return;
        }

        ScaleType scaleType = this.needsScaling();

        switch (scaleType) {
            case UP -> {
                Logger.debug("Scaling up servers for group: {} (current utilization: {}%)", this.groupName, String.format("%.2f", this.getCurrentUtilization() * 100));

                CompletableFuture<Void> upscaleFuture = this.autoUpscale();

                upscaleFuture.exceptionally(throwable -> {
                    Logger.error("Failed to auto-scale up servers for group: {}", this.groupName, throwable);
                    return null;
                });
            }
            case DOWN -> {
                Logger.debug("Scaling down servers for group: {} (current utilization: {}%)", this.groupName, String.format("%.2f", this.getCurrentUtilization() * 100));
                this.autoScaleDown();
            }
            case NONE ->
                    Logger.debug("No scaling needed for group: {} (current utilization: {}%)", this.groupName, String.format("%.2f", this.getCurrentUtilization() * 100));
        }
    }

    public CompletableFuture<Void> upscale() {
        ServerType serverType = ServerType.valueOf(this.scalerConfig.getGroup().getServer().getType().toUpperCase());
        String serverName = this.getNextIdentifier();
        String serverId = UUID.randomUUID().toString();
        Logger.info("Manually scaling up server: {} (ID: {}) for group: {}", serverName, serverId, this.groupName);

        AtlasServer server = AtlasServer.builder()
                .serverId(serverId)
                .name(serverName)
                .group(this.groupName)
                .type(serverType)
                .createdAt(System.currentTimeMillis())
                .isManuallyScaled(true)
                .shutdown(false)
                .build();
        
        CompletableFuture<AtlasServer> createFuture = this.serviceProvider.startServerCompletely(server, StartOptions.scalingUp());

        CompletableFuture<Void> acceptFuture = createFuture.thenAccept(startedServer -> {
            this.addServer(startedServer);
            Logger.debug("Successfully created manual server: {}", startedServer.getName());
        });

        return acceptFuture.exceptionally(throwable -> {
            Logger.error("Failed to create manual server: {}", serverId, throwable);
            this.reservedNames.remove(serverName);
            return null;
        });
    }

    public CompletableFuture<Void> autoUpscale() {
        int currentAutoServers = this.getAutoScaledServers().size();
        int minServers = this.getMinServers();

        if (currentAutoServers < minServers) {
            int serversToCreate = minServers - currentAutoServers;
            Logger.debug("Scaling up to minimum servers for group: {}. Creating {} servers to reach minimum of {}", this.groupName, serversToCreate, minServers);

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < serversToCreate; i++) {
                futures.add(this.createAutoScaledServer());
            }

            CompletableFuture<Void> allFuture = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));

            return allFuture.thenRun(() -> this.lastScaleUpTime = Instant.now());
        }

        if (!this.canScaleUp()) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> createFuture = this.createAutoScaledServer();

        return createFuture.thenRun(() -> this.lastScaleUpTime = Instant.now());
    }

    private CompletableFuture<Void> createAutoScaledServer() {
        ServerType serverType = ServerType.valueOf(this.scalerConfig.getGroup().getServer().getType().toUpperCase());
        String serverName = this.getNextIdentifier();
        String serverId = UUID.randomUUID().toString();
        Logger.debug("Creating auto-scaled server: {} (ID: {}) for group: {}", serverName, serverId, this.groupName);

        AtlasServer server = AtlasServer.builder()
                .serverId(serverId)
                .name(serverName)
                .group(this.groupName)
                .type(serverType)
                .createdAt(System.currentTimeMillis())
                .isManuallyScaled(false)
                .shutdown(false)
                .build();
        
        CompletableFuture<AtlasServer> createFuture = this.serviceProvider.startServerCompletely(server, StartOptions.scalingUp());

        CompletableFuture<Void> acceptFuture = createFuture.thenAccept(startedServer -> {
            this.addServer(startedServer);
            Logger.debug("Started server: {}", startedServer.getName());
        });

        return acceptFuture.exceptionally(throwable -> {
            Logger.error("Failed to create auto-scaled server: {}", serverId, throwable);
            this.reservedNames.remove(serverName);
            return null;
        });
    }

    private void autoScaleDown() {
        if (!this.canScaleDown())
            return;

        AtlasServer serverToRemove = this.getAutoScaledServers().stream()
                .filter(server -> server.getServerInfo() != null && server.getServerInfo().getStatus() == ServerStatus.RUNNING)
                .min(Comparator.comparingInt((AtlasServer server) -> server.getServerInfo() != null ? server.getServerInfo().getOnlinePlayers() : 0)
                        .thenComparing(AtlasServer::getCreatedAt, Comparator.reverseOrder()))
                .orElse(null);

        if (serverToRemove != null) {
            Logger.info("Auto-scaling down server: {} (players: {}) from group: {}", serverToRemove.getName(), serverToRemove.getServerInfo() != null ? serverToRemove.getServerInfo().getOnlinePlayers() : 0, this.groupName);

            this.remove(serverToRemove);
            this.lastScaleDownTime = Instant.now();
        }
    }

    public void pauseScaling() {
        this.paused = true;
        Logger.info("Scaling paused for group: {}", this.groupName);
    }

    public void resumeScaling() {
        this.paused = false;
        Logger.info("Scaling resumed for group: {}", this.groupName);
    }

    public CompletableFuture<Void> triggerScaleUp() {
        Logger.info("Triggering manual scale up for group: {}", this.groupName);
        return this.upscale();
    }

    public CompletableFuture<Void> triggerScaleDown() {
        Logger.info("Triggering manual scale down for group: {}", this.groupName);
        return CompletableFuture.runAsync(this::manualScaleDown);
    }

    private void manualScaleDown() {
        AtlasServer serverToRemove = this.servers.values().stream()
                .filter(server -> server.getServerInfo() != null && server.getServerInfo().getStatus() == ServerStatus.RUNNING)
                .min(Comparator.comparingInt((AtlasServer server) -> server.getServerInfo() != null ? server.getServerInfo().getOnlinePlayers() : 0)
                        .thenComparing(AtlasServer::getCreatedAt, Comparator.reverseOrder()))
                .orElse(null);

        if (serverToRemove != null) {
            String serverType = serverToRemove.isManuallyScaled() ? "manual" : "auto-scaled";
            Logger.info("Manually scaling down {} server: {} (players: {}) from group: {}", serverType, serverToRemove.getName(), serverToRemove.getServerInfo() != null ? serverToRemove.getServerInfo().getOnlinePlayers() : 0, this.groupName);

            this.remove(serverToRemove);
        } else {
            Logger.info("No running servers available to scale down in group: {}", this.groupName);
        }
    }

    public void shutdown() {
        Logger.info("Shutting down scaler for group: {}", this.groupName);
        this.shutdown = true;

        CompletableFuture.allOf(this.servers.values().stream()
                .map(this::shutdownServer)
                .toArray(CompletableFuture[]::new)).join();

        this.servers.clear();
    }

    public synchronized String getNextIdentifier() {
        String pattern = this.scalerConfig.getGroup().getServer().getNaming().getNamePattern();

        if (pattern == null || pattern.isEmpty()) {
            pattern = this.groupName.toLowerCase() + "-{id}";
            Logger.warn("No naming pattern configured for group {}, using default: {}", this.groupName, pattern);
        }

        if (this.isUuidIdentifier()) {
            return pattern.replace("{id}", UUID.randomUUID().toString().substring(0, 8));
        }

        int nextNumber = this.getLowestAvailableNumber();
        String serverName = pattern.replace("{id}", String.valueOf(nextNumber));
        this.reservedNames.add(serverName);
        return serverName;
    }

    public void removeManualServer(String serverId) {
        AtlasServer server = this.servers.get(serverId);
        if (server != null && server.isManuallyScaled()) {
            Logger.info("Manually removing server: {} from group: {}", server.getName(), this.groupName);
            this.remove(server);
        } else {
            Logger.warn("Attempted to manually remove non-manual server or non-existent server: {}", serverId);
        }
    }

    public void remove(AtlasServer server) {
        if (server == null || !this.servers.containsKey(server.getServerId()))
            return;

        String serverId = server.getServerId();
        this.pendingRemovals.add(serverId);
        Logger.debug("Added server {} to pending removals", serverId);

        this.lifecycleService.removeServer(server, DeletionOptions.scalingDown()).thenRun(() -> {
            this.pendingRemovals.remove(serverId);
            Logger.debug("Completed scaling removal of server: {} (removed from pending)", server.getName());
        }).exceptionally(throwable -> {
            this.pendingRemovals.remove(serverId);
            Logger.error("Failed to remove server {} during scaling (removed from pending)", server.getName(), throwable);
            return null;
        });
    }

    public CompletableFuture<Void> removeAsync(AtlasServer server) {
        if (server == null || !this.servers.containsKey(server.getServerId())) {
            return CompletableFuture.completedFuture(null);
        }

        String serverId = server.getServerId();
        this.pendingRemovals.add(serverId);
        Logger.debug("Added server {} to pending removals (async)", serverId);

        return this.lifecycleService.removeServer(server, DeletionOptions.scalingDown()).thenRun(() -> {
            this.pendingRemovals.remove(serverId);
            Logger.debug("Completed async scaling removal of server: {} (removed from pending)", server.getName());
        }).exceptionally(throwable -> {
            this.pendingRemovals.remove(serverId);
            Logger.error("Failed to remove server {} async during scaling (removed from pending)", server.getName(), throwable);
            return null;
        });
    }

    private CompletableFuture<Void> shutdownServer(AtlasServer server) {
        CompletableFuture<Boolean> deleteFuture = this.serviceProvider.deleteServerCompletely(server, DeletionOptions.systemShutdown());
        CompletableFuture<Void> completionFuture = deleteFuture.thenAccept(success -> {
            if (success) {
                Logger.debug("Successfully removed server during shutdown: {}", server.getName());
            } else {
                Logger.warn("Failed to remove server during shutdown: {}", server.getName());
            }
        });
        return completionFuture.exceptionally(throwable -> {
            Logger.error("Failed to shutdown server: {}", server.getName(), throwable);
            return null;
        });
    }

    public List<AtlasServer> getServers() {
        return new ArrayList<>(this.servers.values());
    }

    public AtlasServer getServer(String serverId) {
        return this.servers.get(serverId);
    }

    public void removeServerFromTracking(String serverId) {
        AtlasServer server = this.servers.get(serverId);
        if (server != null) {
            Logger.debug("Removing server {} (type: {}, status: {}) from scaler tracking for group {}", 
                serverId, server.getType(), 
                server.getServerInfo() != null ? server.getServerInfo().getStatus() : "null",
                this.groupName);
        }
        this.servers.remove(serverId);
        this.pendingRemovals.remove(serverId);
        Logger.debug("Removed server {} from tracking and pending removals", serverId);
    }

    public List<AtlasServer> getAutoScaledServers() {
        return this.servers.values().stream()
                .filter(server -> !server.isManuallyScaled())
                .collect(Collectors.toList());
    }

    public List<AtlasServer> getManuallyScaledServers() {
        return this.servers.values().stream()
                .filter(AtlasServer::isManuallyScaled)
                .collect(Collectors.toList());
    }

    public int getTotalOnlinePlayers() {
        return this.servers.values().stream()
                .filter(server -> server.getServerInfo() != null)
                .mapToInt(server -> server.getServerInfo().getOnlinePlayers())
                .sum();
    }

    public int getAutoScaledOnlinePlayers() {
        return this.getAutoScaledServers().stream()
                .filter(server -> server.getServerInfo() != null)
                .mapToInt(server -> server.getServerInfo().getOnlinePlayers())
                .sum();
    }

    public double getCurrentUtilization() {
        List<AtlasServer> autoServers = this.getAutoScaledServers();
        if (autoServers.isEmpty())
            return 0.0;

        List<AtlasServer> runningServers = autoServers.stream()
                .filter(server -> server.getServerInfo() != null && server.getServerInfo().getStatus() == ServerStatus.RUNNING)
                .toList();

        int avgMaxPlayers;
        if (!runningServers.isEmpty()) {
            avgMaxPlayers = runningServers.stream()
                    .mapToInt(server -> server.getServerInfo().getMaxPlayers())
                    .sum() / runningServers.size();
        } else {
            avgMaxPlayers = 20;
        }

        int totalPlayers = 0;
        int totalCapacity = 0;

        for (AtlasServer server : autoServers) {
            if (server.getServerInfo() == null) continue;

            totalPlayers += server.getServerInfo().getOnlinePlayers();

            if (server.getServerInfo().getStatus() == ServerStatus.RUNNING) {
                totalCapacity += server.getServerInfo().getMaxPlayers();
            } else if (server.getServerInfo().getStatus() == ServerStatus.STARTING) {
                totalCapacity += avgMaxPlayers;
            }
        }

        if (totalCapacity == 0)
            return 0.0;

        return (double) totalPlayers / totalCapacity;
    }

    public boolean canScaleUp() {
        int maxServers = this.getMaxServers();

        if (maxServers == -1) {
            return !this.shutdown;
        }

        return !this.shutdown && this.getAutoScaledServers().size() < maxServers;
    }

    public boolean canScaleDown() {
        return !this.shutdown && this.getAutoScaledServers().size() > this.getMinServers();
    }

    public void addServer(AtlasServer server) {
        this.servers.put(server.getServerId(), server);
        this.reservedNames.remove(server.getName());

        if (server.getServerInfo() != null) {
            ServerStatus status = server.getServerInfo().getStatus();
            if (status == ServerStatus.RUNNING) {
                AtlasBase atlasInstance = AtlasBase.getInstance();
                if (atlasInstance != null && atlasInstance.getNettyServer() != null) {
                    atlasInstance.getNettyServer().broadcastServerAdd(server);
                }
            } else if (status == ServerStatus.STARTING) {
                AtlasBase atlasInstance = AtlasBase.getInstance();
                if (atlasInstance != null && atlasInstance.getNettyServer() != null) {
                    atlasInstance.getNettyServer().broadcastServerUpdate(server);
                }
            }
        }
    }

    public void updateServerStatus(String serverId, ServerStatus status) {
        AtlasServer server = this.servers.get(serverId);
        if (server == null || server.getServerInfo() == null)
            return;

        ServerStatus oldStatus = server.getServerInfo().getStatus();
        server.getServerInfo().setStatus(status);

        if (oldStatus != ServerStatus.RUNNING && status == ServerStatus.RUNNING) {
            AtlasBase atlasInstance = AtlasBase.getInstance();
            if (atlasInstance == null || atlasInstance.getNettyServer() == null)
                return;

            atlasInstance.getNettyServer().broadcastServerAdd(server);

            if (atlasInstance.getApiManager() != null && atlasInstance.getApiManager().getWebSocketManager() != null) {
                atlasInstance.getApiManager().getWebSocketManager().broadcastServerStatusUpdate(serverId, "RUNNING");
            }
            return;
        }

        if (oldStatus == ServerStatus.RUNNING && (status == ServerStatus.STOPPED || status == ServerStatus.ERROR)) {
            AtlasBase atlasInstance = AtlasBase.getInstance();
            if (atlasInstance == null || atlasInstance.getNettyServer() == null)
                return;

            atlasInstance.getNettyServer().broadcastServerRemove(serverId, "Server status changed to " + status);
        }
    }

    public void updateServerInfo(String serverId, ServerInfo serverInfo) {
        AtlasServer server = this.servers.get(serverId);
        if (server == null) {
            Logger.warn("Received heartbeat for server {} but it's not in scaler tracking for group {}. Available servers: {}", 
                serverId, this.groupName, this.servers.keySet());
            return;
        }


        ServerStatus oldStatus = server.getServerInfo() != null ? server.getServerInfo().getStatus() : null;
        server.setServerInfo(serverInfo);
        server.setLastHeartbeat(System.currentTimeMillis());


        ServerStatus newStatus = serverInfo.getStatus();
        if (oldStatus != ServerStatus.RUNNING && newStatus == ServerStatus.RUNNING) {
            AtlasBase atlasInstance = AtlasBase.getInstance();
            if (atlasInstance == null || atlasInstance.getNettyServer() == null)
                return;

            atlasInstance.getNettyServer().broadcastServerAdd(server);

            if (atlasInstance.getApiManager() != null && atlasInstance.getApiManager().getWebSocketManager() != null) {
                atlasInstance.getApiManager().getWebSocketManager().broadcastServerStatusUpdate(serverId, "RUNNING");
            }
            return;
        }

        if (oldStatus == ServerStatus.RUNNING && (newStatus == ServerStatus.STOPPED || newStatus == ServerStatus.ERROR)) {
            AtlasBase atlasInstance = AtlasBase.getInstance();
            if (atlasInstance == null || atlasInstance.getNettyServer() == null)
                return;

            atlasInstance.getNettyServer().broadcastServerRemove(serverId, "Server status changed to " + newStatus);
            return;
        }

        AtlasBase atlasInstance = AtlasBase.getInstance();
        if (atlasInstance == null || atlasInstance.getNettyServer() == null)
            return;

        atlasInstance.getNettyServer().broadcastServerUpdate(server);
    }

    public void updateServerHeartbeat(String serverId) {
        AtlasServer server = this.servers.get(serverId);
        if (server == null)
            return;

        server.setLastHeartbeat(System.currentTimeMillis());
    }

    protected boolean shouldScaleUp() {
        double utilization = this.getCurrentUtilization();
        double threshold = this.scalerConfig.getGroup().getScaling().getConditions().getScaleUpThreshold();
        int cooldownSeconds = this.getCooldownSeconds();

        int startingServers = (int) this.getAutoScaledServers().stream()
                .filter(server -> server.getServerInfo() != null && server.getServerInfo().getStatus() == ServerStatus.STARTING)
                .count();

        boolean thresholdMet = utilization >= threshold;
        boolean canScale = this.canScaleUp();
        boolean cooldownExpired = Instant.now().isAfter(this.lastScaleUpTime.plusSeconds(cooldownSeconds));

        if (startingServers > 0) {
            thresholdMet = utilization >= 0.9;

            if (thresholdMet) {
                Logger.debug("High utilization ({}%) detected with {} starting servers, allowing scale up",
                        String.format("%.1f", utilization * 100), startingServers);
            }
        }

        if (thresholdMet && canScale && !cooldownExpired) {
            Logger.debug("Scale up conditions met for {} but in cooldown for {} more seconds",
                    this.groupName,
                    cooldownSeconds - Instant.now().getEpochSecond() + this.lastScaleUpTime.getEpochSecond());
        }

        return thresholdMet && canScale && cooldownExpired;
    }

    protected boolean shouldScaleDown() {
        double utilization = this.getCurrentUtilization();
        double threshold = this.scalerConfig.getGroup().getScaling().getConditions().getScaleDownThreshold();
        int cooldownSeconds = this.getCooldownSeconds();

        boolean thresholdMet = utilization <= threshold;
        boolean canScale = this.canScaleDown();
        boolean cooldownExpired = Instant.now().isAfter(this.lastScaleDownTime.plusSeconds(cooldownSeconds));

        if (thresholdMet && canScale && !cooldownExpired) {
            Logger.debug("Scale down conditions met for {} but in cooldown for {} more seconds",
                    this.groupName,
                    cooldownSeconds - Instant.now().getEpochSecond() + this.lastScaleDownTime.getEpochSecond());
        }

        return thresholdMet && canScale && cooldownExpired;
    }

    protected int getCooldownSeconds() {
        return AtlasBase.getInstance().getConfigManager().getAtlasConfig()
                .getAtlas().getScaling().getCooldown();
    }

    protected int getEmptyServerCount() {
        return (int) this.getAutoScaledServers().stream()
                .filter(server -> server.getServerInfo() != null && server.getServerInfo().getOnlinePlayers() == 0)
                .count();
    }


    protected int getMinServers() {
        return this.scalerConfig.getGroup().getServer().getMinServers();
    }

    protected int getMaxServers() {
        return this.scalerConfig.getGroup().getServer().getMaxServers();
    }


    protected boolean isUuidIdentifier() {
        String identifier = this.scalerConfig.getGroup().getServer().getNaming().getIdentifier();
        return identifier != null && identifier.equalsIgnoreCase("uuid");
    }

    protected int extractServerNumber(String serverName) {
        if (this.isUuidIdentifier()) return 0;

        try {
            String pattern = this.scalerConfig.getGroup().getServer().getNaming().getNamePattern();
            String prefix = pattern.substring(0, pattern.indexOf("{id}"));
            String suffix = pattern.substring(pattern.indexOf("{id}") + 4);

            if (!serverName.startsWith(prefix) || !serverName.endsWith(suffix))
                return 0;

            int startIndex = prefix.length();
            int endIndex = serverName.length() - suffix.length();

            if (startIndex >= endIndex)
                return 0;

            String numberPart = serverName.substring(startIndex, endIndex);
            return Integer.parseInt(numberPart);
        } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
            Logger.debug("Could not extract server number from: {} using pattern: {}", serverName, this.scalerConfig.getGroup().getServer().getNaming().getNamePattern());
        }

        return 0;
    }

    public String getScalingStatus() {
        double utilization = this.getCurrentUtilization();
        int autoServers = this.getAutoScaledServers().size();
        int manualServers = this.getManuallyScaledServers().size();
        int totalPlayers = this.getTotalOnlinePlayers();
        int maxServers = this.getMaxServers();

        String maxDisplay = maxServers == -1 ? "∞" : String.valueOf(maxServers);

        return String.format("Group: %s | Utilization: %.1f%% | Auto: %d/%s | Manual: %d | Players: %d",
                this.groupName,
                utilization * 100,
                autoServers,
                maxDisplay,
                manualServers,
                totalPlayers);
    }

    public void checkHeartbeats() {
        long currentTime = System.currentTimeMillis();
        List<AtlasServer> serversToRemove = new ArrayList<>();

        for (AtlasServer server : this.servers.values()) {
            if (server.getServerInfo() == null) continue;

            long timeSinceLastHeartbeat = currentTime - server.getLastHeartbeat();

            if (server.getServerInfo().getStatus() == ServerStatus.RUNNING && timeSinceLastHeartbeat > 15000) {
                if (this.manuallyStopped.contains(server.getServerId())) {
                    Logger.info("Server {} was manually stopped, not restarting due to heartbeat timeout", server.getName());
                    this.handleServerActuallyStopped(server);
                } else {
                    Logger.warn("Server {} hasn't sent heartbeat in {} seconds, marking for removal", server.getName(), timeSinceLastHeartbeat / 1000);
                    serversToRemove.add(server);
                }
            } else if (server.getServerInfo().getStatus() == ServerStatus.STARTING && timeSinceLastHeartbeat > 180000) {
                if (this.manuallyStopped.contains(server.getServerId())) {
                    Logger.info("Starting server {} was manually stopped, not restarting due to heartbeat timeout", server.getName());
                    this.handleServerActuallyStopped(server);
                } else {
                    Logger.warn("Starting server {} hasn't sent heartbeat in {} seconds, marking for removal", server.getName(), timeSinceLastHeartbeat / 1000);
                    serversToRemove.add(server);
                }
            } else if (server.getServerInfo().getStatus() == ServerStatus.STOPPING && timeSinceLastHeartbeat > 15000) {
                Logger.info("Server {} heartbeat timeout during shutdown (container monitoring will handle status)", server.getName());
            }
        }

        for (AtlasServer server : serversToRemove) {
            String serverId = server.getServerId();

            if (this.manuallyStopped.contains(serverId)) {
                Logger.info("Skipping restart of manually stopped server: {}", server.getName());
                this.handleServerActuallyStopped(server);
                continue;
            }
            
            this.pendingRemovals.add(serverId);
            
            if (server.getType() == ServerType.STATIC) {
                this.lifecycleService.stopServer(server).thenRun(() -> {
                    this.pendingRemovals.remove(serverId);
                    Logger.debug("Completed heartbeat timeout stop of STATIC server: {} (removed from pending)", server.getName());
                }).exceptionally(throwable -> {
                    this.pendingRemovals.remove(serverId);
                    Logger.error("Failed to stop STATIC server {} after heartbeat timeout (removed from pending)", server.getName(), throwable);
                    return null;
                });
            } else {
                this.lifecycleService.removeServer(server, DeletionOptions.connectionLost()).thenRun(() -> {
                    this.pendingRemovals.remove(serverId);
                    Logger.debug("Completed heartbeat timeout removal of DYNAMIC server: {} (removed from pending)", server.getName());
                }).exceptionally(throwable -> {
                    this.pendingRemovals.remove(serverId);
                    Logger.error("Failed to remove DYNAMIC server {} after heartbeat timeout (removed from pending)", server.getName(), throwable);
                    return null;
                });
            }
        }

        this.serviceProvider.validateServerState();
    }

    public boolean hasPendingRemovals() {
        return !this.pendingRemovals.isEmpty();
    }

    public int getPendingRemovalCount() {
        return this.pendingRemovals.size();
    }

    public void clearPendingRemovals() {
        this.pendingRemovals.clear();
        Logger.debug("Cleared all pending removals for group: {}", this.groupName);
    }

    public void validateAndCleanupZombieServers() {
        Logger.debug("Running manual zombie server validation for group: {}", this.groupName);
        this.serviceProvider.validateServerState();
    }

    private void handleServerActuallyStopped(AtlasServer server) {
        if (this.manuallyStopped.contains(server.getServerId())) {
            Logger.debug("Server {} was manually stopped and will remain down", server.getName());
        }
        
        Logger.debug("Server {} manual stop handling complete", server.getName());
    }

    public void markServerAsManuallyStopped(String serverId) {
        this.manuallyStopped.add(serverId);
        Logger.debug("Marked server {} as manually stopped", serverId);
    }

    public void clearManualStopFlag(String serverId) {
        this.manuallyStopped.remove(serverId);
        Logger.debug("Cleared manual stop flag for server {}", serverId);
    }

    public void markServerAsRestarting(String serverId) {
        this.currentlyRestarting.add(serverId);
        Logger.debug("Marked server {} as currently restarting", serverId);
    }

    public void clearRestartFlag(String serverId) {
        this.currentlyRestarting.remove(serverId);
        Logger.debug("Cleared restart flag for server {}", serverId);
    }

    public boolean isCurrentlyRestarting(String serverId) {
        return this.currentlyRestarting.contains(serverId);
    }

}
