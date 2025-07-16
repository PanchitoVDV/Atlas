package be.esmay.atlas.velocity.modules.gate.commands;

import be.esmay.atlas.common.models.AtlasServer;
import be.esmay.atlas.velocity.modules.gate.GateModule;
import be.esmay.atlas.velocity.utils.ChatUtils;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.RawCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public final class LobbyCommand implements RawCommand {

    private final GateModule gateModule;

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!(source instanceof Player player)) {
            source.sendMessage(ChatUtils.format("This command can only be used by players."));
            return;
        }

        if (player.getCurrentServer().map(server -> server.getServerInfo().getName()).orElse("").toLowerCase().startsWith("lobby")) {
            player.sendMessage(ChatUtils.format("<dark_red>You are already in a lobby!"));
            return;
        }

        AtlasServer atlasServer = this.gateModule.getNextServerInGroup("Lobby");
        if (atlasServer == null) {
            source.sendMessage(ChatUtils.format("<dark_red>No lobby servers are available at the moment."));
            return;
        }

        RegisteredServer registeredServer = this.gateModule.getPlugin().getProxyServer().getServer(atlasServer.getName()).orElse(null);
        if (registeredServer == null) {
            source.sendMessage(ChatUtils.format("<dark_red>Lobby server is not available at the moment."));
            return;
        }

        player.createConnectionRequest(registeredServer).fireAndForget();
        player.sendMessage(ChatUtils.format("<green>Connected you to <dark_green>%1<green>!", atlasServer.getName()));
    }
}
