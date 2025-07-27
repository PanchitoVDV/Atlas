package be.esmay.atlas.base.config.impl;

import be.esmay.atlas.base.config.ConfigurateConfig;
import be.esmay.atlas.base.utils.Logger;
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
        try {
            this.atlas = this.loadOrCreateConfig("atlas", Atlas.class);
            if (this.atlas == null) {
                Logger.error("Failed to load Atlas configuration - atlas is null");
                throw new RuntimeException("Atlas configuration loading failed");
            }
        } catch (Exception e) {
            Logger.error("Error in AtlasConfig constructor: " + e.getMessage(), e);
            throw new RuntimeException("Failed to initialize AtlasConfig", e);
        }
    }

    @Data
    @ConfigSerializable
    public static class Atlas {

        private Network network;

        @Setting("service-provider")
        private ServiceProvider serviceProvider;

        private Templates templates;

        private S3 s3;

        private Scaling scaling;

        private Database database;

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

        @Setting("host-ip-override")
        private String hostIpOverride;

    }

    @Getter
    @ConfigSerializable
    public static class Templates {

        @Setting("download-on-startup")
        private boolean downloadOnStartup;

        @Setting("cleanup-dynamic-on-shutdown")
        private boolean cleanupDynamicOnShutdown;

        @Setting("clean-plugins-before-templates")
        private boolean cleanPluginsBeforeTemplates = true;

        @Setting("s3-enabled")
        private boolean s3Enabled = false;

        @Setting("s3-bucket")
        private String s3Bucket = "atlas-templates";

        @Setting("s3-path-prefix")
        private String s3PathPrefix = "templates/";

        @Setting("s3-cache")
        private S3Cache s3Cache;

    }

    @Data
    @ConfigSerializable
    public static class S3 {

        private String region = "us-east-1";

        private String endpoint = "https://s3.amazonaws.com";

        @Setting("access-key-id")
        private String accessKeyId;

        @Setting("secret-access-key")
        private String secretAccessKey;

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

    @Data
    @ConfigSerializable
    public static class Database {

        private String type = "h2";

        private String path = "data/atlas.db";

        private String url;

        private String username;

        private String password;

        @Setting("retention-days")
        private int retentionDays = 30;

    }

}