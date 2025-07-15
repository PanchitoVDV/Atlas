package be.esmay.atlas.velocity.listeners;

import be.esmay.atlas.velocity.network.AtlasNetworkClient;
import be.esmay.atlas.velocity.proxy.ProxyServerInfoManager;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public final class ProxyPlayerEventListener {

    private final AtlasNetworkClient networkClient;

    @Subscribe(order = PostOrder.LATE)
    public void onPostLogin(PostLoginEvent event) {
        this.networkClient.sendServerInfoUpdate();
    }
    
    @Subscribe(order = PostOrder.LATE)
    public void onDisconnect(DisconnectEvent event) {
        this.networkClient.sendServerInfoUpdate();
    }
    
}