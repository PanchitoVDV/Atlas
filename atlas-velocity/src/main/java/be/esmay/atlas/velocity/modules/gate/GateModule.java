package be.esmay.atlas.velocity.modules.gate;

import be.esmay.atlas.common.enums.ServerStatus;
import be.esmay.atlas.common.models.AtlasServer;
import be.esmay.atlas.velocity.AtlasVelocityPlugin;
import be.esmay.atlas.velocity.modules.gate.commands.LobbyCommand;
import be.esmay.atlas.velocity.modules.gate.listeners.PlayerChooseServerListener;
import be.esmay.atlas.velocity.modules.gate.listeners.PlayerKickedFromServerListener;
import be.esmay.atlas.velocity.modules.scaling.ScalingModule;
import be.esmay.atlas.velocity.modules.scaling.api.AtlasVelocityAPI;
import com.jazzkuh.modulemanager.velocity.VelocityModule;
import com.jazzkuh.modulemanager.velocity.VelocityModuleManager;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;

import java.util.List;

public final class GateModule extends VelocityModule<AtlasVelocityPlugin> {

    public GateModule(VelocityModuleManager<AtlasVelocityPlugin> owningManager, ScalingModule scalingModule) {
        super(owningManager);
    }

    @Override
    public void onEnable() {
        this.registerComponent(new PlayerChooseServerListener(this));
        this.registerComponent(new PlayerKickedFromServerListener(this));

        CommandManager commandManager = this.getPlugin().getProxyServer().getCommandManager();
        CommandMeta commandMeta = commandManager.metaBuilder("lobby")
                .aliases("l", "hub", "spawn", "leave")
                .plugin(this.getPlugin())
                .build();

        commandManager.register(commandMeta, new LobbyCommand(this));
    }

    public AtlasServer getNextServerInGroup(String group) {
        return this.getNextServerInGroup(group, null);
    }

        public AtlasServer getNextServerInGroup(String group, String excludedServerName) {
        List<AtlasServer> runningServers = AtlasVelocityAPI.getServersByGroup(group)
                .stream()
                .filter(server -> server.getServerInfo() != null && server.getServerInfo().getStatus() == ServerStatus.RUNNING)
                .filter(server -> !server.getName().equalsIgnoreCase(excludedServerName))
                .toList();

        if (runningServers.isEmpty()) {
            return null;
        }

        return runningServers.stream()
                .min((s1, s2) -> {
                    int players1 = s1.getServerInfo().getOnlinePlayers();
                    int players2 = s2.getServerInfo().getOnlinePlayers();

                    if (players1 != players2) {
                        return Integer.compare(players1, players2);
                    }

                    return Long.compare(s1.getCreatedAt(), s2.getCreatedAt());
                })
                .orElse(null);
    }
}
