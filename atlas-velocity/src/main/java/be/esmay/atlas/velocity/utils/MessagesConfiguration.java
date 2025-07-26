package be.esmay.atlas.velocity.utils;

import be.esmay.atlas.velocity.utils.configuration.ConfigurateConfig;
import lombok.Getter;
import lombok.SneakyThrows;

import java.nio.file.Path;
import java.util.List;

@Getter
public final class MessagesConfiguration extends ConfigurateConfig {

    private final String version;
    private final String noServerFound;

    private final List<String> kickedMessage;

    private final String alreadyInLobby;
    private final String noServersAvailable;
    private final String connectingToLobby;

    private final String serverStartingNotification;
    private final String serverStartedNotification;
    private final String serverStoppedNotification;

    private final List<String> atlasCommandHelp;
    private final String atlasServerNotFound;
    private final String atlasServerStarted;
    private final String atlasServerStopped;
    private final String atlasServerRestarted;
    private final String atlasNoPermission;
    private final String atlasUsageStart;
    private final String atlasUsageStop;
    private final String atlasUsageRestart;

    @SneakyThrows
    public MessagesConfiguration(Path folder) {
        super(folder, "messages.yml");

        this.version = this.rootNode.node("_version").getString("1");

        this.noServerFound = this.rootNode.node("messages", "gate", "join", "no_server_found").getString("<dark_red>Could not find a lobby server due to high traffic! Please try again later.");
        this.kickedMessage = this.rootNode.node("messages", "gate", "kicked", "message").getList(String.class, List.of(
                "<dark_red>You have been kicked from %1:",
                "<dark_red>%2"
        ));

        this.alreadyInLobby = this.rootNode.node("messages", "gate", "lobby-command", "already_in_lobby").getString("<dark_red>You are already in a lobby!");
        this.noServersAvailable = this.rootNode.node("messages", "gate", "lobby-command", "no_servers_available").getString("<dark_red>No lobby servers are available at the moment.");
        this.connectingToLobby = this.rootNode.node("messages", "gate", "lobby-command", "connecting_to_lobby").getString("<green>Connected you to <dark_green>%1<green>!");

        this.serverStartingNotification = this.rootNode.node("messages", "scaling", "server-starting").getString("<dark_gray>[<yellow>+<dark_gray>] <gray>%1 <dark_gray>» <gray>%2");
        this.serverStartedNotification = this.rootNode.node("messages", "scaling", "server-started").getString("<dark_gray>[<green>✔<dark_gray>] <gray>%1 <dark_gray>» <gray>%2");
        this.serverStoppedNotification = this.rootNode.node("messages", "scaling", "server-stopped").getString("<dark_gray>[<dark_red>✘<dark_gray>] <gray>%1 <dark_gray>» <gray>%2");

        this.atlasCommandHelp = this.rootNode.node("messages", "atlas", "help").getList(String.class, List.of(
                "<gold>Atlas Commands:",
                "<yellow>/atlas start <server><gray> - Start a server",
                "<yellow>/atlas stop <server><gray> - Stop a server",
                "<yellow>/atlas restart <server><gray> - Restart a server"
        ));
        this.atlasServerNotFound = this.rootNode.node("messages", "atlas", "server-not-found").getString("<red>Server not found: <dark_red>%1");
        this.atlasServerStarted = this.rootNode.node("messages", "atlas", "start", "success").getString("<green>Starting server: <dark_green>%1");
        this.atlasServerStopped = this.rootNode.node("messages", "atlas", "stop", "success").getString("<red>Stopping server: <dark_red>%1");
        this.atlasServerRestarted = this.rootNode.node("messages", "atlas", "restart", "success").getString("<yellow>Restarting server: <gold>%1");
        this.atlasNoPermission = this.rootNode.node("messages", "atlas", "no-permission").getString("<red>You don't have permission to use this command.");
        this.atlasUsageStart = this.rootNode.node("messages", "atlas", "start", "usage").getString("<red>Usage: /atlas start <server>");
        this.atlasUsageStop = this.rootNode.node("messages", "atlas", "stop", "usage").getString("<red>Usage: /atlas stop <server>");
        this.atlasUsageRestart = this.rootNode.node("messages", "atlas", "restart", "usage").getString("<red>Usage: /atlas restart <server>");

        this.saveConfiguration();
    }
}
