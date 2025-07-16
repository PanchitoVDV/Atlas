package be.esmay.atlas.velocity.listeners;

import be.esmay.atlas.velocity.AtlasVelocityPlugin;
import be.esmay.atlas.velocity.network.AtlasNetworkClient;
import be.esmay.atlas.velocity.proxy.ProxyServerInfoManager;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
public final class ProxyPlayerEventListener {

    private final AtlasNetworkClient networkClient;

    @Subscribe(order = PostOrder.LATE)
    public void onPostLogin(PostLoginEvent event) {
        AtlasVelocityPlugin.getInstance().getProxyServer().getScheduler().buildTask(AtlasVelocityPlugin.getInstance(), this.networkClient::sendServerInfoUpdate).delay(100, TimeUnit.MILLISECONDS).schedule();
    }
    
    @Subscribe(order = PostOrder.LATE)
    public void onDisconnect(DisconnectEvent event) {
        AtlasVelocityPlugin.getInstance().getProxyServer().getScheduler().buildTask(AtlasVelocityPlugin.getInstance(), this.networkClient::sendServerInfoUpdate).delay(100, TimeUnit.MILLISECONDS).schedule();
    }
    
}