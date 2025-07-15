package be.esmay.atlas.spigot.server;

import be.esmay.atlas.common.enums.ServerStatus;
import be.esmay.atlas.common.models.ServerInfo;
import be.esmay.atlas.spigot.AtlasSpigotPlugin;
import lombok.RequiredArgsConstructor;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;

@RequiredArgsConstructor
public final class SpigotServerInfoManager {

    private final AtlasSpigotPlugin plugin;

    public ServerInfo getCurrentServerInfo() {
        int onlinePlayers = this.plugin.getServer().getOnlinePlayers().size();
        int maxPlayers = this.plugin.getServer().getMaxPlayers();
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
        for (Player player : this.plugin.getServer().getOnlinePlayers()) {
            playerNames.add(player.getName());
        }

        return playerNames;
    }
}