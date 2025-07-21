package be.esmay.atlas.base.metrics;

import be.esmay.atlas.base.AtlasBase;
import be.esmay.atlas.base.provider.ServiceProvider;
import be.esmay.atlas.base.utils.Logger;
import be.esmay.atlas.common.enums.ServerStatus;
import be.esmay.atlas.common.models.AtlasServer;
import be.esmay.atlas.common.models.ServerResourceMetrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class ResourceMetricsManager {

    private final Map<String, ServerResourceMetrics> metricsCache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    public void start() {
        this.scheduler.scheduleAtFixedRate(this::updateMetrics, 0, 5, TimeUnit.SECONDS);
    }

    public void stop() {
        this.scheduler.shutdown();
        try {
            if (!this.scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                this.scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            this.scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public ServerResourceMetrics getMetrics(String serverId) {
        ServerResourceMetrics cached = this.metricsCache.get(serverId);
        if (cached != null) {
            return cached;
        }

        AtlasServer server = this.findServerById(serverId);
        if (server != null && server.getServerInfo() != null && 
            server.getServerInfo().getStatus() == ServerStatus.STOPPED) {
            long memoryLimit = this.getMemoryLimitFromGroup(server.getGroup());

            return ServerResourceMetrics.builder()
                .cpuUsage(0.0)
                .memoryUsed(0L)
                .memoryTotal(memoryLimit)
                .diskUsed(0L)
                .diskTotal(0L)
                .networkReceiveBytes(0L)
                .networkSendBytes(0L)
                .networkReceiveBandwidth(0.0)
                .networkSendBandwidth(0.0)
                .lastUpdated(System.currentTimeMillis())
                .build();
        }
        
        return null;
    }
    
    private AtlasServer findServerById(String serverId) {
        try {
            ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();
            return provider.getAllServers().get().stream()
                .filter(server -> server.getServerId().equals(serverId))
                .findFirst()
                .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
    
    private long getMemoryLimitFromGroup(String groupName) {
        try {
            AtlasBase atlasBase = AtlasBase.getInstance();
            if (atlasBase.getScalerManager() != null) {
                return atlasBase.getScalerManager().getScalers().stream()
                    .filter(scaler -> scaler.getGroupName().equals(groupName))
                    .findFirst()
                    .map(scaler -> {
                        String memoryStr = scaler.getScalerConfig().getGroup().getServiceProvider().getDocker().getMemory();
                        return this.parseMemoryString(memoryStr);
                    })
                    .orElse(1024L * 1024L * 1024L); // 1GB default
            }
        } catch (Exception e) {
            Logger.debug("Failed to get memory limit from group {}: {}", groupName, e.getMessage());
        }
        return 1024L * 1024L * 1024L; // 1GB default
    }
    
    private long parseMemoryString(String memory) {
        if (memory == null || memory.trim().isEmpty()) {
            return 1024L * 1024L * 1024L; // 1GB default
        }
        
        try {
            memory = memory.trim().toLowerCase();
            long multiplier = 1L;

            if (memory.endsWith("k") || memory.endsWith("kb")) {
                multiplier = 1024L;
                memory = memory.replaceAll("[kb]", "");
            } else if (memory.endsWith("m") || memory.endsWith("mb")) {
                multiplier = 1024L * 1024L;
                memory = memory.replaceAll("[mb]", "");
            } else if (memory.endsWith("g") || memory.endsWith("gb")) {
                multiplier = 1024L * 1024L * 1024L;
                memory = memory.replaceAll("[gb]", "");
            }

            return Long.parseLong(memory.trim()) * multiplier;
        } catch (Exception e) {
            Logger.debug("Failed to parse memory value '{}', using default", memory);
            return 1024L * 1024L * 1024L; // 1GB default
        }
    }
    
    public void updateMetrics(String serverId, ServerResourceMetrics metrics) {
        this.metricsCache.put(serverId, metrics);
    }

    private void updateMetrics() {
        try {
            ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();
            provider.getAllServers().thenAccept(servers -> {
                for (AtlasServer server : servers) {
                    if (server.getServerInfo() != null && 
                        server.getServerInfo().getStatus() == ServerStatus.RUNNING) {
                        this.updateServerMetrics(server);
                    }
                }
            }).exceptionally(throwable -> {
                Logger.error("Failed to update server metrics", throwable);
                return null;
            });
        } catch (Exception e) {
            Logger.error("Error in metrics update cycle", e);
        }
    }

    private void updateServerMetrics(AtlasServer server) {
        try {
            ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();
            provider.getServerResourceMetrics(server.getServerId())
                    .thenAccept(metricsOpt -> {
                        if (metricsOpt.isPresent()) {
                            ServerResourceMetrics metrics = metricsOpt.get();
                            this.metricsCache.put(server.getServerId(), metrics);
                            server.updateResourceMetrics(metrics);
                        }
                    })
                    .exceptionally(throwable -> {
                        Logger.debug("Failed to update metrics for server " + server.getServerId() + ": " + throwable.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            Logger.debug("Failed to update metrics for server " + server.getServerId() + ": " + e.getMessage());
        }
    }
}