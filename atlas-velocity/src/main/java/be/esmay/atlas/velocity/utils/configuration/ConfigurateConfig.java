package be.esmay.atlas.velocity.utils.configuration;

import lombok.Getter;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.loader.HeaderMode;
import org.spongepowered.configurate.util.MapFactories;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.nio.file.Path;

@Getter
public abstract class ConfigurateConfig {

    protected final YamlConfigurationLoader loader;
    protected CommentedConfigurationNode rootNode;

    public ConfigurateConfig(Path folder, String name) {
        this.loader = YamlConfigurationLoader.builder()
                .path(folder.resolve(name))
                .indent(2)
                .nodeStyle(NodeStyle.BLOCK)
                .headerMode(HeaderMode.PRESET)
                .defaultOptions(options -> {
                    options = options.mapFactory(MapFactories.sortedNatural());
                    return options;
                })
                .build();

        try {
            this.rootNode = this.loader.load();
            this.saveConfiguration();
        } catch (Exception ignored) {}
    }

    public void saveConfiguration() {
        try {
            this.loader.save(this.rootNode);
        } catch (final Exception ignored) {}
    }
}