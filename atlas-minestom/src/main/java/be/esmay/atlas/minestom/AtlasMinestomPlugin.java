package be.esmay.atlas.minestom;

import be.esmay.atlas.minestom.api.AtlasMinestomAPI;
import be.esmay.atlas.minestom.cache.NetworkServerCacheManager;
import be.esmay.atlas.minestom.listeners.MinestomPlayerEventListener;
import be.esmay.atlas.minestom.network.AtlasNetworkClient;
import be.esmay.atlas.minestom.server.MinestomServerInfoManager;
import com.jazzkuh.minestomplugins.Plugin;
import lombok.Getter;
import lombok.Setter;
import net.minestom.server.MinecraftServer;

@Getter
public final class AtlasMinestomPlugin extends Plugin<MinecraftServer> {
    
    @Getter
    private static AtlasMinestomPlugin instance;
    
    private AtlasNetworkClient networkClient;
    private NetworkServerCacheManager cacheManager;
    private MinestomServerInfoManager serverInfoManager;
    private MinestomPlayerEventListener playerEventListener;

    @Setter
    private int maxPlayers = 75; // Default for now, later configurable via config.
    
    public AtlasMinestomPlugin() {
        instance = this;
    }
    
    @Override
    public void onEnable() {
        this.getLogger().info("Initializing Atlas Spigot plugin...");
        
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
            this.getLogger().error("Invalid ATLAS_PORT: {}", atlasPortStr);
            return;
        }
        
        this.cacheManager = new NetworkServerCacheManager();
        this.serverInfoManager = new MinestomServerInfoManager(this);
        
        this.networkClient = new AtlasNetworkClient(
                atlasHost,
                atlasPort,
                authToken,
                serverId,
                this.cacheManager,
                this.serverInfoManager,
                this.getLogger()
        );
        
        this.playerEventListener = new MinestomPlayerEventListener(this.networkClient);

        // The constructor of MinestomPlayerEventListener will automatically register the event listeners
        new MinestomPlayerEventListener(this.networkClient);
        
        AtlasMinestomAPI.initialize(this.cacheManager, this.serverInfoManager, this.networkClient, this);
        this.networkClient.connect().thenRun(() -> this.getLogger().info("Successfully connected to Atlas base")).exceptionally(throwable -> {
            this.getLogger().error("Failed to connect to Atlas base");
            return null;
        });
        
        this.getLogger().info("Atlas Spigot plugin initialized successfully");
    }
    
    @Override
    public void onDisable() {
        this.getLogger().info("Shutting down Atlas Spigot plugin...");
        
        if (this.networkClient != null) {
            this.networkClient.disconnect();
        }
        
        AtlasMinestomAPI.shutdown();
        
        this.getLogger().info("Atlas Spigot plugin shut down successfully");
    }

}