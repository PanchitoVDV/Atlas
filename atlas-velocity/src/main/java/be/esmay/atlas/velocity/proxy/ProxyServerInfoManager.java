package be.esmay.atlas.velocity.proxy;

import be.esmay.atlas.common.enums.ServerStatus;
import be.esmay.atlas.common.models.ServerInfo;
import com.velocitypowered.api.proxy.ProxyServer;
import lombok.RequiredArgsConstructor;

import java.util.HashSet;

@RequiredArgsConstructor
public final class ProxyServerInfoManager {

    private final ProxyServer proxyServer;

    public ServerInfo getServerInfo() {
        return ServerInfo.builder()
                .status(ServerStatus.RUNNING)
                .onlinePlayers(this.proxyServer.getAllPlayers().size())
                .maxPlayers(this.proxyServer.getConfiguration().getShowMaxPlayers())
                .onlinePlayerNames(new HashSet<>())
                .build();
    }

}