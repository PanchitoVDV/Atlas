package be.esmay.atlas.common.models;

import be.esmay.atlas.common.enums.ServerStatus;
import be.esmay.atlas.common.enums.ServerType;
import lombok.Builder;
import lombok.Data;
import lombok.Setter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

@Data
@Builder
public final class AtlasServer {

    private String serverId;
    private String name;
    private String group;
    private String workingDirectory;

    private String address;
    private int port;

    private ServerType type;
    private long createdAt;
    private long lastHeartbeat;
    private String serviceProviderId;
    private boolean isManuallyScaled;
    private volatile boolean shutdown;
    private volatile boolean shouldRestartAfterStop;

    private ServerInfo serverInfo;
    private ServerResourceMetrics resourceMetrics;

    @Builder.Default
    private Map<String, String> metadata = new ConcurrentHashMap<>();

    @Setter
    private transient BiConsumer<String, ServerStatus> statusChangeListener;

    public void updateServerInfo(ServerInfo serverInfo) {
        ServerStatus oldStatus = this.serverInfo != null ? this.serverInfo.getStatus() : null;
        ServerStatus newStatus = serverInfo != null ? serverInfo.getStatus() : null;
        
        this.serverInfo = serverInfo;

        if (this.statusChangeListener != null && oldStatus != newStatus && newStatus != null) {
            this.statusChangeListener.accept(this.serverId, newStatus);
        }
    }

    public AtlasServer withServerInfo(ServerInfo serverInfo) {
        this.serverInfo = serverInfo;
        return this;
    }
    
    public void updateResourceMetrics(ServerResourceMetrics resourceMetrics) {
        this.resourceMetrics = resourceMetrics;
    }
    
    public AtlasServer withResourceMetrics(ServerResourceMetrics resourceMetrics) {
        this.resourceMetrics = resourceMetrics;
        return this;
    }

    public void setMetadata(String key, String value) {
        if (key == null) return;
        if (this.metadata == null) {
            this.metadata = new ConcurrentHashMap<>();
        }

        this.metadata.put(key, value);
    }

    public void setMetadata(Map<String, String> metadata) {
        if (metadata == null) return;
        if (this.metadata == null) {
            this.metadata = new ConcurrentHashMap<>();
        }

        this.metadata.putAll(metadata);
    }

    public String getMetadata(String key) {
        if (key == null || this.metadata == null) return null;
        return this.metadata.get(key);
    }

    public boolean hasMetadata(String key) {
        if (key == null || this.metadata == null) return false;
        return this.metadata.containsKey(key);
    }

    public void removeMetadata(String key) {
        if (key == null || this.metadata == null) return;
        this.metadata.remove(key);
    }

    public void clearMetadata() {
        if (this.metadata == null) return;
        this.metadata.clear();
    }
}