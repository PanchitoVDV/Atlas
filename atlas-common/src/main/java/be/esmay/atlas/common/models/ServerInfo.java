package be.esmay.atlas.common.models;

import be.esmay.atlas.common.enums.ServerStatus;
import lombok.Builder;
import lombok.Data;

import java.util.Set;

@Data
@Builder
public final class ServerInfo {

    private ServerStatus status;
    private int onlinePlayers;
    private int maxPlayers;
    private Set<String> onlinePlayerNames;

}
