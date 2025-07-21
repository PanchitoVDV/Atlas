package be.esmay.atlas.common.models;

import be.esmay.atlas.common.enums.ServerStatus;
import be.esmay.atlas.common.enums.ServerType;
import lombok.Builder;
import lombok.Data;
import lombok.Setter;

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
}