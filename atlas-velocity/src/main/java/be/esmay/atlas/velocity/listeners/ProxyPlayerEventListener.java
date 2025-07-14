package be.esmay.atlas.velocity.listeners;

import be.esmay.atlas.velocity.network.AtlasNetworkClient;
import be.esmay.atlas.velocity.proxy.ProxyServerInfoManager;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;

public final class ProxyPlayerEventListener {
    
    private final ProxyServerInfoManager serverInfoManager;
    private final AtlasNetworkClient networkClient;
    
    public ProxyPlayerEventListener(ProxyServerInfoManager serverInfoManager, AtlasNetworkClient networkClient) {
        this.serverInfoManager = serverInfoManager;
        this.networkClient = networkClient;
    }
    
    @Subscribe(order = PostOrder.LATE)
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        
        this.serverInfoManager.addPlayer(player);
        this.networkClient.sendServerInfoUpdate();
    }
    
    @Subscribe(order = PostOrder.LATE)
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        
        this.serverInfoManager.removePlayer(player);
        this.networkClient.sendServerInfoUpdate();
    }
    
    @Subscribe(order = PostOrder.LATE)
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        String previousServer = event.getPreviousServer()
            .map(serverConnection -> serverConnection.getServerInfo().getName())
            .orElse(null);
        String newServer = event.getServer().getServerInfo().getName();
        
        this.serverInfoManager.updatePlayerRouting(player, previousServer, newServer);
    }
    
}