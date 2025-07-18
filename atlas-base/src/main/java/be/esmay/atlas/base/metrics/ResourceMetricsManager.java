package be.esmay.atlas.base.metrics;

import be.esmay.atlas.base.AtlasBase;
import be.esmay.atlas.base.provider.ServiceProvider;
import be.esmay.atlas.base.utils.Logger;
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
        this.scheduler.scheduleAtFixedRate(this::updateMetrics, 0, 30, TimeUnit.SECONDS);
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
        return this.metricsCache.get(serverId);
    }

    private void updateMetrics() {
        try {
            ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();
            provider.getAllServers().thenAccept(servers -> {
                for (AtlasServer server : servers) {
                    this.updateServerMetrics(server);
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