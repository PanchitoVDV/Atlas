package be.esmay.atlas.base.config.impl;

import be.esmay.atlas.base.config.ConfigurateConfig;
import lombok.Data;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Setting;

import java.io.File;
import java.util.List;
import java.util.Map;

public final class ScalerConfig extends ConfigurateConfig {

    public ScalerConfig(File file, String fileName) {
        super(file, fileName);

        this.loadOrCreateConfig("group", Group.class);
    }

    @Data
    @ConfigSerializable
    public static class Group {

        private String name;

        @Setting("display-name")
        private String displayName;

        private Server server;

        private Scaling scaling;

        private List<String> templates;

        @Setting("service-provider")
        private ServiceProvider serviceProvider;

    }

    @Data
    @ConfigSerializable
    public static class Server {

        private String type;

        private Naming naming;

        @Setting("min-servers")
        private int minServers;

        @Setting("max-servers")
        private int maxServers;

        @Setting("max-players-per-server")
        private int maxPlayersPerServer;

    }

    @Data
    @ConfigSerializable
    public static class Naming {

        @Setting("identifier")
        private String identifier;

        @Setting("name-pattern")
        private String namePattern;

    }

    @Data
    @ConfigSerializable
    public static class Scaling {

        private String type;

        private Conditions conditions;

    }

    @Data
    @ConfigSerializable
    public static class Conditions {

        @Setting("scale-up-threshold")
        private double scaleUpThreshold;

        @Setting("scale-down-threshold")
        private double scaleDownThreshold;

        @Setting("min-empty-servers")
        private int minEmptyServers;

    }

    @Data
    @ConfigSerializable
    public static class ServiceProvider {

        private Docker docker;

    }

    @Data
    @ConfigSerializable
    public static class Docker {

        private String image;

        private String memory;

        private String cpu;

        private String command;

        private Map<String, String> environment;

    }
}
