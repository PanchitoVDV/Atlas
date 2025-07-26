package be.esmay.atlas.velocity.modules.scaling.commands;

import be.esmay.atlas.common.models.AtlasServer;
import be.esmay.atlas.velocity.modules.scaling.api.AtlasVelocityAPI;
import be.esmay.atlas.velocity.utils.ChatUtils;
import be.esmay.atlas.velocity.utils.MessagesConfiguration;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RequiredArgsConstructor
public final class AtlasCommand implements SimpleCommand {

    private final MessagesConfiguration messagesConfiguration;

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (!source.hasPermission("atlas.command.atlas")) {
            source.sendMessage(ChatUtils.format(this.messagesConfiguration.getAtlasNoPermission()));
            return;
        }

        if (args.length == 0) {
            this.sendHelp(source);
            return;
        }

        String subcommand = args[0].toLowerCase();
        switch (subcommand) {
            case "start" -> this.handleStart(source, args);
            case "stop" -> this.handleStop(source, args);
            case "restart" -> this.handleRestart(source, args);
            case "help" -> this.sendHelp(source);
            default -> this.sendHelp(source);
        }
    }

    private void handleStart(CommandSource source, String[] args) {
        if (args.length < 2) {
            source.sendMessage(ChatUtils.format(this.messagesConfiguration.getAtlasUsageStart()));
            return;
        }

        String serverName = args[1];
        if (!this.serverExists(serverName)) {
            source.sendMessage(ChatUtils.format(this.messagesConfiguration.getAtlasServerNotFound(), serverName));
            return;
        }

        AtlasVelocityAPI.startServer(serverName);
        source.sendMessage(ChatUtils.format(this.messagesConfiguration.getAtlasServerStarted(), serverName));
    }

    private void handleStop(CommandSource source, String[] args) {
        if (args.length < 2) {
            source.sendMessage(ChatUtils.format(this.messagesConfiguration.getAtlasUsageStop()));
            return;
        }

        String serverName = args[1];
        if (!this.serverExists(serverName)) {
            source.sendMessage(ChatUtils.format(this.messagesConfiguration.getAtlasServerNotFound(), serverName));
            return;
        }

        AtlasVelocityAPI.stopServer(serverName);
        source.sendMessage(ChatUtils.format(this.messagesConfiguration.getAtlasServerStopped(), serverName));
    }

    private void handleRestart(CommandSource source, String[] args) {
        if (args.length < 2) {
            source.sendMessage(ChatUtils.format(this.messagesConfiguration.getAtlasUsageRestart()));
            return;
        }

        String serverName = args[1];
        if (!this.serverExists(serverName)) {
            source.sendMessage(ChatUtils.format(this.messagesConfiguration.getAtlasServerNotFound(), serverName));
            return;
        }

        AtlasVelocityAPI.restartServer(serverName);
        source.sendMessage(ChatUtils.format(this.messagesConfiguration.getAtlasServerRestarted(), serverName));
    }

    private void sendHelp(CommandSource source) {
        for (String line : this.messagesConfiguration.getAtlasCommandHelp()) {
            source.sendMessage(ChatUtils.format(line));
        }
    }

    private boolean serverExists(String serverName) {
        return AtlasVelocityAPI.getAllServers().stream()
                .anyMatch(server -> server.getName().equals(serverName));
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        String[] args = invocation.arguments();

        if (args.length == 0 || args.length == 1) {
            String input = args.length == 0 ? "" : args[0].toLowerCase();
            return CompletableFuture.completedFuture(
                    Stream.of("start", "stop", "restart", "help")
                            .filter(subcommand -> subcommand.startsWith(input))
                            .collect(Collectors.toList())
            );
        }

        if (args.length == 2) {
            String subcommand = args[0].toLowerCase();
            if (List.of("start", "stop", "restart").contains(subcommand)) {
                String input = args[1].toLowerCase();
                return CompletableFuture.completedFuture(
                        AtlasVelocityAPI.getAllServers().stream()
                                .map(AtlasServer::getName)
                                .filter(name -> name.toLowerCase().startsWith(input))
                                .collect(Collectors.toList())
                );
            }
        }

        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("atlas.command.atlas");
    }
}
