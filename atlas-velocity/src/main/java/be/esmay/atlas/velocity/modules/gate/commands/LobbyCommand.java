package be.esmay.atlas.velocity.modules.gate.commands;

import be.esmay.atlas.common.models.AtlasServer;
import be.esmay.atlas.velocity.modules.gate.GateModule;
import be.esmay.atlas.velocity.modules.scaling.api.AtlasVelocityAPI;
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

        AtlasServer currentAtlasServer = AtlasVelocityAPI.getServerByName(player.getCurrentServer().map(server -> server.getServerInfo().getName()).orElse("Unknown")).orElse(null);
        if (currentAtlasServer == null || currentAtlasServer.getGroup().trim().equalsIgnoreCase(this.gateModule.getPlugin().getDefaultConfiguration().getLobbyGroup())) {
            player.sendMessage(ChatUtils.format(this.gateModule.getPlugin().getMessagesConfiguration().getAlreadyInLobby()));
            return;
        }

        AtlasServer atlasServer = this.gateModule.getNextServerInGroup(this.gateModule.getPlugin().getDefaultConfiguration().getLobbyGroup());
        if (atlasServer == null) {
            source.sendMessage(ChatUtils.format(this.gateModule.getPlugin().getMessagesConfiguration().getNoServersAvailable()));
            return;
        }

        RegisteredServer registeredServer = this.gateModule.getPlugin().getProxyServer().getServer(atlasServer.getName()).orElse(null);
        if (registeredServer == null) {
            source.sendMessage(ChatUtils.format(this.gateModule.getPlugin().getMessagesConfiguration().getNoServersAvailable()));
            return;
        }

        player.createConnectionRequest(registeredServer).fireAndForget();
        player.sendMessage(ChatUtils.format(this.gateModule.getPlugin().getMessagesConfiguration().getConnectingToLobby(), atlasServer.getName()));
    }
}
