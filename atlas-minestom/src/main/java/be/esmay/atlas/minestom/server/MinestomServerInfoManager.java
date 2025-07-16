package be.esmay.atlas.minestom.server;

import be.esmay.atlas.common.enums.ServerStatus;
import be.esmay.atlas.common.models.ServerInfo;
import be.esmay.atlas.minestom.AtlasMinestomPlugin;
import lombok.RequiredArgsConstructor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;

import java.util.HashSet;
import java.util.Set;

@RequiredArgsConstructor
public final class MinestomServerInfoManager {

    private final AtlasMinestomPlugin plugin;

    public ServerInfo getCurrentServerInfo() {
        int onlinePlayers = MinecraftServer.getConnectionManager().getOnlinePlayers().size();
        int maxPlayers = this.plugin.getMaxPlayers();
        Set<String> onlinePlayerNames = this.getOnlinePlayerNames();

        return ServerInfo.builder()
                .status(ServerStatus.RUNNING)
                .onlinePlayers(onlinePlayers)
                .maxPlayers(maxPlayers)
                .onlinePlayerNames(onlinePlayerNames)
                .build();
    }

    private Set<String> getOnlinePlayerNames() {
        Set<String> playerNames = new HashSet<>();
        for (Player player : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            playerNames.add(player.getUsername());
        }

        return playerNames;
    }
}