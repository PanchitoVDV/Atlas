package be.esmay.atlas.base.scaler;

import be.esmay.atlas.base.AtlasBase;
import be.esmay.atlas.base.config.impl.ScalerConfig;
import be.esmay.atlas.base.provider.ServiceProvider;
import be.esmay.atlas.base.scaler.impl.ProxyScaler;
import be.esmay.atlas.base.utils.Logger;
import be.esmay.atlas.common.models.AtlasServer;
import lombok.Getter;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Getter
public final class ScalerManager {

    private final Set<Scaler> scalers = new HashSet<>();

    private ScheduledExecutorService scheduledExecutor;
    private ScheduledFuture<?> scalingTask;

    private volatile boolean isShuttingDown = false;

    private final AtlasBase atlasBase;

    public ScalerManager(AtlasBase atlasBase) {
        this.atlasBase = atlasBase;
    }

    public void initialize() {
        this.loadScalers();
        this.ensureAllResourcesReady();
        this.startScalingTask();
    }

    private void loadScalers() {
        this.scalers.clear();

        File groupsFolder = new File(System.getProperty("user.dir"), "groups");
        if (!groupsFolder.exists()) {
            groupsFolder.mkdirs();
        }

        Collection<File> groupFiles = FileUtils.listFiles(groupsFolder, new String[]{"yml"}, true).stream()
                .filter(file -> !file.getName().startsWith("_"))
                .toList();

        for (File file : groupFiles) {
            ScalerConfig scalerConfig = new ScalerConfig(file.getParentFile(), file.getName());

            String type = scalerConfig.getGroup().getScaling().getType();
            if (type == null) {
                Logger.error("No scaling type defined in group file " + file.getName());
                continue;
            }

            Scaler scaler = ScalerRegistry.get(type, scalerConfig.getGroup().getDisplayName(), scalerConfig);
            if (scaler == null) {
                Logger.error("Failed to create scaler for type " + type + " in group file " + file.getName());
                continue;
            }

            this.scalers.add(scaler);
            Logger.info("Loaded scaler {} with type {}", scaler.getGroupName(), type);
        }
    }

    private void ensureAllResourcesReady() {
        ServiceProvider provider = this.atlasBase.getProviderManager().getProvider();
        
        Logger.info("Ensuring all resources are ready before starting scalers...");
        
        for (Scaler scaler : this.scalers) {
            try {
                provider.ensureResourcesReady(scaler.getScalerConfig().getGroup()).get();
            } catch (Exception e) {
                Logger.error("Failed to prepare resources for scaler {}: {}", scaler.getGroupName(), e.getMessage());
                throw new RuntimeException("Cannot start scaling - resource preparation failed", e);
            }
        }
        
        Logger.info("All Docker images are ready - starting scalers");
    }

    private void startScalingTask() {
        int checkInterval = this.atlasBase.getConfigManager().getAtlasConfig().getAtlas().getScaling().getCheckInterval();

        this.scheduledExecutor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread thread = new Thread(r, "Atlas-Scaler");
            thread.setDaemon(true);
            return thread;
        });

        Logger.info("Starting scaling task with interval: {} seconds", checkInterval);

        this.scalingTask = this.scheduledExecutor.scheduleWithFixedDelay(
                this::performScalingCheck,
                checkInterval,
                checkInterval,
                TimeUnit.SECONDS
        );
    }

    private void performScalingCheck() {
        if (this.isShuttingDown) {
            return;
        }

        try {
            Logger.debug("Performing scaling check for {} scalers", this.scalers.size());

            List<Scaler> sortedScalers = this.scalers.stream()
                    .sorted(Comparator.comparing((Scaler scaler) -> !(scaler instanceof ProxyScaler))
                            .thenComparingInt(scaler -> scaler.getScalerConfig().getGroup().getPriority()))
                    .toList();

            for (Scaler scaler : sortedScalers) {
                if (this.isShuttingDown) {
                    break;
                }

                try {
                    Logger.debug(scaler.getScalingStatus());
                    scaler.scaleServers();
                } catch (Exception e) {
                    Logger.error("Error during scaling check for group: {}", scaler.getGroupName(), e);
                }
            }
        } catch (Exception e) {
            Logger.error("Unexpected error during scaling check", e);
        }
    }

    public Scaler getScaler(String groupName) {
        Scaler exactMatch = this.scalers.stream()
                .filter(scaler -> scaler.getGroupName().equals(groupName))
                .findFirst()
                .orElse(null);
        
        if (exactMatch != null) {
            return exactMatch;
        }

        Scaler caseInsensitiveMatch = this.scalers.stream()
                .filter(scaler -> scaler.getGroupName().equalsIgnoreCase(groupName))
                .findFirst()
                .orElse(null);
        
        if (caseInsensitiveMatch != null) {
            return caseInsensitiveMatch;
        }

        return this.scalers.stream()
                .filter(scaler -> scaler.getScalerConfig().getGroup().getName() != null && scaler.getScalerConfig().getGroup().getName().equalsIgnoreCase(groupName))
                .findFirst()
                .orElse(null);
    }

    public void reloadScalers() {
        Logger.info("Reloading scaler configurations");
        for (Scaler scaler : this.scalers) {
            try {
                scaler.shutdown();
            } catch (Exception e) {
                Logger.error("Error shutting down scaler during reload: {}", scaler.getGroupName(), e);
            }
        }
        this.loadScalers();
        Logger.info("Scaler configurations reloaded successfully");
    }

    public void shutdown() {
        Logger.info("Shutting down ScalerManager");
        this.isShuttingDown = true;

        if (this.scalingTask != null) {
            this.scalingTask.cancel(false);
        }

        if (this.scheduledExecutor != null) {
            this.scheduledExecutor.shutdown();
            try {
                if (!this.scheduledExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    this.scheduledExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                this.scheduledExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        for (Scaler scaler : this.scalers) {
            try {
                scaler.shutdown();
            } catch (Exception e) {
                Logger.error("Error shutting down scaler for group: {}", scaler.getGroupName(), e);
            }
        }

        this.scalers.clear();
    }

    public AtlasServer getServerFromTracking(String serverId) {
        for (Scaler scaler : this.scalers) {
            AtlasServer server = scaler.getServer(serverId);
            if (server != null) {
                return server;
            }
        }
        return null;
    }

    public List<AtlasServer> getAllServersFromTracking() {
        return this.scalers.stream()
                .flatMap(scaler -> scaler.getServers().stream())
                .toList();
    }

    public List<AtlasServer> getServersByGroupFromTracking(String group) {
        return this.scalers.stream()
                .filter(scaler -> scaler.getGroupName().equals(group))
                .flatMap(scaler -> scaler.getServers().stream())
                .toList();
    }

}

