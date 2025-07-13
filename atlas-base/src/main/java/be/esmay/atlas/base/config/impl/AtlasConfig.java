package be.esmay.atlas.base.config.impl;

import be.esmay.atlas.base.config.ConfigurateConfig;
import lombok.Data;
import lombok.Getter;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Setting;

import java.io.File;

@Getter
public final class AtlasConfig extends ConfigurateConfig {

    private final Atlas atlas;

    public AtlasConfig() {
        super(new File(System.getProperty("user.dir")), "atlas.yml");
        this.atlas = this.loadOrCreateConfig("atlas", Atlas.class);
    }

    @Data
    @ConfigSerializable
    public static class Atlas {

        private Network network;

        private Database database;

        @Setting("service-provider")
        private ServiceProvider serviceProvider;

        private Templates templates;

        private Scaling scaling;

        private Proxy proxy;

    }

    @Data
    @ConfigSerializable
    public static class Network {

        private int port;

        @Setting("api-port")
        private int apiPort;

        @Setting("api-key")
        private String apiKey;

    }

    @Getter
    @ConfigSerializable
    public static class Database {

        private String type;

        private String url;

        private String host;

        private int port;

        private String database;

        private String username;

        private String password;

    }

    @Getter
    @ConfigSerializable
    public static class ServiceProvider {

        private String type;

        private Docker docker;

    }

    @Getter
    @ConfigSerializable
    public static class Docker {

        private String network;

        @Setting("auto-create-network")
        private boolean autoCreateNetwork;

        @Setting("socket-path")
        private String socketPath;

    }

    @Getter
    @ConfigSerializable
    public static class Templates {

        @Setting("download-on-startup")
        private boolean downloadOnStartup;

        @Setting("cleanup-dynamic-on-shutdown")
        private boolean cleanupDynamicOnShutdown;

    }

    @Getter
    @ConfigSerializable
    public static class Scaling {

        @Setting("check-interval")
        private int checkInterval;

        private int cooldown;

    }

    @Getter
    @ConfigSerializable
    public static class Proxy {

        @Setting("auto-manage")
        private boolean autoManage;

        @Setting("min-instances")
        private int minInstances;

        @Setting("max-instances")
        private int maxInstances;

        @Setting("naming-pattern")
        private String namingPattern;

    }
}