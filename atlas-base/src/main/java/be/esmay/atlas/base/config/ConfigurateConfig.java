package be.esmay.atlas.base.config;

import be.esmay.atlas.base.utils.Logger;
import lombok.Getter;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.loader.HeaderMode;
import org.spongepowered.configurate.objectmapping.ObjectMapper;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

@Getter
public abstract class ConfigurateConfig {

    protected final YamlConfigurationLoader loader;
    protected CommentedConfigurationNode rootNode;

    public ConfigurateConfig(File directory, String filename) {
        this(directory, filename, filename);
    }

    public ConfigurateConfig(File directory, String filename, String resourceName) {
        if (!directory.exists()) {
            directory.mkdirs();
        }

        File configFile = new File(directory, filename);

        this.loader = YamlConfigurationLoader.builder()
                .path(configFile.toPath())
                .indent(2)
                .nodeStyle(NodeStyle.BLOCK)
                .headerMode(HeaderMode.PRESET)
                .defaultOptions(options -> options
                        .serializers(builder -> builder.registerAnnotatedObjects(ObjectMapper.factory())))
                .build();

        if (!configFile.exists()) {
            copyFromResources(configFile, resourceName);
        }

        try {
            this.rootNode = this.loader.load();
        } catch (IOException e) {
            Logger.error("Error loading configuration: " + e.getMessage());
            this.rootNode = this.loader.createNode();
        }
    }

    private void copyFromResources(File targetFile, String resourceName) {
        try (InputStream resourceStream = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            if (resourceStream == null) return;

            Files.copy(resourceStream, targetFile.toPath());
        } catch (IOException e) {
            Logger.error("Failed to copy default config from resources: " + e.getMessage());
        }
    }

    public void saveConfiguration() {
        try {
            this.loader.save(this.rootNode);
        } catch (final ConfigurateException e) {
            Logger.error("Unable to save configuration: " + e.getMessage());
        }
    }

    public void reloadConfiguration() {
        try {
            this.rootNode = this.loader.load();
        } catch (IOException e) {
            Logger.error("Error reloading configuration: " + e.getMessage());
        }
    }

    protected <T> T loadOrCreateConfig(String nodeName, Class<T> configClass) {
        try {
            T config = this.rootNode.node(nodeName).get(configClass);
            if (config == null) {
                config = configClass.getDeclaredConstructor().newInstance();
                this.rootNode.node(nodeName).set(configClass, config);
                this.saveConfiguration();
            }

            return config;
        } catch (Exception e) {
            Logger.error("Error loading config: " + e.getMessage(), e);
            try {
                return configClass.getDeclaredConstructor().newInstance();
            } catch (Exception ex) {
                throw new RuntimeException("Failed to create default config instance", ex);
            }
        }
    }
}