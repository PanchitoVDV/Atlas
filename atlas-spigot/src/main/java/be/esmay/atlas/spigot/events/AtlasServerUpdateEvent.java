package be.esmay.atlas.spigot.events;

import be.esmay.atlas.common.models.AtlasServer;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

@Getter
@RequiredArgsConstructor
public final class AtlasServerUpdateEvent extends Event {
    
    private static final HandlerList handlers = new HandlerList();
    
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
    
    @Override
    public HandlerList getHandlers() {
        return handlers;
    }
    
    public static HandlerList getHandlerList() {
        return handlers;
    }
}