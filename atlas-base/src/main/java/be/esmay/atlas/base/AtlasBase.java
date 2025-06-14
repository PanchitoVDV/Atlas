package be.esmay.atlas.base;

import be.esmay.atlas.base.utils.Logger;
import lombok.Getter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Getter
public final class AtlasBase {

    @Getter
    private static AtlasBase instance;

    private final ExecutorService executorService;

    private volatile boolean running = false;
    private final Object shutdownLock = new Object();

    public AtlasBase() {
        instance = this;

        this.executorService = Executors.newVirtualThreadPerTaskExecutor();

        Logger.printBanner();
        Logger.info("Initializing Atlas...");

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "Atlas-Shutdown"));

        Logger.info("Atlas initialization completed successfully.");
    }

    public void start() {
        synchronized (this.shutdownLock) {
            if (this.running) {
                Logger.warn("Atlas is already running.");
                return;
            }

            try {
                Logger.info("Starting Atlas components...");

                this.running = true;
                Logger.info("Atlas is now running and ready to use.");
            } catch (Exception e) {
                Logger.error("Failed to start Atlas", e);
                shutdown();
                throw new RuntimeException("Atlas startup failed", e);
            }
        }
    }

    public void shutdown() {
        synchronized (this.shutdownLock) {
            if (!this.running)
                return;

            Logger.info("Atlas is shutting down...");
            this.running = false;

            try {
                if (this.executorService != null && !this.executorService.isShutdown()) {
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

                Logger.info("Atlas has been stopped successfully.");
            } catch (Exception e) {
                Logger.error("Error during Atlas shutdown", e);
            }
        }
    }

    public CompletableFuture<Void> runAsync(Runnable task) {
        return CompletableFuture.runAsync(task, this.executorService);
    }

    public <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, this.executorService);
    }

    public static void main(String[] args) {
        try {
            System.setProperty("java.util.logging.manager", "org.slf4j.bridge.SLF4JBridgeHandler");
            System.setProperty("file.encoding", "UTF-8");

            AtlasBase atlasBase = new AtlasBase();
            atlasBase.start();

            synchronized (atlasBase.shutdownLock) {
                while (atlasBase.running) {
                    try {
                        atlasBase.shutdownLock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } catch (Exception e) {
            Logger.error("Failed to initialize Atlas", e);
            System.exit(1);
        }
    }

}
