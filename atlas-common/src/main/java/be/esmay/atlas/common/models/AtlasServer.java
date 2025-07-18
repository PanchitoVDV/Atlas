package be.esmay.atlas.common.models;

import be.esmay.atlas.common.enums.ServerType;
import lombok.Builder;
import lombok.Data;

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

    private ServerInfo serverInfo;
    private ServerResourceMetrics resourceMetrics;

    public void updateServerInfo(ServerInfo serverInfo) {
        this.serverInfo = serverInfo;
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