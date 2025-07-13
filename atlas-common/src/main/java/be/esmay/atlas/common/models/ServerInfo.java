package be.esmay.atlas.common.models;

import be.esmay.atlas.common.enums.ServerStatus;
import be.esmay.atlas.common.enums.ServerType;
import lombok.Builder;
import lombok.Data;

import java.util.Set;

@Data
@Builder
public final class ServerInfo {

    private String serverId;
    private String name;
    private String group;
    private String workingDirectory;

    private String address;
    private int port;

    private ServerType type;
    private ServerStatus status;

    private int onlinePlayers;
    private int maxPlayers;
    private Set<String> onlinePlayerNames;

    private long createdAt;
    private long lastHeartbeat;
    private String serviceProviderId;
    private boolean isManuallyScaled;

}
