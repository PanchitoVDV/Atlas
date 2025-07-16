package be.esmay.atlas.spigot.listeners;

import be.esmay.atlas.spigot.AtlasSpigotPlugin;
import be.esmay.atlas.spigot.network.AtlasNetworkClient;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.slf4j.Logger;

@RequiredArgsConstructor
public final class SpigotPlayerEventListener implements Listener {

    private final AtlasNetworkClient networkClient;

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(AtlasSpigotPlugin.getInstance(), this.networkClient::sendServerInfoUpdate, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Bukkit.getScheduler().runTaskLater(AtlasSpigotPlugin.getInstance(), this.networkClient::sendServerInfoUpdate, 1L);
    }
}