package be.esmay.atlas.velocity;

import be.esmay.atlas.velocity.utils.DefaultConfiguration;
import be.esmay.atlas.velocity.utils.MessagesConfiguration;
import com.google.inject.Inject;
import com.jazzkuh.modulemanager.velocity.IVelocityPlugin;
import com.jazzkuh.modulemanager.velocity.VelocityModuleManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import lombok.Getter;
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
public final class AtlasVelocityPlugin implements IVelocityPlugin {

    @Getter
    private static AtlasVelocityPlugin instance;

    @Getter
    private static VelocityModuleManager<AtlasVelocityPlugin> moduleManager;

    private final ProxyServer proxyServer;
    private final Logger logger;
    private final Path dataDirectory;

    private final DefaultConfiguration defaultConfiguration;
    private final MessagesConfiguration messagesConfiguration;

    @Inject
    public AtlasVelocityPlugin(ProxyServer proxyServer, Logger logger, @DataDirectory Path dataDirectory) {
        instance = this;

        this.proxyServer = proxyServer;
        this.logger = logger;
        this.dataDirectory = dataDirectory;

        this.defaultConfiguration = new DefaultConfiguration(this.dataDirectory);
        this.messagesConfiguration = new MessagesConfiguration(this.dataDirectory);

        moduleManager = new VelocityModuleManager<>(this, this.logger);
        moduleManager.scanModules(getClass());
        moduleManager.load();
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        this.logger.info("Initializing Atlas Velocity plugin...");

        moduleManager.enable();

        this.logger.info("Atlas Velocity plugin initialized successfully");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        this.logger.info("Shutting down Atlas Velocity plugin...");

        moduleManager.disable();

        this.logger.info("Atlas Velocity plugin shut down successfully");
    }

}