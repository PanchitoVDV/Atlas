package be.esmay.atlas.base.activity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class ActivityBuilder {

    private final ActivityService activityService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ActivityType activityType;
    
    private String serverId;
    private String serverName;
    private String groupName;
    private String triggeredBy = "system";
    private String description;
    private Map<String, Object> metadata = new HashMap<>();

    ActivityBuilder(ActivityService activityService, ActivityType activityType) {
        this.activityService = activityService;
        this.activityType = activityType;
    }

    public ActivityBuilder serverId(String serverId) {
        this.serverId = serverId;
        return this;
    }

    public ActivityBuilder serverName(String serverName) {
        this.serverName = serverName;
        return this;
    }

    public ActivityBuilder groupName(String groupName) {
        this.groupName = groupName;
        return this;
    }

    public ActivityBuilder triggeredBy(String triggeredBy) {
        this.triggeredBy = triggeredBy;
        return this;
    }

    public ActivityBuilder description(String description) {
        this.description = description;
        return this;
    }

    public ActivityBuilder metadata(String key, Object value) {
        this.metadata.put(key, value);
        return this;
    }

    public ActivityBuilder metadata(Map<String, Object> metadata) {
        this.metadata.putAll(metadata);
        return this;
    }

    public ActivityBuilder scalingDetails(String direction, int serversBefore, int serversAfter) {
        this.metadata.put("direction", direction);
        this.metadata.put("servers_before", serversBefore);
        this.metadata.put("servers_after", serversAfter);
        return this;
    }

    public ActivityBuilder serverDetails(String serverId, String serverName) {
        this.serverId = serverId;
        this.serverName = serverName;
        return this;
    }

    public ActivityBuilder playerDetails(int playersBefore, int playersAfter, int capacity) {
        this.metadata.put("players_before", playersBefore);
        this.metadata.put("players_after", playersAfter);
        this.metadata.put("capacity", capacity);
        return this;
    }

    public ActivityBuilder resourceDetails(String resourceType, double usage, double threshold) {
        this.metadata.put("resource_type", resourceType);
        this.metadata.put("usage_percent", usage);
        this.metadata.put("threshold_percent", threshold);
        return this;
    }


    public ActivityBuilder errorDetails(String errorType, String errorMessage) {
        this.metadata.put("error_type", errorType);
        this.metadata.put("error_message", errorMessage);
        return this;
    }

    public CompletableFuture<Void> record() {
        try {
            String metadataJson = null;
            if (!this.metadata.isEmpty()) {
                metadataJson = this.objectMapper.writeValueAsString(this.metadata);
            }

            ServerActivity activity = ServerActivity.builder()
                .activityType(this.activityType)
                .serverId(this.serverId)
                .serverName(this.serverName)
                .groupName(this.groupName)
                .triggeredBy(this.triggeredBy)
                .description(this.description)
                .metadata(metadataJson)
                .timestamp(LocalDateTime.now())
                .build();

            return this.activityService.recordActivityInternal(activity);
        } catch (JsonProcessingException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}