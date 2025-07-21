package be.esmay.atlas.velocity.modules.scaling;

import be.esmay.atlas.velocity.AtlasVelocityPlugin;
import be.esmay.atlas.velocity.modules.scaling.api.AtlasVelocityAPI;
import be.esmay.atlas.velocity.modules.scaling.cache.NetworkServerCacheManager;
import be.esmay.atlas.velocity.modules.scaling.listeners.ProxyPlayerEventListener;
import be.esmay.atlas.velocity.modules.scaling.network.AtlasNetworkClient;
import be.esmay.atlas.velocity.modules.scaling.proxy.ProxyServerInfoManager;
import be.esmay.atlas.velocity.modules.scaling.registry.VelocityServerRegistryManager;
import com.jazzkuh.modulemanager.velocity.VelocityModule;
import com.jazzkuh.modulemanager.velocity.VelocityModuleManager;
import lombok.Getter;

@Getter
public final class ScalingModule extends VelocityModule<AtlasVelocityPlugin> {
    
    private AtlasNetworkClient networkClient;
    private NetworkServerCacheManager cacheManager;
    private ProxyServerInfoManager serverInfoManager;
    private VelocityServerRegistryManager registryManager;
    private ProxyPlayerEventListener playerEventListener;
    
    public ScalingModule(VelocityModuleManager<AtlasVelocityPlugin> owningManager) {
        super(owningManager);
    }

    @Override
    public void onEnable() {
        String atlasHost = System.getenv("ATLAS_HOST");
        String atlasPortStr = System.getenv("ATLAS_PORT");
        String authToken = System.getenv("ATLAS_NETTY_KEY");
        String serverId = System.getenv("SERVER_UUID");

        if (atlasHost == null || atlasPortStr == null || authToken == null || serverId == null) {
            this.getLogger().error("Missing required environment variables:");
            this.getLogger().error("ATLAS_HOST: {}", atlasHost);
            this.getLogger().error("ATLAS_PORT: {}", atlasPortStr);
            this.getLogger().error("ATLAS_NETTY_KEY: {}", authToken != null ? "[REDACTED]" : "null");
            this.getLogger().error("SERVER_UUID: {}", serverId);
            return;
        }

        int atlasPort;
        try {
            atlasPort = Integer.parseInt(atlasPortStr);
        } catch (NumberFormatException e) {
            this.getLogger().error("Invalid ATLAS_BASE_PORT: {}", atlasPortStr);
            return;
        }

        this.cacheManager = new NetworkServerCacheManager();
        this.cacheManager.setProxyServer(this.getPlugin().getProxyServer());
        this.serverInfoManager = new ProxyServerInfoManager(this.getPlugin().getProxyServer());
        this.registryManager = new VelocityServerRegistryManager(this.getPlugin(), this.getPlugin().getProxyServer());

        this.networkClient = new AtlasNetworkClient(
                atlasHost,
                atlasPort,
                authToken,
                serverId,
                this.cacheManager,
                this.serverInfoManager,
                this.registryManager,
                this.getPlugin().getProxyServer(),
                this.getPlugin(),
                this.getLogger()
        );

        this.playerEventListener = new ProxyPlayerEventListener(this.networkClient);
        this.getPlugin().getProxyServer().getEventManager().register(this.getPlugin(), this.playerEventListener);

        AtlasVelocityAPI.initialize(this.cacheManager, this.serverInfoManager);
        this.networkClient.connect().thenRun(() -> this.getLogger().info("Successfully connected to Atlas base")).exceptionally(throwable -> {
            this.getLogger().error("Failed to connect to Atlas base", throwable);
            return null;
        });
    }

    @Override
    public void onDisable() {
        if (this.networkClient != null) {
            this.networkClient.disconnect();
        }

        if (this.playerEventListener != null) {
            this.getPlugin().getProxyServer().getEventManager().unregisterListener(this.getPlugin(), this.playerEventListener);
        }

        AtlasVelocityAPI.shutdown();
    }
}
