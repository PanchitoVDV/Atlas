package be.esmay.atlas.velocity.events;

import be.esmay.atlas.common.models.AtlasServer;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public final class AtlasServerUpdateEvent {
    
    private final AtlasServer currentServerInfo;
    private final AtlasServer previousServerInfo;

    public boolean isServerAdded() {
        return this.currentServerInfo != null && this.previousServerInfo == null;
    }
    
    public boolean isServerRemoved() {
        return this.currentServerInfo == null && this.previousServerInfo != null;
    }
    
    public boolean isServerUpdated() {
        return this.currentServerInfo != null && this.previousServerInfo != null;
    }
    
    public String getServerId() {
        if (this.currentServerInfo != null) {
            return this.currentServerInfo.getServerId();
        }

        if (this.previousServerInfo != null) {
            return this.previousServerInfo.getServerId();
        }

        return null;
    }
    
}