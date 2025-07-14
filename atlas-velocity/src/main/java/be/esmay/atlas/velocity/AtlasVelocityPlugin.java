package be.esmay.atlas.velocity;

import be.esmay.atlas.velocity.api.AtlasVelocityAPI;
import be.esmay.atlas.velocity.cache.NetworkServerCacheManager;
import be.esmay.atlas.velocity.listeners.ProxyPlayerEventListener;
import be.esmay.atlas.velocity.network.AtlasNetworkClient;
import be.esmay.atlas.velocity.proxy.ProxyServerInfoManager;
import be.esmay.atlas.velocity.registry.VelocityServerRegistryManager;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import lombok.Getter;
import org.checkerframework.checker.units.qual.A;
import org.slf4j.Logger;

import java.nio.file.Path;

@Getter
@Plugin(
        id = "atlas-velocity",
        name = "AtlasVelocity",
        version = "1.0.0",
        description = "Atlas server management plugin for Velocity",
        authors = {"Esmaybe"}
)
public final class AtlasVelocityPlugin {

    @Getter
    private static AtlasVelocityPlugin instance;

    private final ProxyServer proxyServer;
    private final Logger logger;
    private final Path dataDirectory;

    private AtlasNetworkClient networkClient;
    private NetworkServerCacheManager cacheManager;
    private ProxyServerInfoManager serverInfoManager;
    private VelocityServerRegistryManager registryManager;
    private ProxyPlayerEventListener playerEventListener;

    @Inject
    public AtlasVelocityPlugin(ProxyServer proxyServer, Logger logger, @DataDirectory Path dataDirectory) {
        instance = this;

        this.proxyServer = proxyServer;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        this.logger.info("Initializing Atlas Velocity plugin...");

        String atlasHost = System.getenv("ATLAS_HOST");
        String atlasPortStr = System.getenv("ATLAS_PORT");
        String authToken = System.getenv("ATLAS_NETTY_KEY");
        String serverId = System.getenv("SERVER_UUID");

        if (atlasHost == null || atlasPortStr == null || authToken == null || serverId == null) {
            this.logger.error("Missing required environment variables:");
            this.logger.error("ATLAS_HOST: {}", atlasHost);
            this.logger.error("ATLAS_PORT: {}", atlasPortStr);
            this.logger.error("ATLAS_NETTY_KEY: {}", authToken != null ? "[REDACTED]" : "null");
            this.logger.error("SERVER_UUID: {}", serverId);
            return;
        }

        int atlasPort;
        try {
            atlasPort = Integer.parseInt(atlasPortStr);
        } catch (NumberFormatException e) {
            this.logger.error("Invalid ATLAS_BASE_PORT: {}", atlasPortStr);
            return;
        }

        this.cacheManager = new NetworkServerCacheManager();
        this.cacheManager.setProxyServer(this.proxyServer);
        this.serverInfoManager = new ProxyServerInfoManager(serverId, this.proxyServer);
        this.registryManager = new VelocityServerRegistryManager(this.proxyServer);

        this.networkClient = new AtlasNetworkClient(
                atlasHost,
                atlasPort,
                authToken,
                serverId,
                this.cacheManager,
                this.serverInfoManager,
                this.registryManager,
                this.logger
        );

        this.playerEventListener = new ProxyPlayerEventListener(this.serverInfoManager, this.networkClient);
        this.proxyServer.getEventManager().register(this, this.playerEventListener);

        AtlasVelocityAPI.initialize(this.cacheManager, this.serverInfoManager);
        this.networkClient.connect().thenRun(() -> this.logger.info("Successfully connected to Atlas base")).exceptionally(throwable -> {
            this.logger.error("Failed to connect to Atlas base", throwable);
            return null;
        });

        this.logger.info("Atlas Velocity plugin initialized successfully");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        this.logger.info("Shutting down Atlas Velocity plugin...");

        if (this.networkClient != null) {
            this.networkClient.disconnect();
        }

        if (this.playerEventListener != null) {
            this.proxyServer.getEventManager().unregisterListener(this, this.playerEventListener);
        }

        AtlasVelocityAPI.shutdown();

        this.logger.info("Atlas Velocity plugin shut down successfully");
    }

}