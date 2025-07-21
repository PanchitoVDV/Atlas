package be.esmay.atlas.velocity.utils;

import be.esmay.atlas.velocity.utils.configuration.ConfigurateConfig;
import lombok.Getter;

import java.nio.file.Path;

@Getter
public final class DefaultConfiguration extends ConfigurateConfig {

    private final String version;
    private final String lobbyGroup;

    public DefaultConfiguration(Path folder) {
        super(folder, "config.yml");

        this.version = this.rootNode.node("_version").getString("1");
        this.lobbyGroup = this.rootNode.node("lobby-group").getString("Lobby");

        this.saveConfiguration();
    }
}
