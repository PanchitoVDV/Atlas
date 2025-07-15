package be.esmay.atlas.spigot;

import be.esmay.atlas.spigot.api.AtlasSpigotAPI;
import be.esmay.atlas.spigot.cache.NetworkServerCacheManager;
import be.esmay.atlas.spigot.listeners.SpigotPlayerEventListener;
import be.esmay.atlas.spigot.network.AtlasNetworkClient;
import be.esmay.atlas.spigot.server.SpigotServerInfoManager;
import lombok.Getter;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Getter
public final class AtlasSpigotPlugin extends JavaPlugin {
    
    @Getter
    private static AtlasSpigotPlugin instance;
    
    private AtlasNetworkClient networkClient;
    private NetworkServerCacheManager cacheManager;
    private SpigotServerInfoManager serverInfoManager;
    private SpigotPlayerEventListener playerEventListener;
    
    public AtlasSpigotPlugin() {
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
            this.getLogger().severe("Missing required environment variables:");
            this.getLogger().severe("ATLAS_HOST: " + atlasHost);
            this.getLogger().severe("ATLAS_PORT: " + atlasPortStr);
            this.getLogger().severe("ATLAS_NETTY_KEY: " + (authToken != null ? "[REDACTED]" : "null"));
            this.getLogger().severe("SERVER_UUID: " + serverId);
            this.getServer().getPluginManager().disablePlugin(this);
            return;
        }
        
        int atlasPort;
        try {
            atlasPort = Integer.parseInt(atlasPortStr);
        } catch (NumberFormatException e) {
            this.getLogger().severe("Invalid ATLAS_PORT: " + atlasPortStr);
            this.getServer().getPluginManager().disablePlugin(this);
            return;
        }
        
        this.cacheManager = new NetworkServerCacheManager();
        this.serverInfoManager = new SpigotServerInfoManager(this);
        
        this.networkClient = new AtlasNetworkClient(
                atlasHost,
                atlasPort,
                authToken,
                serverId,
                this.cacheManager,
                this.serverInfoManager,
                this.getLogger()
        );
        
        this.playerEventListener = new SpigotPlayerEventListener(this.networkClient);
        this.getServer().getPluginManager().registerEvents(this.playerEventListener, this);
        
        AtlasSpigotAPI.initialize(this.cacheManager, this.serverInfoManager, this.networkClient);
        this.networkClient.connect().thenRun(() -> this.getLogger().info("Successfully connected to Atlas base")).exceptionally(throwable -> {
            this.getLogger().severe("Failed to connect to Atlas base");
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
        
        AtlasSpigotAPI.shutdown();
        
        this.getLogger().info("Atlas Spigot plugin shut down successfully");
    }

}