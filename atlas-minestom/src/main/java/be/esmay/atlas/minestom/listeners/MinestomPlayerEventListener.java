package be.esmay.atlas.minestom.listeners;

import be.esmay.atlas.minestom.network.AtlasNetworkClient;
import net.minestom.server.MinecraftServer;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;

import java.time.temporal.ChronoUnit;

public final class MinestomPlayerEventListener {

    private final AtlasNetworkClient networkClient;

    public MinestomPlayerEventListener(AtlasNetworkClient networkClient) {
        this.networkClient = networkClient;

        MinecraftServer.getGlobalEventHandler().addListener(PlayerSpawnEvent.class, (event) -> {
            if (!event.isFirstSpawn()) return;

            MinecraftServer.getSchedulerManager().buildTask(this.networkClient::sendServerInfoUpdate).delay(100, ChronoUnit.MILLIS).schedule();
        });

        MinecraftServer.getGlobalEventHandler().addListener(PlayerDisconnectEvent.class, (event) ->
                MinecraftServer.getSchedulerManager().buildTask(this.networkClient::sendServerInfoUpdate).delay(100, ChronoUnit.MILLIS).schedule());
    }
}