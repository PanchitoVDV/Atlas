package be.esmay.atlas.base.config.impl;

import be.esmay.atlas.base.config.ConfigurateConfig;
import lombok.Data;
import lombok.Getter;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Setting;

import java.io.File;
import java.util.List;

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

        @Setting("service-provider")
        private ServiceProvider serviceProvider;

        private Templates templates;

        private Scaling scaling;

        private Proxy proxy;

    }

    @Data
    @ConfigSerializable
    public static class Network {

        @Setting("bind-address")
        private String bindAddress = "0.0.0.0";

        private int port;

        @Setting("allowed-networks")
        private List<String> allowedNetworks = List.of("172.17.0.0/16", "10.0.0.0/8");

        @Setting("connection-timeout")
        private int connectionTimeout = 30;

        @Setting("api-host")
        private String apiHost = "127.0.0.1";

        @Setting("api-port")
        private int apiPort;

        @Setting("api-key")
        private String apiKey;

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

        private S3 s3;

    }

    @Data
    @ConfigSerializable
    public static class S3 {

        private boolean enabled = false;

        private String bucket;

        private String region = "us-east-1";

        private String endpoint = "https://s3.amazonaws.com";

        @Setting("access-key-id")
        private String accessKeyId;

        @Setting("secret-access-key")
        private String secretAccessKey;

        @Setting("path-prefix")
        private String pathPrefix = "templates/";

        private S3Cache cache;

    }

    @Data
    @ConfigSerializable
    public static class S3Cache {

        private boolean enabled = true;

        private String directory = "cache/templates";

        @Setting("ttl-seconds")
        private int ttlSeconds = 3600;

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