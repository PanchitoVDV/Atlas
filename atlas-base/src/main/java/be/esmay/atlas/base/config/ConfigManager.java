package be.esmay.atlas.base.config;

import be.esmay.atlas.base.config.impl.AtlasConfig;
import be.esmay.atlas.base.utils.Logger;
import lombok.Getter;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

@Getter
public final class ConfigManager {

    private AtlasConfig atlasConfig;

    public void initialize() {
        Logger.info("Enabling configuration manager...");

        this.atlasConfig = new AtlasConfig();
        this.initializeGroupsFolder();
    }

    private void initializeGroupsFolder() {
        File groupsFolder = new File(System.getProperty("user.dir"), "groups");

        if (!groupsFolder.exists()) {
            groupsFolder.mkdirs();
        }

        File[] files = groupsFolder.listFiles();
        if (files == null || files.length == 0) {
            this.copyExampleGroup(groupsFolder);
        }
    }

    private void copyExampleGroup(File groupsFolder) {
        File targetFile = new File(groupsFolder, "_example.yml");

        try (InputStream resourceStream = getClass().getClassLoader()
                .getResourceAsStream("groups/_example.yml")) {

            if (resourceStream == null) {
                Logger.error("Could not find groups/_example.yml in resources");
                return;
            }

            Files.copy(resourceStream, targetFile.toPath());
        } catch (IOException e) {
            Logger.error("Failed to copy example group configuration: " + e.getMessage());
        }
    }
}