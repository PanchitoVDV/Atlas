package be.esmay.atlas.base.config.impl;

import be.esmay.atlas.base.config.ConfigurateConfig;
import be.esmay.atlas.base.utils.Logger;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Builder.Default;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Setting;

import java.io.File;
import java.util.List;
import java.util.Map;

@Getter
public final class ScalerConfig extends ConfigurateConfig {

    private final Group group;

    public ScalerConfig(File file, String fileName) {
        super(file, fileName);

        this.group = this.loadOrCreateConfig("group", Group.class);
    }

    public void updateAndSave() {
        try {
            this.rootNode.node("group").set(Group.class, this.group);
            this.saveConfiguration();
            Logger.debug("Configuration saved for group: {}", this.group.getName());
        } catch (Exception e) {
            Logger.error("Failed to save configuration updates for group: {}", this.group.getName(), e);
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @ConfigSerializable
    public static class Group {

        private String name;

        @Setting("display-name")
        private String displayName;

        @Default
        private int priority = 0;

        private Server server;

        private Scaling scaling;

        private List<String> templates;

        @Setting("service-provider")
        private ServiceProvider serviceProvider;

    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @ConfigSerializable
    public static class Server {

        private String type;

        private Naming naming;

        @Setting("min-servers")
        private int minServers;

        @Setting("max-servers")
        private int maxServers;

    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @ConfigSerializable
    public static class Naming {

        @Setting("identifier")
        private String identifier;

        @Setting("naming-pattern")
        private String namePattern;

    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @ConfigSerializable
    public static class Scaling {

        private String type;

        private Conditions conditions;

    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @ConfigSerializable
    public static class Conditions {

        @Setting("scale-up-threshold")
        private double scaleUpThreshold;

        @Setting("scale-down-threshold")
        private double scaleDownThreshold;

    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @ConfigSerializable
    public static class ServiceProvider {

        private Docker docker;

    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @ConfigSerializable
    public static class Docker {

        private String image;

        private String memory;

        private String cpu;

        private String command;

        private Map<String, String> environment;

        @Setting("volume-mount-path")
        private String volumeMountPath;

        @Setting("working-directory")
        private String workingDirectory;

    }
}
