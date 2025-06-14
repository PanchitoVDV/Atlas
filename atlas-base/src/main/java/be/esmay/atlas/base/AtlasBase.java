package be.esmay.atlas.base;

import be.esmay.atlas.base.utils.Logger;
import lombok.Getter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
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
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    public AtlasBase() {
        instance = this;

        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
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

                Logger.info("Atlas has been stopped successfully.");
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

    public CompletableFuture<Void> runAsync(Runnable task) {
        return CompletableFuture.runAsync(task, this.executorService);
    }

    public <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, this.executorService);
    }

    public static void main(String[] args) {
        try {
            AtlasBase atlasBase = new AtlasBase();
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


