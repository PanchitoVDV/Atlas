package be.esmay.atlas.base.scaler;

import be.esmay.atlas.base.AtlasBase;
import be.esmay.atlas.base.config.impl.ScalerConfig;
import be.esmay.atlas.base.lifecycle.ServerLifecycleManager;
import be.esmay.atlas.base.provider.ServiceProvider;
import be.esmay.atlas.base.utils.Logger;
import be.esmay.atlas.common.enums.ScaleType;
import be.esmay.atlas.common.enums.ServerStatus;
import be.esmay.atlas.common.enums.ServerType;
import be.esmay.atlas.common.models.ServerInfo;
import lombok.Getter;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
    protected final Map<String, ServerInfo> servers = new ConcurrentHashMap<>();

    protected volatile boolean shutdown = false;
    protected volatile boolean paused = false;
    protected volatile Instant lastScaleUpTime = Instant.MIN;
    protected volatile Instant lastScaleDownTime = Instant.MIN;

    public Scaler(String groupName, ScalerConfig scalerConfig) {
        this.groupName = groupName;
        this.scalerConfig = scalerConfig;
        this.serviceProvider = AtlasBase.getInstance().getProviderManager().getProvider();
        this.lifecycleManager = new ServerLifecycleManager();
    }

    public abstract ScaleType needsScaling();

    public int getLowestAvailableNumber() {
        if (this.isUuidIdentifier()) return 1;

        Set<Integer> usedNumbers = this.servers.values().stream()
                .map(server -> this.extractServerNumber(server.getName()))
                .filter(num -> num > 0)
                .collect(Collectors.toSet());

        for (int i = 1; i <= this.getMaxServers(); i++) {
            if (!usedNumbers.contains(i)) {
                return i;
            }
        }

        return usedNumbers.size() + 1;
    }

    public void scaleServers() {
        this.checkHeartbeats();

        if (this.paused) {
            Logger.debug("Scaling is paused for group: {}", this.groupName);
            return;
        }

        ScaleType scaleType = this.needsScaling();

        switch (scaleType) {
            case UP -> {
                Logger.debug("Scaling up servers for group: {} (current utilization: {}%)",
                        this.groupName, String.format("%.2f", this.getCurrentUtilization() * 100));

                CompletableFuture<Void> upscaleFuture = this.autoUpscale();
                
                upscaleFuture.exceptionally(throwable -> {
                    Logger.error("Failed to auto-scale up servers for group: {}", this.groupName, throwable);
                    return null;
                });
            }
            case DOWN -> {
                Logger.debug("Scaling down servers for group: {} (current utilization: {}%)",
                        this.groupName, String.format("%.2f", this.getCurrentUtilization() * 100));
                this.autoScaleDown();
            }
            case NONE -> Logger.debug("No scaling needed for group: {} (current utilization: {}%)",
                    this.groupName, String.format("%.2f", this.getCurrentUtilization() * 100));
        }
    }

    public CompletableFuture<Void> upscale() {
        ServerType serverType = ServerType.valueOf(this.scalerConfig.getGroup().getServer().getType().toUpperCase());
        String serverId = this.getNextIdentifier(); // Use existing logic
        Logger.info("Manually scaling up server: {} for group: {}", serverId, this.groupName);

        CompletableFuture<ServerInfo> createFuture = this.lifecycleManager.createServer(
            this.serviceProvider, 
            this.scalerConfig.getGroup(), 
            serverId, 
            serverId, 
            serverType, 
            true
        );
        
        CompletableFuture<Void> acceptFuture = createFuture.thenAccept(server -> {
            this.addServer(server);
            Logger.info("Successfully created manual server: {}", server.getName());
        });
        
        return acceptFuture.exceptionally(throwable -> {
            Logger.error("Failed to create manual server: {}", serverId, throwable);
            return null;
        });
    }

    public CompletableFuture<Void> autoUpscale() {
        int currentAutoServers = this.getAutoScaledServers().size();
        int minServers = this.getMinServers();

        if (currentAutoServers < minServers) {
            int serversToCreate = minServers - currentAutoServers;
            Logger.debug("Scaling up to minimum servers for group: {}. Creating {} servers to reach minimum of {}",
                    this.groupName, serversToCreate, minServers);

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
        String serverId = this.getNextIdentifier(); // Use existing logic
        Logger.debug("Creating auto-scaled server: {} for group: {}", serverId, this.groupName);

        CompletableFuture<ServerInfo> createFuture = this.lifecycleManager.createServer(
            this.serviceProvider, 
            this.scalerConfig.getGroup(), 
            serverId, 
            serverId, 
            serverType, 
            false
        );
        
        CompletableFuture<Void> acceptFuture = createFuture.thenAccept(server -> {
            this.addServer(server);
            Logger.info("Started server: {}", server.getName());
        });
        
        return acceptFuture.exceptionally(throwable -> {
            Logger.error("Failed to create auto-scaled server: {}", serverId, throwable);
            return null;
        });
    }

    private void autoScaleDown() {
        if (!this.canScaleDown())
            return;

        ServerInfo serverToRemove = this.getAutoScaledServers().stream()
                .filter(server -> server.getStatus() == ServerStatus.RUNNING)
                .min(Comparator.comparingInt(ServerInfo::getOnlinePlayers)
                        .thenComparing(ServerInfo::getCreatedAt, Comparator.reverseOrder()))
                .orElse(null);

        if (serverToRemove != null) {
            Logger.info("Auto-scaling down server: {} (players: {}) from group: {}",
                    serverToRemove.getName(), serverToRemove.getOnlinePlayers(), this.groupName);

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
        ServerInfo serverToRemove = this.servers.values().stream()
                .filter(server -> server.getStatus() == ServerStatus.RUNNING)
                .min(Comparator.comparingInt(ServerInfo::getOnlinePlayers)
                        .thenComparing(ServerInfo::getCreatedAt, Comparator.reverseOrder()))
                .orElse(null);

        if (serverToRemove != null) {
            String serverType = serverToRemove.isManuallyScaled() ? "manual" : "auto-scaled";
            Logger.info("Manually scaling down {} server: {} (players: {}) from group: {}",
                    serverType, serverToRemove.getName(), serverToRemove.getOnlinePlayers(), this.groupName);

            this.remove(serverToRemove);
        } else {
            Logger.info("No running servers available to scale down in group: {}", this.groupName);
        }
    }

    public void shutdown() {
        Logger.info("Shutting down scaler for group: {}", this.groupName);
        this.shutdown = true;

        CompletableFuture.allOf(
                this.servers.values().stream()
                        .map(this::shutdownServer)
                        .toArray(CompletableFuture[]::new)
        ).join();

        this.servers.clear();
    }

    public String getNextIdentifier() {
        String pattern = this.scalerConfig.getGroup().getServer().getNaming().getNamePattern();

        if (pattern == null || pattern.isEmpty()) {
            pattern = this.groupName.toLowerCase() + "-{id}";
            Logger.warn("No naming pattern configured for group {}, using default: {}", this.groupName, pattern);
        }

        if (this.isUuidIdentifier()) {
            return pattern.replace("{id}", UUID.randomUUID().toString().substring(0, 8));
        }

        int nextNumber = this.getLowestAvailableNumber();
        return pattern.replace("{id}", String.valueOf(nextNumber));
    }

    public void removeManualServer(String serverId) {
        ServerInfo server = this.servers.get(serverId);
        if (server != null && server.isManuallyScaled()) {
            Logger.info("Manually removing server: {} from group: {}", server.getName(), this.groupName);
            this.remove(server);
        } else {
            Logger.warn("Attempted to manually remove non-manual server or non-existent server: {}", serverId);
        }
    }

    public void remove(ServerInfo server) {
        if (server == null || !this.servers.containsKey(server.getServerId()))
            return;

        Logger.debug("Removing server: {} from group: {}", server.getName(), this.groupName);

        this.shutdownServer(server).thenRun(() -> {
            this.servers.remove(server.getServerId());
            Logger.info("Stopped server: {}", server.getName());
        }).exceptionally(throwable -> {
            Logger.error("Failed to stop server {}, keeping in tracking", server.getName(), throwable);
            return null;
        });
    }

    public CompletableFuture<Void> removeAsync(ServerInfo server) {
        if (server == null || !this.servers.containsKey(server.getServerId())) {
            return CompletableFuture.completedFuture(null);
        }

        Logger.debug("Removing server: {} from group: {}", server.getName(), this.groupName);

        CompletableFuture<Void> deleteFuture = this.lifecycleManager.deleteServerCompletely(this.serviceProvider, server);
        CompletableFuture<Void> completionFuture = deleteFuture.thenRun(() -> {
            this.servers.remove(server.getServerId());
            Logger.info("Stopped server: {}", server.getName());
        });
        return completionFuture.exceptionally(throwable -> {
            Logger.error("Failed to stop server {}, keeping in tracking", server.getName(), throwable);
            return null;
        });
    }

    private CompletableFuture<Void> shutdownServer(ServerInfo server) {
        CompletableFuture<Void> deleteFuture = this.lifecycleManager.deleteServerCompletely(this.serviceProvider, server);
        CompletableFuture<Void> completionFuture = deleteFuture.thenRun(() -> Logger.debug("Successfully removed server: {}", server.getName()));
        return completionFuture.exceptionally(throwable -> {
            Logger.error("Failed to shutdown server: {}", server.getName(), throwable);
            return null;
        });
    }

    public List<ServerInfo> getServers() {
        return new ArrayList<>(this.servers.values());
    }

    public List<ServerInfo> getAutoScaledServers() {
        return this.servers.values().stream()
                .filter(server -> !server.isManuallyScaled())
                .collect(Collectors.toList());
    }

    public List<ServerInfo> getManuallyScaledServers() {
        return this.servers.values().stream()
                .filter(ServerInfo::isManuallyScaled)
                .collect(Collectors.toList());
    }

    public int getTotalOnlinePlayers() {
        return this.servers.values().stream()
                .mapToInt(ServerInfo::getOnlinePlayers)
                .sum();
    }

    public int getAutoScaledOnlinePlayers() {
        return this.getAutoScaledServers().stream()
                .mapToInt(ServerInfo::getOnlinePlayers)
                .sum();
    }

    public double getCurrentUtilization() {
        List<ServerInfo> autoServers = this.getAutoScaledServers();
        if (autoServers.isEmpty())
            return 0.0;

        List<ServerInfo> runningServers = autoServers.stream()
                .filter(server -> server.getStatus() == ServerStatus.RUNNING)
                .toList();
        
        int avgMaxPlayers;
        if (!runningServers.isEmpty()) {
            avgMaxPlayers = runningServers.stream()
                    .mapToInt(ServerInfo::getMaxPlayers)
                    .sum() / runningServers.size();
        } else {
            avgMaxPlayers = 20;
        }
        
        int totalPlayers = 0;
        int totalCapacity = 0;
        
        for (ServerInfo server : autoServers) {
            totalPlayers += server.getOnlinePlayers();
            
            if (server.getStatus() == ServerStatus.RUNNING) {
                totalCapacity += server.getMaxPlayers();
            } else if (server.getStatus() == ServerStatus.STARTING) {
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

    public void addServer(ServerInfo server) {
        this.servers.put(server.getServerId(), server);
        
        AtlasBase atlasInstance = AtlasBase.getInstance();
        if (atlasInstance != null && atlasInstance.getNettyServer() != null) {
            atlasInstance.getNettyServer().broadcastServerAdd(server);
        }
    }

    public void updateServerPlayerCount(String serverId, int playerCount) {
        ServerInfo server = this.servers.get(serverId);
        if (server == null)
            return;

        server.setOnlinePlayers(playerCount);
        server.setLastHeartbeat(System.currentTimeMillis());
    }

    public void updateServerCapacity(String serverId, int maxPlayers) {
        ServerInfo server = this.servers.get(serverId);
        if (server == null)
            return;

        server.setMaxPlayers(maxPlayers);
        server.setLastHeartbeat(System.currentTimeMillis());
    }

    protected boolean shouldScaleUp() {
        double utilization = this.getCurrentUtilization();
        double threshold = this.scalerConfig.getGroup().getScaling().getConditions().getScaleUpThreshold();
        int cooldownSeconds = this.getCooldownSeconds();

        int startingServers = (int) this.getAutoScaledServers().stream()
                .filter(server -> server.getStatus() == ServerStatus.STARTING)
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
                .filter(server -> server.getOnlinePlayers() == 0)
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
        List<ServerInfo> serversToRemove = new ArrayList<>();
        
        for (ServerInfo server : this.servers.values()) {
            long timeSinceLastHeartbeat = currentTime - server.getLastHeartbeat();
            
            if (server.getStatus() == ServerStatus.RUNNING && timeSinceLastHeartbeat > 15000) {
                Logger.warn("Server {} hasn't sent heartbeat in {} seconds, marking for removal", 
                        server.getName(), timeSinceLastHeartbeat / 1000);
                serversToRemove.add(server);
            } else if (server.getStatus() == ServerStatus.STARTING && timeSinceLastHeartbeat > 180000) {
                Logger.warn("Starting server {} hasn't sent heartbeat in {} seconds, marking for removal", 
                        server.getName(), timeSinceLastHeartbeat / 1000);
                serversToRemove.add(server);
            }
        }
        
        for (ServerInfo server : serversToRemove) {
            this.remove(server);
        }
    }

}
