package be.esmay.atlas.base;

import be.esmay.atlas.base.api.ApiManager;
import be.esmay.atlas.base.commands.CommandManager;
import be.esmay.atlas.base.config.ConfigManager;
import be.esmay.atlas.base.lifecycle.ServerLifecycleManager;
import be.esmay.atlas.base.metrics.NetworkBandwidthMonitor;
import be.esmay.atlas.base.metrics.ResourceMetricsManager;
import be.esmay.atlas.base.network.NettyServer;
import be.esmay.atlas.base.provider.ProviderManager;
import be.esmay.atlas.base.scaler.ScalerManager;
import be.esmay.atlas.base.server.ServerManager;
import be.esmay.atlas.base.utils.Logger;
import lombok.Data;
import lombok.Getter;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Data
public final class AtlasBase {

    @Getter
    private static AtlasBase instance;

    private final ExecutorService executorService;
    private final ConfigManager configManager;
    private final ProviderManager providerManager;
    private final ScalerManager scalerManager;
    private final CommandManager commandManager;
    private final ServerManager serverManager;
    private final ApiManager apiManager;
    private ResourceMetricsManager resourceMetricsManager;
    private NetworkBandwidthMonitor networkBandwidthMonitor;

    private NettyServer nettyServer;

    private volatile boolean running = false;
    private volatile boolean debugMode = false;

    private final Object shutdownLock = new Object();
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    public AtlasBase() {
        instance = this;

        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
        this.configManager = new ConfigManager();
        this.providerManager = new ProviderManager();
        this.scalerManager = new ScalerManager(this);
        this.commandManager = new CommandManager();
        this.serverManager = new ServerManager(this);
        this.apiManager = new ApiManager(this);

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "Atlas-Shutdown"));
    }

    public void start() {
        synchronized (this.shutdownLock) {
            if (this.running) {
                Logger.warn("Atlas is already running.");
                return;
            }

            try {
                Logger.printBanner();
                Logger.info("Starting Atlas...");

                this.createRequiredDirectories();
                this.configManager.initialize();
                this.nettyServer = new NettyServer(this.configManager.getAtlasConfig().getAtlas().getNetwork());
                this.providerManager.initialize(this.configManager.getAtlasConfig());
                this.scalerManager.initialize();
                this.commandManager.initialize();

                if (this.providerManager.getProvider() instanceof be.esmay.atlas.base.provider.impl.DockerServiceProvider dockerProvider) {
                    this.resourceMetricsManager = new ResourceMetricsManager(dockerProvider.getDockerClient());
                    this.resourceMetricsManager.start();
                }

                this.networkBandwidthMonitor = new NetworkBandwidthMonitor();
                this.networkBandwidthMonitor.start();
                
                this.apiManager.start();
                this.nettyServer.start();

                Thread.sleep(500);

                this.running = true;
                Logger.info("Atlas is now running and ready to use.");
            } catch (Exception e) {
                Logger.error("Failed to start Atlas", e);
                this.shutdown();

                throw new RuntimeException("Atlas startup failed", e);
            }
        }
    }

    public void shutdown() {
        synchronized (this.shutdownLock) {
            if (!this.running) return;

            Logger.info("Atlas is shutting down...");
            this.running = false;

            try {
                this.shutdownExecutorService();

                if (this.nettyServer != null)
                    this.nettyServer.shutdown();

                if (this.apiManager != null)
                    this.apiManager.stop();

                if (this.scalerManager != null)
                    this.scalerManager.shutdown();

                if (this.commandManager != null)
                    this.commandManager.shutdown();

                if (this.networkBandwidthMonitor != null)
                    this.networkBandwidthMonitor.stop();
                    
                if (this.resourceMetricsManager != null)
                    this.resourceMetricsManager.stop();
                    
                if (this.providerManager != null)
                    this.providerManager.shutdown();

                Logger.info("Atlas has been stopped successfully.");
                Logger.closeLogFile();
            } catch (Exception e) {
                Logger.error("Error during Atlas shutdown", e);
            } finally {
                this.shutdownLatch.countDown();
            }
        }
    }

    private void shutdownExecutorService() {
        if (this.executorService == null || this.executorService.isShutdown()) return;

        this.executorService.shutdown();

        try {
            if (!this.executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                this.executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            this.executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void createRequiredDirectories() {
        File serversDir = new File("servers");
        File templatesGlobalServerDir = new File("templates/global/server");
        File templatesGlobalProxyDir = new File("templates/global/proxy");

        this.createDirectory(serversDir, "servers");
        this.createDirectory(templatesGlobalServerDir, "templates/global/server");
        this.createDirectory(templatesGlobalProxyDir, "templates/global/proxy");
    }

    private void createDirectory(File directory, String name) {
        if (directory.exists()) return;

        if (!directory.mkdirs())
            Logger.warn("Failed to create {} directory: {}", name, directory.getAbsolutePath());
    }

    public CompletableFuture<Void> runAsync(Runnable task) {
        return CompletableFuture.runAsync(task, this.executorService);
    }

    public <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, this.executorService);
    }

    public void setDebugMode(boolean debugMode) {
        this.debugMode = debugMode;

        Logger.setDebugMode(debugMode);
        Logger.info("Debug mode {}", debugMode ? "enabled" : "disabled");
    }

    public static void main(String[] args) {
        try {
            AtlasBase atlasBase = new AtlasBase();

            for (String arg : args) {
                if (arg.equals("--debug")) {
                    atlasBase.setDebugMode(true);
                    break;
                }
            }

            atlasBase.start();
            atlasBase.shutdownLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Logger.error("Atlas was interrupted", e);
        } catch (Exception e) {
            Logger.error("Failed to initialize Atlas", e);
            System.exit(1);
        }
    }

}


