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

        this.saveConfiguration();
    }
}
