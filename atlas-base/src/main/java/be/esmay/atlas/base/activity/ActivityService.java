package be.esmay.atlas.base.activity;

import be.esmay.atlas.base.config.impl.AtlasConfig;
import be.esmay.atlas.base.utils.Logger;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
public final class ActivityService {

    private final ActivityRepository repository;
    private final AtlasConfig.Database databaseConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    private ExecutorService asyncExecutor;
    private ScheduledExecutorService cleanupScheduler;

    public void initialize() {
        this.asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.cleanupScheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread thread = new Thread(r, "Atlas-ActivityCleanup");
            thread.setDaemon(true);
            return thread;
        });
        
        this.cleanupScheduler.scheduleAtFixedRate(this::cleanupOldActivities, 1, 6, TimeUnit.HOURS);
        
        Logger.info("ActivityService initialized with retention: {} days", this.databaseConfig.getRetentionDays());
    }

    public CompletableFuture<Void> recordActivity(ActivityType activityType, String description) {
        return this.recordActivity(activityType, null, null, "system", description, null);
    }

    public CompletableFuture<Void> recordActivity(ActivityType activityType, String serverId, String triggeredBy, String description) {
        return this.recordActivity(activityType, serverId, null, triggeredBy, description, null);
    }

    public CompletableFuture<Void> recordActivity(ActivityType activityType, String serverId, String groupName, String triggeredBy, String description) {
        return this.recordActivity(activityType, serverId, groupName, triggeredBy, description, null);
    }

    public CompletableFuture<Void> recordActivity(ActivityType activityType, String serverId, String groupName, String triggeredBy, String description, Map<String, Object> metadata) {
        return CompletableFuture.runAsync(() -> {
            try {
                String metadataJson = null;
                if (metadata != null && !metadata.isEmpty()) {
                    metadataJson = this.objectMapper.writeValueAsString(metadata);
                }

                ServerActivity activity = ServerActivity.builder()
                    .activityType(activityType)
                    .serverId(serverId)
                    .groupName(groupName)
                    .triggeredBy(triggeredBy)
                    .description(description)
                    .metadata(metadataJson)
                    .timestamp(LocalDateTime.now())
                    .build();

                this.repository.save(activity);
                
                Logger.debug("Recorded activity: {} - {}", activityType, description);
            } catch (JsonProcessingException e) {
                Logger.error("Failed to serialize activity metadata", e);
            } catch (Exception e) {
                Logger.error("Failed to record activity: {} - {}", activityType, description, e);
            }
        }, this.asyncExecutor);
    }

    public ActivityBuilder createActivity(ActivityType activityType) {
        return new ActivityBuilder(this, activityType);
    }

    public List<ServerActivity> getRecentActivities(int limit) {
        return this.repository.findRecent(Math.min(limit, 1000));
    }

    public List<ServerActivity> getActivitiesByServer(String serverId, int limit) {
        return this.repository.findByServerId(serverId, Math.min(limit, 500));
    }

    public List<ServerActivity> getActivitiesByGroup(String groupName, int limit) {
        return this.repository.findByGroupName(groupName, Math.min(limit, 500));
    }

    public List<ServerActivity> getActivitiesByType(ActivityType activityType, int limit) {
        return this.repository.findByActivityType(activityType, Math.min(limit, 500));
    }

    public List<ServerActivity> getFilteredActivities(String serverId, String groupName, ActivityType activityType, int limit) {
        return this.repository.findByFilter(serverId, groupName, activityType, Math.min(limit, 500));
    }

    public Optional<ServerActivity> getActivityById(String id) {
        return this.repository.findById(id);
    }

    public long getActivityCount() {
        return this.repository.countAll();
    }

    private void cleanupOldActivities() {
        try {
            int retentionDays = this.databaseConfig.getRetentionDays();
            if (retentionDays <= 0) {
                Logger.debug("Activity retention disabled (retention-days: {})", retentionDays);
                return;
            }

            LocalDateTime cutoffDate = LocalDateTime.now().minusDays(retentionDays);
            int deletedCount = this.repository.deleteOlderThan(cutoffDate);
            
            if (deletedCount > 0) {
                Logger.info("Cleaned up {} activities older than {} days", deletedCount, retentionDays);
            } else {
                Logger.debug("No activities to clean up (retention: {} days)", retentionDays);
            }
        } catch (Exception e) {
            Logger.error("Error during activity cleanup", e);
        }
    }

    public void shutdown() {
        Logger.info("Shutting down ActivityService");
        
        if (this.cleanupScheduler != null) {
            this.cleanupScheduler.shutdown();
            try {
                if (!this.cleanupScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    this.cleanupScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                this.cleanupScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        if (this.asyncExecutor != null) {
            this.asyncExecutor.shutdown();
            try {
                if (!this.asyncExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    this.asyncExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                this.asyncExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    CompletableFuture<Void> recordActivityInternal(ServerActivity activity) {
        return CompletableFuture.runAsync(() -> {
            try {
                this.repository.save(activity);
                Logger.debug("Recorded activity: {} - {}", activity.getActivityType(), activity.getDescription());
            } catch (Exception e) {
                Logger.error("Failed to record activity: {} - {}", activity.getActivityType(), activity.getDescription(), e);
            }
        }, this.asyncExecutor);
    }
}