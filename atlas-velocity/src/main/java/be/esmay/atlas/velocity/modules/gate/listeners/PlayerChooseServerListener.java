package be.esmay.atlas.velocity.modules.gate.listeners;

import be.esmay.atlas.common.models.AtlasServer;
import be.esmay.atlas.velocity.modules.gate.GateModule;
import be.esmay.atlas.velocity.modules.scaling.ScalingModule;
import be.esmay.atlas.velocity.utils.ChatUtils;
import com.jazzkuh.modulemanager.velocity.handlers.listeners.AbstractListener;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public final class PlayerChooseServerListener extends AbstractListener {

    private final GateModule gateModule;

    @Subscribe(order = PostOrder.FIRST)
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        AtlasServer selectedServer = this.gateModule.getNextServerInGroup("Lobby");
        if (selectedServer == null) {
            event.getPlayer().disconnect(ChatUtils.format("<dark_red>Could not find a lobby server due to high traffic! Please try again later."));
            this.gateModule.getLogger().warn("Could not find a lobby server for player {} due to high traffic!", event.getPlayer().getUsername());
            return;
        }

        RegisteredServer registeredServer = this.gateModule.getPlugin().getProxyServer().getServer(selectedServer.getName()).orElse(null);
        if (registeredServer == null) {
            event.getPlayer().disconnect(ChatUtils.format("<dark_red>Could not find the lobby server! Please try again later."));
            this.gateModule.getLogger().warn("Could not find the lobby server {} for player {}!", selectedServer.getName(), event.getPlayer().getUsername());
            return;
        }

        event.setInitialServer(registeredServer);
    }


}
