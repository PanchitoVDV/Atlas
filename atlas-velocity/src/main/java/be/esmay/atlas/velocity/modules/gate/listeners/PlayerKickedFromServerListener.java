package be.esmay.atlas.velocity.modules.gate.listeners;

import be.esmay.atlas.common.models.AtlasServer;
import be.esmay.atlas.velocity.modules.gate.GateModule;
import be.esmay.atlas.velocity.utils.ChatUtils;
import com.jazzkuh.modulemanager.velocity.handlers.listeners.AbstractListener;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.text.Component;

@RequiredArgsConstructor
public final class PlayerKickedFromServerListener extends AbstractListener {

    private final GateModule gateModule;

    @Subscribe(order = PostOrder.FIRST)
    public void onKickedFromServer(KickedFromServerEvent event) {
        Component reason = ChatUtils.format(String.join("\n", this.gateModule.getPlugin().getMessagesConfiguration().getKickedMessage()),
                event.getServer().getServerInfo().getName(), event.getServerKickReason().orElse(ChatUtils.format("Unknown reason.")));

        AtlasServer selectedServer = this.gateModule.getNextServerInGroup(this.gateModule.getPlugin().getDefaultConfiguration().getLobbyGroup(), event.getServer().getServerInfo().getName());
        if (selectedServer == null) {
            event.setResult(KickedFromServerEvent.DisconnectPlayer.create(reason));

            this.gateModule.getLogger().warn("Could not find a lobby server for player {} due to high traffic!", event.getPlayer().getUsername());
            return;
        }

        RegisteredServer registeredServer = this.gateModule.getPlugin().getProxyServer().getServer(selectedServer.getName()).orElse(null);
        if (registeredServer == null) {
            event.setResult(KickedFromServerEvent.DisconnectPlayer.create(reason));

            this.gateModule.getLogger().warn("Could not find the lobby server {} for player {}!", selectedServer.getName(), event.getPlayer().getUsername());
            return;
        }

        event.setResult(KickedFromServerEvent.RedirectPlayer.create(registeredServer, reason));
    }


}
