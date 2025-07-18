package be.esmay.atlas.base.metrics;

import be.esmay.atlas.base.AtlasBase;
import be.esmay.atlas.base.provider.ServiceProvider;
import be.esmay.atlas.base.utils.Logger;
import be.esmay.atlas.common.models.AtlasServer;
import be.esmay.atlas.common.models.ServerResourceMetrics;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Statistics;
import com.github.dockerjava.core.InvocationBuilder;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class ResourceMetricsManager {
    
    private final Map<String, ServerResourceMetrics> metricsCache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final DockerClient dockerClient;
    
    public ResourceMetricsManager(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }
    
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
            String containerId = server.getServiceProviderId();
            if (containerId == null) return;
            
            List<Container> containers = this.dockerClient.listContainersCmd()
                .withIdFilter(List.of(containerId))
                .exec();
                
            if (containers.isEmpty()) return;
            
            InvocationBuilder.AsyncResultCallback<Statistics> callback = new InvocationBuilder.AsyncResultCallback<>();
            this.dockerClient.statsCmd(containerId).withNoStream(true).exec(callback);
            
            Statistics stats = callback.awaitResult();
            callback.close();
            
            if (stats != null) {
                long memoryUsed = stats.getMemoryStats().getUsage();
                long memoryLimit = stats.getMemoryStats().getLimit();
                
                double cpuDelta = stats.getCpuStats().getCpuUsage().getTotalUsage() - 
                                 stats.getPreCpuStats().getCpuUsage().getTotalUsage();
                double systemDelta = stats.getCpuStats().getSystemCpuUsage() - 
                                    stats.getPreCpuStats().getSystemCpuUsage();
                double cpuUsage = 0.0;
                if (systemDelta > 0.0 && cpuDelta > 0.0) {
                    cpuUsage = (cpuDelta / systemDelta) * stats.getCpuStats().getOnlineCpus() * 100.0;
                }
                
                File workingDir = new File(server.getWorkingDirectory());
                long diskTotal = workingDir.getTotalSpace();
                long diskUsed = diskTotal - workingDir.getFreeSpace();
                
                ServerResourceMetrics metrics = ServerResourceMetrics.builder()
                    .cpuUsage(cpuUsage)
                    .memoryUsed(memoryUsed)
                    .memoryTotal(memoryLimit)
                    .diskUsed(diskUsed)
                    .diskTotal(diskTotal)
                    .lastUpdated(System.currentTimeMillis())
                    .build();
                    
                this.metricsCache.put(server.getServerId(), metrics);
                server.updateResourceMetrics(metrics);
            }
        } catch (Exception e) {
            Logger.debug("Failed to update metrics for server " + server.getServerId() + ": " + e.getMessage());
        }
    }
}