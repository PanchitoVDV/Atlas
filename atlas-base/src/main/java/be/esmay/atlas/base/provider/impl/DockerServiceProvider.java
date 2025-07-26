package be.esmay.atlas.base.provider.impl;

import be.esmay.atlas.base.AtlasBase;
import be.esmay.atlas.base.config.impl.AtlasConfig;
import be.esmay.atlas.base.config.impl.ScalerConfig;
import be.esmay.atlas.base.directory.DirectoryManager;
import be.esmay.atlas.base.lifecycle.ServerLifecycleManager;
import be.esmay.atlas.base.lifecycle.ServerLifecycleService;
import be.esmay.atlas.base.provider.DeletionOptions;
import be.esmay.atlas.base.provider.DeletionReason;
import be.esmay.atlas.base.provider.ServiceProvider;
import be.esmay.atlas.base.provider.StartOptions;
import be.esmay.atlas.base.provider.StartReason;
import be.esmay.atlas.base.scaler.Scaler;
import be.esmay.atlas.base.template.TemplateManager;
import be.esmay.atlas.base.utils.Logger;
import be.esmay.atlas.common.enums.ServerStatus;
import be.esmay.atlas.common.enums.ServerType;
import be.esmay.atlas.common.models.AtlasServer;
import be.esmay.atlas.common.models.ServerInfo;
import be.esmay.atlas.common.models.ServerResourceMetrics;
import be.esmay.atlas.common.models.ServerStats;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.LogContainerCmd;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.CpuStatsConfig;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.Info;
import com.github.dockerjava.api.model.Network;
import com.github.dockerjava.api.model.NetworkSettings;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.api.model.StatisticNetworksConfig;
import com.github.dockerjava.api.model.Statistics;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.InvocationBuilder;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class DockerServiceProvider extends ServiceProvider {

    private final AtlasConfig.Docker dockerConfig;
    private final DockerClient dockerClient;
    private final Map<String, String> serverContainerIds;
    private final Map<String, Closeable> logStreamConnections;
    private final Map<String, Map<String, Consumer<String>>> logSubscribers;
    private final Map<String, NetworkStatsCache> networkStatsCache;
    private final ExecutorService executorService;
    private final Set<String> manuallyStoppedStaticServers;

    private final Set<Integer> usedProxyPorts;
    private final Map<String, Integer> serverNameToPort;
    private final Map<String, Integer> serverIdToPort;
    private static final int PROXY_PORT_START = 25565;

    private static final Map<String, CompletableFuture<Void>> IMAGE_PULL_FUTURES = new ConcurrentHashMap<>();
    private static final Set<String> PULLED_IMAGES = ConcurrentHashMap.newKeySet();

    private final String cachedHostIp;

    public DockerServiceProvider(AtlasConfig.ServiceProvider serviceProviderConfig) {
        super("docker");
        this.dockerConfig = serviceProviderConfig.getDocker();
        this.serverContainerIds = new ConcurrentHashMap<>();
        this.logStreamConnections = new ConcurrentHashMap<>();
        this.logSubscribers = new ConcurrentHashMap<>();
        this.networkStatsCache = new ConcurrentHashMap<>();
        this.usedProxyPorts = ConcurrentHashMap.newKeySet();
        this.serverNameToPort = new ConcurrentHashMap<>();
        this.serverIdToPort = new ConcurrentHashMap<>();
        this.executorService = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "Docker-Provider");
            thread.setDaemon(true);
            return thread;
        });
        this.manuallyStoppedStaticServers = ConcurrentHashMap.newKeySet();

        DefaultDockerClientConfig.Builder configBuilder = DefaultDockerClientConfig.createDefaultConfigBuilder();

        if (this.dockerConfig.getSocketPath() != null && !this.dockerConfig.getSocketPath().isEmpty()) {
            configBuilder.withDockerHost("unix://" + this.dockerConfig.getSocketPath());
            Logger.info("Docker provider using custom socket: {}", this.dockerConfig.getSocketPath());
        }

        System.setProperty("org.slf4j.simpleLogger.log.com.github.dockerjava", "off");
        System.setProperty("org.slf4j.simpleLogger.log.org.apache.http", "off");
        System.setProperty("org.slf4j.simpleLogger.log.org.apache.http.wire", "off");
        System.setProperty("org.slf4j.simpleLogger.log.org.apache.http.headers", "off");
        System.setProperty("org.slf4j.simpleLogger.log.org.apache.http.impl", "off");
        System.setProperty("org.slf4j.simpleLogger.log.org.apache.http.client", "off");
        System.setProperty("org.slf4j.simpleLogger.log.com.github.dockerjava.core", "off");
        System.setProperty("org.slf4j.simpleLogger.log.com.github.dockerjava.jaxrs", "off");

        java.util.logging.Logger.getLogger("com.github.dockerjava").setLevel(java.util.logging.Level.OFF);
        java.util.logging.Logger.getLogger("org.apache.http").setLevel(java.util.logging.Level.OFF);
        java.util.logging.Logger.getGlobal().setLevel(java.util.logging.Level.OFF);

        this.dockerClient = DockerClientBuilder.getInstance(configBuilder.build()).build();

        try {
            Info info = this.dockerClient.infoCmd().exec();
            Logger.info("Docker provider connected successfully - Docker version: {}", info.getServerVersion());
        } catch (Exception e) {
            Logger.error("Failed to connect to Docker: {}", e.getMessage());
            throw new RuntimeException("Docker connection failed", e);
        }

        this.cachedHostIp = this.determineHostIpForContainers();
        Logger.debug("Cached host IP for containers: {}", this.cachedHostIp);

        if (this.dockerConfig.isAutoCreateNetwork() && this.dockerConfig.getNetwork() != null) {
            this.createNetworkIfNotExists();
        }

        this.cleanupOldAtlasContainers();
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            try {
                String networkCidr = this.getDockerNetworkCidr();
                if (networkCidr == null) return;

                AtlasBase atlasBase = AtlasBase.getInstance();
                if (atlasBase != null && atlasBase.getNettyServer() != null) {
                    atlasBase.getNettyServer().getConnectionValidator().addAllowedNetwork(networkCidr);
                    Logger.debug("Added Docker network {} to allowed networks", networkCidr);
                }
            } catch (Exception e) {
                Logger.error("Failed to initialize Docker provider network settings", e);
            }
        });
    }

    private String getDockerNetworkCidr() {
        try {
            List<Network> networks = this.dockerClient.listNetworksCmd()
                    .withNameFilter(this.dockerConfig.getNetwork())
                    .exec();

            if (!networks.isEmpty()) {
                Network network = networks.getFirst();
                if (network.getIpam() != null && network.getIpam().getConfig() != null && !network.getIpam().getConfig().isEmpty()) {
                    return network.getIpam().getConfig().getFirst().getSubnet();
                }
            }
        } catch (Exception e) {
            Logger.error("Failed to get Docker network CIDR", e);
        }
        return null;
    }

    @Override
    public CompletableFuture<AtlasServer> createServer(ScalerConfig.Group groupConfig, AtlasServer atlasServer) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String containerId = this.getOrCreateDockerContainer(groupConfig, atlasServer);
                if (containerId == null) {
                    throw new RuntimeException("Failed to create or find Docker container");
                }

                this.serverContainerIds.put(atlasServer.getServerId(), containerId);
                this.dockerClient.startContainerCmd(containerId).exec();

                InspectContainerResponse containerInfo = this.dockerClient.inspectContainerCmd(containerId).exec();
                String ipAddress = this.getContainerIpAddress(containerInfo);

                int serverPort = 25565;
                if (this.isProxyServer(atlasServer.getGroup())) {
                    ipAddress = this.cachedHostIp;
                    Integer hostPort = this.serverIdToPort.get(atlasServer.getServerId());
                    if (hostPort != null) {
                        serverPort = hostPort;
                    }

                    Logger.debug("Using public IP and port for proxy server {}: {}:{}", atlasServer.getName(), ipAddress, serverPort);
                }

                ServerInfo serverInfo = ServerInfo.builder()
                        .status(ServerStatus.STARTING)
                        .onlinePlayers(0)
                        .maxPlayers(atlasServer.getServerInfo() != null ? atlasServer.getServerInfo().getMaxPlayers() : 20)
                        .onlinePlayerNames(new HashSet<>())
                        .build();

                AtlasServer updatedServer = AtlasServer.builder()
                        .serverId(atlasServer.getServerId())
                        .name(atlasServer.getName())
                        .group(atlasServer.getGroup())
                        .workingDirectory(atlasServer.getWorkingDirectory())
                        .address(ipAddress)
                        .port(serverPort)
                        .type(atlasServer.getType())
                        .createdAt(atlasServer.getCreatedAt())
                        .serviceProviderId(containerId)
                        .isManuallyScaled(atlasServer.isManuallyScaled())
                        .lastHeartbeat(System.currentTimeMillis())
                        .serverInfo(serverInfo)
                        .build();

                Logger.debug("Container created and started: {}", atlasServer.getName());

                return updatedServer;
            } catch (Exception e) {
                Logger.error("Failed to create Docker server: {}", e.getMessage());
                throw new RuntimeException(e);
            }
        }, this.executorService);
    }


    @Override
    public CompletableFuture<Void> stopServer(AtlasServer atlasServer) {
        if (atlasServer.getType() == ServerType.DYNAMIC) {
            DeletionOptions stopOptions = DeletionOptions.builder()
                    .reason(DeletionReason.USER_COMMAND)
                    .gracefulStop(true)
                    .cleanupDirectory(false)
                    .removeFromTracking(false)
                    .build();

            return this.deleteServerCompletely(atlasServer, stopOptions)
                    .thenAccept(success -> {
                        if (!success) {
                            throw new RuntimeException("Failed to stop server through unified deletion: " + atlasServer.getName());
                        }
                    });
        } else {
            return CompletableFuture.runAsync(() -> {
                try {
                    String containerId = this.serverContainerIds.get(atlasServer.getServerId());
                    if (containerId == null) {
                        Logger.warn("No container ID found for server: {}, server may already be stopped", atlasServer.getName());
                        return;
                    }

                    Logger.debug("Stopping container for static server: {} (container: {})", atlasServer.getName(), containerId.substring(0, 12));
                    
                    this.dockerClient.stopContainerCmd(containerId).withTimeout(30).exec();
                    Logger.info("Successfully stopped static server container: {}", atlasServer.getName());

                    this.waitForContainerStop(atlasServer, containerId);
                    
                } catch (Exception e) {
                    Logger.error("Failed to stop static server: {}", atlasServer.getName(), e);
                    throw new RuntimeException("Failed to stop static server: " + atlasServer.getName(), e);
                }
            }, this.executorService);
        }
    }

    @Override
    public CompletableFuture<Boolean> deleteServer(String serverId) {
        AtlasServer server = AtlasBase.getInstance().getScalerManager().getServerFromTracking(serverId);
        if (server == null) {
            Logger.warn("Server not found for deletion: {}, cleaning up any remaining traces", serverId);
            this.cleanupServerTracking(serverId);
            return CompletableFuture.completedFuture(true);
        }

        return this.deleteServerCompletely(server, DeletionOptions.userCommand());
    }

    @Override
    public CompletableFuture<Boolean> deleteServerCompletely(AtlasServer server, DeletionOptions options) {
        if (server == null) {
            Logger.warn("Cannot delete null server");
            return CompletableFuture.completedFuture(false);
        }

        Logger.info("Starting deletion for server: {} (reason: {}, graceful: {})",
                server.getName(), options.getReason(), options.isGracefulStop());

        return CompletableFuture.supplyAsync(() -> {
            try {
                return this.performUnifiedDeletion(server, options);
            } catch (Exception e) {
                Logger.error("Unified deletion failed for server: {}", server.getName(), e);

                if (options.isRemoveFromTracking()) {
                    this.cleanupServerTracking(server.getServerId());
                }

                return options.getReason() == DeletionReason.ERROR_RECOVERY;
            }
        }, this.executorService);
    }

    private boolean performUnifiedDeletion(AtlasServer server, DeletionOptions options) throws Exception {
        String serverId = server.getServerId();
        String containerName = server.getName();

        Logger.debug("Starting deletion process for server: {} ({})", containerName, serverId);

        boolean wasAlreadyShutdown = server.isShutdown();
        if (wasAlreadyShutdown && options.getReason() != DeletionReason.ERROR_RECOVERY) {
            Logger.warn("Server {} already marked as shutdown, but proceeding with deletion", containerName);
        }
        server.setShutdown(true);

        this.releaseServerPort(server);
        this.closeServerStreams(serverId);

        DeletionContext context = this.prepareDeletionContext(server, options);

        if (options.isGracefulStop() && context.containerExists) {
            this.stopContainerGracefully(context, options.getTimeoutSeconds());
        }

        this.removeContainer(context, options.getTimeoutSeconds());

        if (options.isCleanupDirectory() && context.shouldCleanupDirectory) {
            this.cleanupServerVolume(context.volumePath, serverId);
        }

        if (options.isRemoveFromTracking()) {
            this.cleanupServerTracking(serverId);
        } else {
            Logger.debug("Skipping tracking cleanup for server: {} (removeFromTracking=false)", containerName);
        }

        Logger.info("Successfully completed deletion for server: {} ({})", containerName, serverId);
        return true;
    }

    private static class DeletionContext {
        final String serverId;
        final String containerId;
        final String containerName;
        final boolean containerExists;
        final boolean shouldCleanupDirectory;
        final String volumePath;

        DeletionContext(String serverId, String containerId, String containerName,
                        boolean containerExists, boolean shouldCleanupDirectory, String volumePath) {
            this.serverId = serverId;
            this.containerId = containerId;
            this.containerName = containerName;
            this.containerExists = containerExists;
            this.shouldCleanupDirectory = shouldCleanupDirectory;
            this.volumePath = volumePath;
        }
    }

    private void releaseServerPort(AtlasServer server) {
        if (this.isProxyServer(server.getGroup())) {
            this.releaseProxyPortForServer(server.getServerId());
            Logger.debug("Released proxy port for server {} during unified deletion", server.getName());
        }
    }

    private void closeServerStreams(String serverId) {
        Closeable logConnection = this.logStreamConnections.remove(serverId);
        if (logConnection != null) {
            try {
                logConnection.close();
                Logger.debug("Closed log stream for server: {}", serverId);
            } catch (IOException e) {
                Logger.warn("Error closing log stream for server {}: {}", serverId, e.getMessage());
            }
        }

        AtlasServer server = AtlasBase.getInstance().getScalerManager().getServerFromTracking(serverId);
        if (server != null && server.getType() == ServerType.STATIC) {
            Logger.debug("Preserving log subscribers for static server: {}", serverId);
        } else {
            this.logSubscribers.remove(serverId);
        }
    }

    private DeletionContext prepareDeletionContext(AtlasServer server, DeletionOptions options) {
        String serverId = server.getServerId();
        String containerName = server.getName();
        String containerId = this.serverContainerIds.get(serverId);

        if (containerId == null) {
            Logger.warn("Container ID not found for server: {} - assuming already deleted", containerName);
            return new DeletionContext(serverId, null, containerName, false, false, null);
        }

        boolean containerExists = true;
        boolean shouldCleanupDirectory = false;
        String volumePath = null;

        try {
            InspectContainerResponse containerInfo = this.dockerClient.inspectContainerCmd(containerId).exec();
            Map<String, String> labels = containerInfo.getConfig().getLabels();

            String dynamicLabel = labels != null ? labels.get("atlas.dynamic") : null;
            boolean isDynamic = "true".equals(dynamicLabel);

            if (isDynamic && options.isCleanupDirectory() && server.getWorkingDirectory() != null) {
                shouldCleanupDirectory = true;
                volumePath = server.getWorkingDirectory();
                if (!volumePath.startsWith("/")) {
                    volumePath = System.getProperty("user.dir") + "/" + volumePath;
                }
            }

            Logger.debug("Container {} exists, dynamic: {}, cleanup dir: {}",
                    containerId.substring(0, 12), isDynamic, shouldCleanupDirectory);

        } catch (Exception e) {
            Logger.debug("Could not inspect container {} (may not exist): {}",
                    containerId.substring(0, 12), e.getMessage());

            if (server.getType() == ServerType.DYNAMIC && options.isCleanupDirectory() && server.getWorkingDirectory() != null) {
                shouldCleanupDirectory = true;
                volumePath = server.getWorkingDirectory();
                if (!volumePath.startsWith("/")) {
                    volumePath = System.getProperty("user.dir") + "/" + volumePath;
                }
                Logger.debug("Enabling directory cleanup for dynamic server {} despite container inspection failure", server.getName());
            }
        }

        return new DeletionContext(serverId, containerId, containerName,
                containerExists, shouldCleanupDirectory, volumePath);
    }

    private void stopContainerGracefully(DeletionContext context, int timeoutSeconds) throws Exception {
        if (context.containerId == null) {
            Logger.debug("No container ID to stop for server: {}", context.containerName);
            return;
        }

        try {
            Logger.debug("Gracefully stopping container: {}", context.containerId.substring(0, 12));
            this.dockerClient.stopContainerCmd(context.containerId)
                    .withTimeout(timeoutSeconds)
                    .exec();

            this.waitForContainerStop(context.containerId);
            Logger.debug("Container {} stopped gracefully", context.containerId.substring(0, 12));

        } catch (Exception e) {
            Logger.warn("Graceful stop failed for container {}: {}",
                    context.containerId.substring(0, 12), e.getMessage());
        }
    }

    private void removeContainer(DeletionContext context, int timeoutSeconds) throws Exception {
        if (context.containerId == null) {
            Logger.debug("No container ID to remove for server: {}", context.containerName);
            return;
        }

        boolean volumesRemoved = false;

        try {
            Logger.debug("Removing container: {}", context.containerId.substring(0, 12));
            this.dockerClient.removeContainerCmd(context.containerId)
                    .withForce(true)
                    .exec();
            this.waitForContainerDeletion(context.containerId);

        } catch (Exception firstAttempt) {
            Logger.warn("First removal attempt failed for {}: {}, trying with volumes",
                    context.containerId.substring(0, 12), firstAttempt.getMessage());

            try {
                this.dockerClient.removeContainerCmd(context.containerId)
                        .withForce(true)
                        .withRemoveVolumes(true)
                        .exec();
                this.waitForContainerDeletion(context.containerId);
                volumesRemoved = true;

            } catch (Exception secondAttempt) {
                Logger.error("Both removal attempts failed for container {}: {}",
                        context.containerId.substring(0, 12), secondAttempt.getMessage());
                throw new Exception("Failed to remove container after multiple attempts", secondAttempt);
            }
        }

        Logger.debug("Successfully removed container: {} (volumes removed: {})",
                context.containerId.substring(0, 12), volumesRemoved);
    }

    private void cleanupServerTracking(String serverId) {
        this.serverContainerIds.remove(serverId);
        this.logSubscribers.remove(serverId);
        this.serverIdToPort.remove(serverId);

        Logger.debug("Cleaned up all tracking for server: {}", serverId);
    }


    private void waitForContainerDeletion(String containerId) {
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                this.dockerClient.inspectContainerCmd(containerId).exec();
                Thread.sleep(500);
            } catch (Exception e) {
                Logger.debug("Container {} confirmed deleted after {} attempts", containerId.substring(0, 12), attempt + 1);
                return;
            }
        }

        Logger.error("Container {} failed to delete after 10 seconds timeout", containerId.substring(0, 12));
        throw new RuntimeException("Container failed to delete within timeout: " + containerId);
    }

    private void waitForContainerStop(String containerId) {
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                InspectContainerResponse containerInfo = this.dockerClient.inspectContainerCmd(containerId).exec();
                if (Boolean.FALSE.equals(containerInfo.getState().getRunning())) {
                    Logger.debug("Container {} confirmed stopped after {} attempts", containerId.substring(0, 12), attempt + 1);
                    return;
                }

                Thread.sleep(500);
            } catch (Exception e) {
                Logger.debug("Container {} no longer exists (stopped and removed)", containerId.substring(0, 12));
                return;
            }
        }

        Logger.error("Container {} failed to stop after 10 seconds timeout", containerId.substring(0, 12));
        throw new RuntimeException("Container failed to stop within timeout: " + containerId);
    }

    private void cleanupServerVolume(String volumePath, String serverId) {
        try {
            AtlasBase atlasInstance = AtlasBase.getInstance();
            if (atlasInstance != null && atlasInstance.getConfigManager() != null) {
                boolean cleanupDynamicOnShutdown = atlasInstance.getConfigManager().getAtlasConfig().getAtlas().getTemplates().isCleanupDynamicOnShutdown();
                if (!cleanupDynamicOnShutdown) {
                    Logger.debug("Dynamic volume cleanup is disabled in config for server: {}", serverId);
                    return;
                }
            }

            DirectoryManager directoryManager = new DirectoryManager();
            Path volumeDir = Paths.get(volumePath);
            if (Files.exists(volumeDir)) {
                directoryManager.deleteDirectoryRecursively(volumeDir);
                Logger.debug("Cleaned up volume directory for dynamic server: {}", serverId);
            }
        } catch (Exception e) {
            Logger.warn("Failed to cleanup volume directory for server {}: {}", serverId, e.getMessage());
        }
    }


    @Override
    public CompletableFuture<Optional<AtlasServer>> getServer(String serverId) {
        return CompletableFuture.supplyAsync(() -> {
            AtlasServer server = AtlasBase.getInstance().getScalerManager().getServerFromTracking(serverId);
            return Optional.ofNullable(server);
        });
    }

    @Override
    public CompletableFuture<List<AtlasServer>> getAllServers() {
        return CompletableFuture.supplyAsync(() -> 
            AtlasBase.getInstance().getScalerManager().getAllServersFromTracking()
        );
    }

    @Override
    public CompletableFuture<List<AtlasServer>> getServersByGroup(String group) {
        return CompletableFuture.supplyAsync(() -> 
            AtlasBase.getInstance().getScalerManager().getServersByGroupFromTracking(group)
        );
    }

    @Override
    public CompletableFuture<Boolean> isServerRunning(String serverId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String containerId = this.serverContainerIds.get(serverId);
                if (containerId == null) {
                    return false;
                }

                InspectContainerResponse containerInfo = this.dockerClient.inspectContainerCmd(containerId).exec();
                return Boolean.TRUE.equals(containerInfo.getState().getRunning());
            } catch (Exception e) {
                Logger.error("Error checking Docker container status: {}", e.getMessage());
                return false;
            }
        }, this.executorService);
    }

    @Override
    public CompletableFuture<Boolean> updateServerStatus(String serverId, AtlasServer updatedServer) {
        return CompletableFuture.supplyAsync(() -> {
            AtlasServer server = AtlasBase.getInstance().getScalerManager().getServerFromTracking(serverId);
            if (server == null) {
                Logger.warn("Server not found for update: {}", serverId);
                return false;
            }

            Logger.debug("Updated server status for: {}", updatedServer.getName());
            return true;
        });
    }

    @Override
    public CompletableFuture<Optional<ServerResourceMetrics>> getServerResourceMetrics(String serverId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String containerId = this.serverContainerIds.get(serverId);
                if (containerId == null) {
                    return Optional.empty();
                }

                InvocationBuilder.AsyncResultCallback<Statistics> callback = new InvocationBuilder.AsyncResultCallback<>();
                this.dockerClient.statsCmd(containerId).withNoStream(true).exec(callback);
                Statistics stats = callback.awaitResult();
                callback.close();

                if (stats == null) {
                    return Optional.empty();
                }

                long memoryUsed = stats.getMemoryStats().getUsage();
                long memoryLimit = stats.getMemoryStats().getLimit();
                
                double cpuDelta = stats.getCpuStats().getCpuUsage().getTotalUsage() - 
                                 stats.getPreCpuStats().getCpuUsage().getTotalUsage();
                double systemDelta = stats.getCpuStats().getSystemCpuUsage() - 
                                    stats.getPreCpuStats().getSystemCpuUsage();
                double cpuUsage = 0.0;
                if (systemDelta > 0.0 && cpuDelta > 0.0) {
                    cpuUsage = (cpuDelta / systemDelta) * stats.getCpuStats().getOnlineCpus() * 100.0;
                }

                AtlasServer server = AtlasBase.getInstance().getScalerManager().getServerFromTracking(serverId);
                long diskUsed = 0;
                long diskTotal = 0;
                if (server != null && server.getWorkingDirectory() != null) {
                    File workingDir = new File(server.getWorkingDirectory());
                    diskUsed = this.getDirectorySize(workingDir);
                    diskTotal = workingDir.getTotalSpace();
                }

                long networkReceiveBytes = 0;
                long networkSendBytes = 0;
                double networkReceiveBandwidth = 0.0;
                double networkSendBandwidth = 0.0;
                
                if (stats.getNetworks() != null) {
                    for (Map.Entry<String, StatisticNetworksConfig> entry : stats.getNetworks().entrySet()) {
                        StatisticNetworksConfig networkStats = entry.getValue();
                        networkReceiveBytes += networkStats.getRxBytes();
                        networkSendBytes += networkStats.getTxBytes();
                    }

                    String previousStatsKey = "docker_network_" + serverId;
                    NetworkStatsCache previousStats = this.networkStatsCache.get(previousStatsKey);
                    long currentTime = System.currentTimeMillis();
                    
                    if (previousStats != null) {
                        long timeDelta = currentTime - previousStats.timestamp;
                        if (timeDelta > 0) {
                            double timeDeltaSec = timeDelta / 1000.0;
                            networkReceiveBandwidth = (networkReceiveBytes - previousStats.rxBytes) / timeDeltaSec;
                            networkSendBandwidth = (networkSendBytes - previousStats.txBytes) / timeDeltaSec;
                        }
                    }

                    this.networkStatsCache.put(previousStatsKey, new NetworkStatsCache(networkReceiveBytes, networkSendBytes, currentTime));
                }

                ServerResourceMetrics metrics = ServerResourceMetrics.builder()
                    .cpuUsage(cpuUsage)
                    .memoryUsed(memoryUsed)
                    .memoryTotal(memoryLimit)
                    .diskUsed(diskUsed)
                    .diskTotal(diskTotal)
                    .networkReceiveBytes(networkReceiveBytes)
                    .networkSendBytes(networkSendBytes)
                    .networkReceiveBandwidth(networkReceiveBandwidth)
                    .networkSendBandwidth(networkSendBandwidth)
                    .lastUpdated(System.currentTimeMillis())
                    .build();

                return Optional.of(metrics);
            } catch (Exception e) {
                Logger.debug("Failed to get resource metrics for server " + serverId + ": " + e.getMessage());
                return Optional.empty();
            }
        }, this.executorService);
    }

    private long getDirectorySize(File directory) {
        if (!directory.exists() || !directory.isDirectory()) {
            return 0;
        }
        
        try {
            return Files.walk(directory.toPath())
                .filter(Files::isRegularFile)
                .mapToLong(path -> {
                    try {
                        return Files.size(path);
                    } catch (IOException e) {
                        return 0;
                    }
                })
                .sum();
        } catch (IOException e) {
            Logger.debug("Failed to calculate directory size for " + directory.getPath() + ": " + e.getMessage());
            return 0;
        }
    }

    @Override
    public CompletableFuture<List<String>> getServerLogs(String serverId, int lines) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String containerId = this.serverContainerIds.get(serverId);
                if (containerId == null) {
                    return new ArrayList<>();
                }

                LogContainerCmd logCmd = this.dockerClient.logContainerCmd(containerId)
                        .withStdOut(true)
                        .withStdErr(true);

                if (lines > 0) {
                    logCmd = logCmd.withTail(lines);
                }

                List<String> logs = new ArrayList<>();
                try {
                    logCmd.exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            logs.add(new String(frame.getPayload()).trim());
                        }
                    }).awaitCompletion(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Logger.warn("Log retrieval interrupted for container: {}", containerId);
                }

                return logs;
            } catch (Exception e) {
                Logger.error("Error retrieving Docker logs: {}", e.getMessage());
                return new ArrayList<>();
            }
        }, this.executorService);
    }

    @Override
    public CompletableFuture<String> streamServerLogs(String serverId, Consumer<String> consumer) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String containerId = this.serverContainerIds.get(serverId);
                if (containerId == null) {
                    return null;
                }

                String subscriptionId = UUID.randomUUID().toString();
                this.logSubscribers.computeIfAbsent(serverId, k -> new ConcurrentHashMap<>()).put(subscriptionId, consumer);

                if (!this.logStreamConnections.containsKey(serverId)) {
                    this.startLogStreaming(serverId, containerId);
                }

                return subscriptionId;
            } catch (Exception e) {
                Logger.error("Error starting log stream: {}", e.getMessage());
                return null;
            }
        }, this.executorService);
    }

    @Override
    public CompletableFuture<Void> ensureResourcesReady(ScalerConfig.Group groupConfig) {
        return CompletableFuture.runAsync(() -> {
            ScalerConfig.Docker dockerGroupConfig = groupConfig.getServiceProvider().getDocker();
            if (dockerGroupConfig != null && dockerGroupConfig.getImage() != null) {
                this.ensureImageExists(dockerGroupConfig.getImage());
            }
        }, this.executorService);
    }

    @Override
    public CompletableFuture<Boolean> stopLogStream(String subscriptionId) {
        return CompletableFuture.supplyAsync(() -> {
            for (Map.Entry<String, Map<String, Consumer<String>>> entry : this.logSubscribers.entrySet()) {
                if (!entry.getValue().containsKey(subscriptionId)) {
                    continue;
                }

                String serverId = entry.getKey();
                entry.getValue().remove(subscriptionId);

                if (entry.getValue().isEmpty()) {
                    this.logSubscribers.remove(serverId);


                    Closeable logConnection = this.logStreamConnections.remove(serverId);
                    if (logConnection != null) {
                        try {
                            logConnection.close();
                        } catch (IOException e) {
                            Logger.warn("Error closing log stream: {}", e.getMessage());
                        }
                    }
                }

                return true;
            }

            return false;
        });
    }

    private String getOrCreateDockerContainer(ScalerConfig.Group groupConfig, AtlasServer atlasServer) {
        return this.createDockerContainer(groupConfig, atlasServer);
    }

    private String createDockerContainer(ScalerConfig.Group groupConfig, AtlasServer atlasServer) {
        try {
            ScalerConfig.Docker dockerGroupConfig = groupConfig.getServiceProvider().getDocker();

            List<String> envVars = new ArrayList<>();
            envVars.add("EULA=TRUE");
            envVars.add("SERVER_NAME=" + atlasServer.getName());
            envVars.add("SERVER_GROUP=" + atlasServer.getGroup());
            envVars.add("SERVER_UUID=" + atlasServer.getServerId());
            envVars.add("ATLAS_MANAGED=true");
            envVars.add("SERVER_TYPE=" + groupConfig.getServer().getType());
            envVars.add("UID=0");
            envVars.add("GID=0");

            AtlasBase atlasInstance = AtlasBase.getInstance();
            if (atlasInstance != null && atlasInstance.getConfigManager() != null) {
                AtlasConfig.Network networkConfig = atlasInstance.getConfigManager().getAtlasConfig().getAtlas().getNetwork();
                envVars.add("ATLAS_HOST=" + this.getHostIpForContainers());
                envVars.add("ATLAS_PORT=" + networkConfig.getPort());
                envVars.add("ATLAS_NETTY_KEY=" + atlasInstance.getNettyServer().getNettyKey());
            }

            if (dockerGroupConfig.getEnvironment() != null) {
                dockerGroupConfig.getEnvironment().forEach((key, value) ->
                        envVars.add(key + "=" + value));
            }

            Map<String, String> labels = new HashMap<>();
            labels.put("atlas.managed", "true");
            labels.put("atlas.server-id", atlasServer.getServerId());
            labels.put("atlas.group", atlasServer.getGroup());
            labels.put("atlas.server-name", atlasServer.getName());
            labels.put("atlas.version", "1.0");
            labels.put("atlas.server-type", groupConfig.getServer().getType());
            labels.put("atlas.dynamic", String.valueOf(atlasServer.getType().name().equals("DYNAMIC")));

            CreateContainerCmd createCmd = this.dockerClient.createContainerCmd(dockerGroupConfig.getImage())
                    .withName("atlas-" + atlasServer.getName())
                    .withLabels(labels)
                    .withEnv(envVars)
                    .withBinds(this.createBinds(dockerGroupConfig, atlasServer))
                    .withNetworkMode(this.dockerConfig.getNetwork());

            if (this.isProxyServer(atlasServer.getGroup())) {
                int hostPort = this.getNextAvailableProxyPort(atlasServer.getName());
                createCmd = createCmd.withPortBindings(PortBinding.parse(hostPort + ":25565"));

                this.serverIdToPort.put(atlasServer.getServerId(), hostPort);

                Logger.debug("Exposing proxy {} on host port {}", atlasServer.getName(), hostPort);
            }

            if (dockerGroupConfig.getMemory() != null && !dockerGroupConfig.getMemory().isEmpty()) {
                createCmd = createCmd.withMemory(this.parseMemory(dockerGroupConfig.getMemory()));
            }

            if (dockerGroupConfig.getCpu() != null && !dockerGroupConfig.getCpu().isEmpty()) {
                createCmd = createCmd.withCpuShares(this.parseCpu(dockerGroupConfig.getCpu()));
            }

            if (dockerGroupConfig.getCommand() != null && !dockerGroupConfig.getCommand().isEmpty()) {
                createCmd = createCmd.withCmd(dockerGroupConfig.getCommand().split(" "));
            }

            if (dockerGroupConfig.getWorkingDirectory() != null && !dockerGroupConfig.getWorkingDirectory().isEmpty()) {
                createCmd = createCmd.withWorkingDir(dockerGroupConfig.getWorkingDirectory());
            }

            try {
                CreateContainerResponse container = createCmd.exec();
                return container.getId();
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("already in use")) {
                    Logger.debug("Container name conflict for {}, removing existing container", atlasServer.getName());
                    try {
                        String containerName = "atlas-" + atlasServer.getName();
                        List<Container> existing = this.dockerClient.listContainersCmd()
                            .withShowAll(true)
                            .withNameFilter(List.of(containerName))
                            .exec();
                        
                        for (Container existingContainer : existing) {
                            this.dockerClient.removeContainerCmd(existingContainer.getId()).withForce(true).exec();
                            Logger.debug("Removed existing container: {}", existingContainer.getId().substring(0, 12));
                        }

                        CreateContainerResponse container = createCmd.exec();
                        return container.getId();
                    } catch (Exception retryEx) {
                        Logger.error("Failed to create container after removing existing one: {}", retryEx.getMessage());
                        throw retryEx;
                    }
                }
                throw e;
            }
        } catch (Exception e) {
            Logger.error("Failed to create Docker container: {}", e.getMessage());
            return null;
        }
    }

    private Bind[] createBinds(ScalerConfig.Docker dockerConfig, AtlasServer atlasServer) {
        List<Bind> binds = new ArrayList<>();

        if (atlasServer.getWorkingDirectory() != null) {
            String mountPath = dockerConfig.getVolumeMountPath();
            if (mountPath == null || mountPath.isEmpty()) {
                mountPath = "/data";
            }

            String absoluteWorkingDir = atlasServer.getWorkingDirectory();
            if (!absoluteWorkingDir.startsWith("/")) {
                absoluteWorkingDir = System.getProperty("user.dir") + "/" + absoluteWorkingDir;
            }

            binds.add(new Bind(absoluteWorkingDir, new Volume(mountPath)));
            Logger.debug("Added volume bind: {} -> {}", absoluteWorkingDir, mountPath);
        }

        return binds.toArray(new Bind[0]);
    }

    private void ensureImageExists(String image) {
        String normalizedImage = image.contains(":") ? image : image + ":latest";

        if (PULLED_IMAGES.contains(normalizedImage)) {
            return;
        }

        CompletableFuture<Void> existingPull = IMAGE_PULL_FUTURES.get(normalizedImage);
        if (existingPull != null) {
            try {
                existingPull.get();
                return;
            } catch (Exception e) {
                throw new RuntimeException("Failed waiting for image pull: " + normalizedImage, e);
            }
        }

        try {
            this.dockerClient.inspectImageCmd(normalizedImage).exec();
            PULLED_IMAGES.add(normalizedImage);
            Logger.debug("Docker image {} found locally", normalizedImage);
            return;
        } catch (Exception ignored) {
        }

        CompletableFuture<Void> pullFuture = new CompletableFuture<>();
        CompletableFuture<Void> existing = IMAGE_PULL_FUTURES.putIfAbsent(normalizedImage, pullFuture);

        if (existing != null) {
            try {
                existing.get();
                return;
            } catch (Exception e) {
                throw new RuntimeException("Failed waiting for image pull: " + normalizedImage, e);
            }
        }

        try {
            Logger.info("Docker image {} not found locally. Starting pull - this will block all scaling until complete...", normalizedImage);

            this.dockerClient.pullImageCmd(normalizedImage)
                    .exec(new ResultCallback.Adapter<PullResponseItem>() {
                        @Override
                        public void onNext(PullResponseItem item) {
                            String status = item.getStatus();

                            if (status != null && status.contains("Digest")) {
                                Logger.info("Pulling {}: {}", normalizedImage, status);
                            }
                        }
                    })
                    .awaitCompletion();

            PULLED_IMAGES.add(normalizedImage);
            pullFuture.complete(null);
            Logger.info("Successfully pulled Docker image: {} - scaling can now resume", normalizedImage);

        } catch (Exception e) {
            pullFuture.completeExceptionally(e);
            Logger.error("Failed to pull Docker image {}: {}", normalizedImage, e.getMessage());
            throw new RuntimeException("Could not pull required Docker image: " + normalizedImage, e);
        } finally {
            IMAGE_PULL_FUTURES.remove(normalizedImage);
        }
    }

    private Long parseMemory(String memory) {
        try {
            memory = memory.trim().toLowerCase();
            long multiplier = 1L;

            if (memory.endsWith("k") || memory.endsWith("kb")) {
                multiplier = 1024L;
                memory = memory.replaceAll("[kb]", "");
            } else if (memory.endsWith("m") || memory.endsWith("mb")) {
                multiplier = 1024L * 1024L;
                memory = memory.replaceAll("[mb]", "");
            } else if (memory.endsWith("g") || memory.endsWith("gb")) {
                multiplier = 1024L * 1024L * 1024L;
                memory = memory.replaceAll("[gb]", "");
            }

            return Long.parseLong(memory.trim()) * multiplier;
        } catch (Exception e) {
            Logger.warn("Failed to parse memory value '{}', using default", memory);
            return null;
        }
    }

    private Integer parseCpu(String cpu) {
        try {
            if (cpu.endsWith("m")) {
                int millicores = Integer.parseInt(cpu.replace("m", ""));
                return (millicores * 1024) / 1000;
            } else {
                double cores = Double.parseDouble(cpu);
                return (int) (cores * 1024);
            }
        } catch (Exception e) {
            Logger.warn("Failed to parse CPU value '{}', using default", cpu);
            return null;
        }
    }

    private String getContainerIpAddress(InspectContainerResponse containerInfo) {
        try {
            NetworkSettings networkSettings = containerInfo.getNetworkSettings();
            if (networkSettings != null) {
                Map<String, ContainerNetwork> networks = networkSettings.getNetworks();
                if (networks != null && !networks.isEmpty()) {
                    ContainerNetwork network = networks.values().iterator().next();
                    if (network != null && network.getIpAddress() != null) {
                        return network.getIpAddress();
                    }
                }

                if (networkSettings.getIpAddress() != null && !networkSettings.getIpAddress().isEmpty()) {
                    return networkSettings.getIpAddress();
                }
            }
        } catch (Exception e) {
            Logger.warn("Failed to get container IP address: {}", e.getMessage());
        }

        return "localhost";
    }

    private String getHostIpForContainers() {
        return this.cachedHostIp;
    }

    private String determineHostIpForContainers() {
        try {
            Network network = this.dockerClient.inspectNetworkCmd().withNetworkId(this.dockerConfig.getNetwork()).exec();
            if (network != null && network.getIpam() != null && network.getIpam().getConfig() != null) {
                for (Network.Ipam.Config config : network.getIpam().getConfig()) {
                    if (config.getGateway() != null) {
                        String gatewayIp = config.getGateway();
                        Logger.debug("Using Docker network gateway IP for Atlas host: {}", gatewayIp);
                        return gatewayIp;
                    }
                }
            }
        } catch (Exception e) {
            Logger.warn("Failed to get Docker network gateway: {}", e.getMessage());
        }

        try {
            NetworkInterface networkInterface = NetworkInterface.getByName("eth0");
            if (networkInterface == null) {
                networkInterface = NetworkInterface.getByName("en0");
            }
            if (networkInterface == null) {
                Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                while (interfaces.hasMoreElements()) {
                    NetworkInterface iface = interfaces.nextElement();
                    if (!iface.isLoopback() && iface.isUp()) {
                        networkInterface = iface;
                        break;
                    }
                }
            }

            if (networkInterface != null) {
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!address.isLoopbackAddress() && address instanceof Inet4Address && !address.isSiteLocalAddress()) {
                        Logger.debug("Using public IP for Atlas host: {}", address.getHostAddress());
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Logger.warn("Failed to get host network interface: {}", e.getMessage());
        }

        Logger.info("Using fallback Docker bridge gateway IP for Atlas host: 172.17.0.1");
        return "172.17.0.1";
    }

    private void createNetworkIfNotExists() {
        try {
            List<Network> networks = this.dockerClient.listNetworksCmd()
                    .withNameFilter(this.dockerConfig.getNetwork())
                    .exec();

            if (networks.isEmpty()) {
                this.dockerClient.createNetworkCmd()
                        .withName(this.dockerConfig.getNetwork())
                        .withDriver("bridge")
                        .exec();

                Logger.info("Created Docker network: {}", this.dockerConfig.getNetwork());
            } else {
                Logger.debug("Docker network already exists: {}", this.dockerConfig.getNetwork());
            }
        } catch (Exception e) {
            Logger.error("Error creating Docker network: {}", e.getMessage());
        }
    }

    private void startLogStreaming(String serverId, String containerId) {
        this.executorService.submit(() -> {
            try {
                Closeable logCallback = this.dockerClient.logContainerCmd(containerId)
                        .withStdOut(true)
                        .withStdErr(true)
                        .withFollowStream(true)
                        .withSince((int) (System.currentTimeMillis() / 1000))
                        .exec(new ResultCallback.Adapter<Frame>() {
                            @Override
                            public void onNext(Frame frame) {
                                String logLine = new String(frame.getPayload()).trim();

                                if (logLine.isEmpty()) {
                                    return;
                                }

                                Map<String, Consumer<String>> subscribers = logSubscribers.get(serverId);
                                if (subscribers != null) {
                                    subscribers.values().forEach(consumer -> {
                                        try {
                                            consumer.accept(logLine);
                                        } catch (Exception e) {
                                            Logger.warn("Error notifying log subscriber: {}", e.getMessage());
                                        }
                                    });
                                }
                            }
                        });

                this.logStreamConnections.put(serverId, logCallback);
            } catch (Exception e) {
                Logger.error("Error in log streaming: {}", e.getMessage());
            }
        });
    }


    @Override
    public CompletableFuture<ServerStats> getServerStats(String serverId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String containerId = this.serverContainerIds.get(serverId);
                if (containerId == null) {
                    throw new IllegalArgumentException("Container ID not found for server: " + serverId);
                }

                final Statistics[] statsContainer = new Statistics[1];

                ResultCallback.Adapter<Statistics> callback =
                        new ResultCallback.Adapter<Statistics>() {
                            @Override
                            public void onNext(Statistics statistics) {
                                statsContainer[0] = statistics;
                            }
                        };

                this.dockerClient.statsCmd(containerId).exec(callback);
                Thread.sleep(1000);
                callback.close();

                Statistics statistics = statsContainer[0];
                if (statistics == null) {
                    throw new RuntimeException("Failed to collect statistics for container: " + containerId);
                }

                double cpuUsage = this.calculateCpuUsage(statistics);
                long memoryUsed = statistics.getMemoryStats().getUsage() != null ? statistics.getMemoryStats().getUsage() : 0L;
                long memoryLimit = statistics.getMemoryStats().getLimit() != null ? statistics.getMemoryStats().getLimit() : 0L;

                Map<String, StatisticNetworksConfig> networkStats = statistics.getNetworks();
                long networkRx = 0L;
                long networkTx = 0L;
                if (networkStats != null) {
                    for (StatisticNetworksConfig net : networkStats.values()) {
                        if (net != null) {
                            networkRx += net.getRxBytes() != null ? net.getRxBytes() : 0L;
                            networkTx += net.getTxBytes() != null ? net.getTxBytes() : 0L;
                        }
                    }
                }

                return ServerStats.builder()
                        .cpuUsagePercent(cpuUsage)
                        .memoryUsedBytes(memoryUsed)
                        .memoryTotalBytes(memoryLimit)
                        .diskUsedBytes(0L)
                        .diskTotalBytes(0L)
                        .networkRxBytes(networkRx)
                        .networkTxBytes(networkTx)
                        .timestamp(System.currentTimeMillis())
                        .build();

            } catch (Exception e) {
                Logger.error("Error collecting Docker container stats: {}", e.getMessage());
                throw new RuntimeException("Failed to collect container statistics", e);
            }
        }, this.executorService);
    }

    private double calculateCpuUsage(Statistics statistics) {
        CpuStatsConfig cpuStats = statistics.getCpuStats();
        CpuStatsConfig preCpuStats = statistics.getPreCpuStats();

        if (cpuStats == null || preCpuStats == null) {
            return 0.0;
        }

        Long cpuTotal = cpuStats.getCpuUsage() != null ? cpuStats.getCpuUsage().getTotalUsage() : null;
        Long preCpuTotal = preCpuStats.getCpuUsage() != null ? preCpuStats.getCpuUsage().getTotalUsage() : null;
        Long systemTotal = cpuStats.getSystemCpuUsage();
        Long preSystemTotal = preCpuStats.getSystemCpuUsage();

        if (cpuTotal == null || preCpuTotal == null || systemTotal == null || preSystemTotal == null) {
            return 0.0;
        }

        double cpuDelta = cpuTotal - preCpuTotal;
        double systemDelta = systemTotal - preSystemTotal;

        if (systemDelta > 0.0 && cpuDelta > 0.0) {
            Long onlineCpusLong = cpuStats.getOnlineCpus();
            int onlineCpus = onlineCpusLong != null ? onlineCpusLong.intValue() : Runtime.getRuntime().availableProcessors();
            if (onlineCpus == 0) {
                onlineCpus = Runtime.getRuntime().availableProcessors();
            }
            return (cpuDelta / systemDelta) * onlineCpus * 100.0;
        }

        return 0.0;
    }
    
    public DockerClient getDockerClient() {
        return this.dockerClient;
    }

    @Override
    public void shutdown() {
        Logger.info("Shutting down DockerServiceProvider");

        this.stopAndRemoveAllContainers();

        this.logStreamConnections.values().forEach(connection -> {
            try {
                connection.close();
            } catch (IOException e) {
                Logger.warn("Error closing log stream: {}", e.getMessage());
            }
        });
        this.logStreamConnections.clear();

        try {
            this.dockerClient.close();
        } catch (IOException e) {
            Logger.warn("Error closing Docker client: {}", e.getMessage());
        }


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

    private void stopAndRemoveAllContainers() {
        try {
            Logger.info("Stopping and removing all Atlas-managed Docker containers...");

            List<Container> containers = this.dockerClient.listContainersCmd()
                    .withLabelFilter(Map.of("atlas.managed", "true"))
                    .withShowAll(true)
                    .exec();

            int containerCount = containers.size();
            if (containerCount == 0) {
                return;
            }

            Logger.info("Found {} Atlas-managed containers to remove", containerCount);

            List<String> dynamicVolumesToCleanup = new ArrayList<>();
            
            for (Container container : containers) {
                String containerId = container.getId();
                String containerName = container.getNames()[0];

                try {
                    try {
                        InspectContainerResponse containerInfo = this.dockerClient.inspectContainerCmd(containerId).exec();
                        Map<String, String> labels = containerInfo.getConfig().getLabels();
                        if (labels != null && "true".equals(labels.get("atlas.dynamic"))) {
                            String serverId = labels.get("atlas.server-id");
                            if (serverId != null) {
                                AtlasServer server = AtlasBase.getInstance().getScalerManager().getServerFromTracking(serverId);
                                if (server != null && server.getWorkingDirectory() != null) {
                                    String volumePath = server.getWorkingDirectory();
                                    if (!volumePath.startsWith("/")) {
                                        volumePath = System.getProperty("user.dir") + "/" + volumePath;
                                    }
                                    dynamicVolumesToCleanup.add(volumePath);
                                }
                            }
                        }
                    } catch (Exception e) {
                        Logger.debug("Could not inspect container {} for volume cleanup", containerId.substring(0, 12));
                    }

                    if (container.getState().equalsIgnoreCase("running")) {
                        Logger.info("Stopping container: {} ({})", containerName, containerId.substring(0, 12));
                        this.dockerClient.stopContainerCmd(containerId).withTimeout(30).exec();
                    }

                    Logger.info("Removing container: {} ({})", containerName, containerId.substring(0, 12));
                    this.dockerClient.removeContainerCmd(containerId).withForce(true).exec();

                } catch (Exception e) {
                    Logger.error("Failed to stop/remove container {}: {}", containerName, e.getMessage());
                }
            }
            
            this.cleanupDynamicVolumesOnShutdown(dynamicVolumesToCleanup);

            this.serverContainerIds.clear();
            this.logSubscribers.clear();

            Logger.info("Successfully removed all {} Atlas-managed containers", containerCount);

        } catch (Exception e) {
            Logger.error("Error during container cleanup: {}", e.getMessage());
        }
    }
    
    private void cleanupDynamicVolumesOnShutdown(List<String> volumePaths) {
        try {
            AtlasBase atlasInstance = AtlasBase.getInstance();
            if (atlasInstance != null && atlasInstance.getConfigManager() != null) {
                boolean cleanupDynamicOnShutdown = atlasInstance.getConfigManager().getAtlasConfig().getAtlas().getTemplates().isCleanupDynamicOnShutdown();
                if (!cleanupDynamicOnShutdown) {
                    Logger.debug("Dynamic volume cleanup on shutdown is disabled in config");
                    return;
                }
            }
            
            DirectoryManager directoryManager = new DirectoryManager();
            int cleanedCount = 0;
            
            for (String volumePath : volumePaths) {
                try {
                    Path volumeDir = Paths.get(volumePath);
                    if (Files.exists(volumeDir)) {
                        directoryManager.deleteDirectoryRecursively(volumeDir);
                        cleanedCount++;
                    }
                } catch (Exception e) {
                    Logger.warn("Failed to cleanup volume directory during shutdown {}: {}", volumePath, e.getMessage());
                }
            }
            
            Path serversDir = Paths.get("servers");
            if (Files.exists(serversDir) && Files.isDirectory(serversDir)) {
                try {
                    DirectoryStream<Path> stream = Files.newDirectoryStream(serversDir);
                    for (Path serverDir : stream) {
                        String dirName = serverDir.getFileName().toString();
                        if (Files.isDirectory(serverDir) && dirName.contains("#")) {
                            try {
                                directoryManager.deleteDirectoryRecursively(serverDir);
                                cleanedCount++;
                                Logger.debug("Cleaned up dynamic server directory: {}", dirName);
                            } catch (Exception e) {
                                Logger.warn("Failed to cleanup dynamic directory {}: {}", serverDir, e.getMessage());
                            }
                        }
                    }
                    stream.close();
                } catch (Exception e) {
                    Logger.error("Error scanning servers directory for cleanup: {}", e.getMessage());
                }
            }
            
            if (cleanedCount > 0) {
                Logger.info("Cleaned up {} dynamic server directories during shutdown", cleanedCount);
            }
        } catch (Exception e) {
            Logger.error("Error during dynamic volume cleanup on shutdown: {}", e.getMessage());
        }
    }

    private void cleanupOldAtlasContainers() {
        try {
            List<Container> containers = this.dockerClient.listContainersCmd()
                    .withLabelFilter(Map.of("atlas.managed", "true"))
                    .withShowAll(true)
                    .exec();

            List<String> dynamicVolumePaths = new ArrayList<>();

            if (!containers.isEmpty()) {
                Logger.debug("Found {} old Atlas containers to clean up", containers.size());

                for (Container container : containers) {
                    String containerId = container.getId();
                    String containerName = container.getNames()[0];

                    try {
                        try {
                            InspectContainerResponse containerInfo = this.dockerClient.inspectContainerCmd(containerId).exec();
                            Map<String, String> labels = containerInfo.getConfig().getLabels();
                            if (labels == null) {
                                labels = new HashMap<>();
                            }

                            String dynamicLabel = labels.get("atlas.dynamic");
                            boolean isDynamic = dynamicLabel != null && dynamicLabel.equals("true");

                            if (isDynamic && containerInfo.getMounts() != null) {
                                for (InspectContainerResponse.Mount mount : containerInfo.getMounts()) {
                                    if (mount.getSource() != null && mount.getSource().contains("servers/")) {
                                        dynamicVolumePaths.add(mount.getSource());
                                        Logger.debug("Marked volume for cleanup: {}", mount.getSource());
                                    }
                                }
                            }
                        } catch (Exception e) {
                            Logger.debug("Could not inspect container {} for volume cleanup", containerId.substring(0, 12));
                        }

                        if (container.getState().equalsIgnoreCase("running")) {
                            Logger.debug("Stopping old container: {} ({})", containerName, containerId.substring(0, 12));
                            this.dockerClient.stopContainerCmd(containerId).withTimeout(10).exec();
                        }

                        Logger.debug("Removing old container: {} ({})", containerName, containerId.substring(0, 12));
                        this.dockerClient.removeContainerCmd(containerId).withForce(true).exec();

                    } catch (Exception e) {
                        Logger.debug("Failed to cleanup old container {}: {}", containerName, e.getMessage());
                    }
                }

                Logger.debug("Cleaned up {} old Atlas containers", containers.size());
            } else {
                Logger.debug("No old Atlas containers found during startup cleanup");
            }

            this.cleanupOrphanedDynamicVolumes(dynamicVolumePaths);

        } catch (Exception e) {
            Logger.debug("Error during startup container cleanup: {}", e.getMessage());
        }
    }

    private void cleanupOrphanedDynamicVolumes(List<String> knownVolumePaths) {
        try {
            AtlasBase atlasInstance = AtlasBase.getInstance();
            if (atlasInstance != null && atlasInstance.getConfigManager() != null) {
                boolean cleanupDynamicOnShutdown = atlasInstance.getConfigManager().getAtlasConfig().getAtlas().getTemplates().isCleanupDynamicOnShutdown();
                if (!cleanupDynamicOnShutdown) {
                    Logger.debug("Dynamic volume cleanup is disabled in config");
                    return;
                }
            }

            File serversDir = new File("servers");
            if (!serversDir.exists() || !serversDir.isDirectory()) {
                return;
            }

            File[] serverDirs = serversDir.listFiles();
            if (serverDirs == null) {
                return;
            }

            int cleanedCount = 0;

            for (File serverDir : serverDirs) {
                if (!serverDir.isDirectory()) {
                    continue;
                }

                if (!serverDir.getName().contains("#")) {
                    Logger.debug("Skipping static server directory during cleanup: {}", serverDir.getName());
                    continue;
                }

                String absolutePath = serverDir.getAbsolutePath();

                if (knownVolumePaths.contains(absolutePath)) {
                    try {
                        DirectoryManager directoryManager = new DirectoryManager();
                        directoryManager.deleteDirectoryRecursively(serverDir.toPath());
                        cleanedCount++;
                        Logger.debug("Cleaned up orphaned dynamic server volume: {}", serverDir.getName());
                    } catch (Exception e) {
                        Logger.debug("Failed to cleanup volume directory {}: {}", serverDir.getName(), e.getMessage());
                    }
                }

                boolean hasRunningContainer = false;
                try {
                    List<Container> runningContainers = this.dockerClient.listContainersCmd()
                            .withLabelFilter(Map.of("atlas.managed", "true"))
                            .exec();

                    for (Container container : runningContainers) {
                        if (container.getNames()[0].contains(serverDir.getName())) {
                            hasRunningContainer = true;
                            break;
                        }
                    }
                } catch (Exception e) {
                    hasRunningContainer = true;
                }

                if (!hasRunningContainer && !knownVolumePaths.contains(absolutePath)) {
                    try {
                        DirectoryManager directoryManager = new DirectoryManager();
                        directoryManager.deleteDirectoryRecursively(serverDir.toPath());
                        cleanedCount++;
                        Logger.debug("Cleaned up orphaned server volume: {}", serverDir.getName());
                    } catch (Exception e) {
                        Logger.debug("Failed to cleanup orphaned volume directory {}: {}", serverDir.getName(), e.getMessage());
                    }
                }
            }

            if (cleanedCount > 0) {
                Logger.debug("Cleaned up {} orphaned server volume directories", cleanedCount);
            }

        } catch (Exception e) {
            Logger.debug("Error during volume cleanup: {}", e.getMessage());
        }
    }

    private int getNextAvailableProxyPort(String serverName) {
        Integer previousPort = this.serverNameToPort.get(serverName);
        Logger.debug("Port allocation for server {}: previous port = {}, used ports = {}",
                serverName, previousPort, this.usedProxyPorts);

        if (previousPort != null && !this.usedProxyPorts.contains(previousPort)) {
            this.usedProxyPorts.add(previousPort);
            Logger.debug("Reusing port {} for server {}", previousPort, serverName);
            return previousPort;
        }

        if (previousPort != null) {
            Logger.debug("Cannot reuse port {} for server {} - port is already in use", previousPort, serverName);
        }

        int port = PROXY_PORT_START;
        while (this.usedProxyPorts.contains(port)) {
            port++;
        }

        this.usedProxyPorts.add(port);
        this.serverNameToPort.put(serverName, port);
        Logger.debug("Assigned new port {} to server {}", port, serverName);
        return port;
    }

    private void releaseProxyPort(int port) {
        this.usedProxyPorts.remove(port);
        Logger.debug("Released proxy port {} from used ports. Current used ports: {}", port, this.usedProxyPorts);
    }

    private boolean isProxyServer(String group) {
        return group != null && group.equalsIgnoreCase("proxy");
    }

    private void releaseProxyPortForServer(String serverId) {
        this.releaseProxyPortForServer(serverId, false);
    }

    private void releaseProxyPortForServer(String serverId, boolean deleteMapping) {
        Integer port = deleteMapping ? this.serverIdToPort.remove(serverId) : this.serverIdToPort.get(serverId);
        if (port != null) {
            boolean wasUsed = this.usedProxyPorts.contains(port);
            if (wasUsed) {
                this.releaseProxyPort(port);
                Logger.debug("Released proxy port {} for server {} (deleteMapping: {})", port, serverId, deleteMapping);
            } else {
                Logger.debug("Proxy port {} for server {} was already released", port, serverId);
            }
        } else {
            Logger.debug("No proxy port found for server {}", serverId);
        }
    }

    public void validateServerState() {
        try {
            List<Container> containers = this.dockerClient.listContainersCmd()
                    .withShowAll(true)
                    .withLabelFilter(Map.of("atlas.managed", "true"))
                    .exec();

            Set<String> actualContainerIds = containers.stream()
                    .map(Container::getId)
                    .collect(Collectors.toSet());

            List<String> zombieServerIds = new ArrayList<>();
            List<AtlasServer> allServers = AtlasBase.getInstance().getScalerManager().getAllServersFromTracking();
            for (AtlasServer server : allServers) {
                String serverId = server.getServerId();
                String containerId = this.serverContainerIds.get(serverId);
                if (containerId != null && !actualContainerIds.contains(containerId)) {

                    if (server.isShutdown()) {
                        Logger.debug("Skipping zombie detection for server being shutdown: {}", server.getName());
                        continue;
                    }

                    if (server.getType() == ServerType.STATIC) {
                        boolean isManuallyStopped = false;
                        Scaler scaler = AtlasBase.getInstance().getScalerManager().getScaler(server.getGroup());
                        if (scaler != null && scaler.getManuallyStopped().contains(serverId)) {
                            isManuallyStopped = true;
                        }
                        
                        if (isManuallyStopped) {
                            Logger.debug("Skipping zombie detection for manually stopped STATIC server: {}", server.getName());
                            continue;
                        }
                    }
                    
                    Logger.warn("Detected zombie server: {} (container {} not found in Docker)", serverId, containerId.substring(0, 12));
                    Logger.debug("Cleaning up zombie server tracking for: {}", serverId);

                    this.serverContainerIds.remove(serverId);
                    this.logSubscribers.remove(serverId);
                    this.serverIdToPort.remove(serverId);

                    zombieServerIds.add(serverId);
                }
            }

            if (!zombieServerIds.isEmpty()) {
                AtlasBase atlasInstance = AtlasBase.getInstance();
                if (atlasInstance != null && atlasInstance.getScalerManager() != null) {
                    atlasInstance.getScalerManager().getScalers().forEach(scaler -> {
                        zombieServerIds.forEach(scaler::removeServerFromTracking);
                    });
                    Logger.debug("Cleaned up {} zombie servers from Atlas tracking", zombieServerIds.size());
                }
            }

            for (Container container : containers) {
                String containerId = container.getId();
                if (!this.serverContainerIds.containsValue(containerId)) {
                    Logger.warn("Detected untracked container: {} ({})", container.getNames()[0], containerId.substring(0, 12));
                }
            }

        } catch (Exception e) {
            Logger.debug("Error validating server state: {}", e.getMessage());
        }
    }

    @Override
    public CompletableFuture<AtlasServer> startServerCompletely(AtlasServer server, StartOptions options) {
        if (server == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Cannot start null server"));
        }

        Logger.info("Starting server: {} (reason: {}, directory: {}, templates: {})",
                server.getName(), options.getReason(), options.isPrepareDirectory(), options.isApplyTemplates());

        return CompletableFuture.supplyAsync(() -> {
            try {
                return this.performUnifiedStart(server, options);
            } catch (Exception e) {
                Logger.error("Unified start failed for server: {}", server.getName(), e);

                if (options.isCleanupOnFailure()) {
                    this.cleanupFailedStart(server);
                }

                throw new RuntimeException("Failed to start server: " + server.getName(), e);
            }
        }, this.executorService);
    }

    private AtlasServer performUnifiedStart(AtlasServer server, StartOptions options) throws Exception {
        String serverId = server.getServerId();
        String serverName = server.getName();

        Logger.debug("Starting unified start process for server: {} ({})", serverName, serverId);

        server.setLastHeartbeat(System.currentTimeMillis());

        this.validateStartConditions(server, options);

        if (options.isPrepareDirectory()) {
            this.prepareServerDirectory(server, options);
        }

        if (options.isApplyTemplates()) {
            this.applyServerTemplates(server, options);
        }
        if (options.isValidateResources()) {
            this.validateStartResources(server, options);
        }

        AtlasServer startedServer = this.createAndStartContainer(server, options);

        if (options.isAddToTracking()) {
            this.addServerToTracking(startedServer);
        }

        if (options.isWaitForReady()) {
            this.waitForServerReady(startedServer, options.getTimeoutSeconds());
        }

        Logger.debug("Successfully completed unified start for server: {} ({})", serverName, serverId);
        return startedServer;
    }

    private void validateStartConditions(AtlasServer server, StartOptions options) throws Exception {
        if (server.getServerInfo() != null && server.getServerInfo().getStatus() == ServerStatus.RUNNING) {
            if (options.getReason() != StartReason.RECOVERY && options.getReason() != StartReason.RESTART) {
                throw new IllegalStateException("Server is already running: " + server.getName());
            }
        }

        if (server.isShutdown() && options.getReason() != StartReason.RESTART && options.getReason() != StartReason.RECOVERY) {
            throw new IllegalStateException("Cannot start server that is being shutdown: " + server.getName());
        }

        if (options.getReason() == StartReason.RESTART || options.getReason() == StartReason.RECOVERY) {
            server.setShutdown(false);
            Logger.debug("Reset shutdown flag for server: {} (reason: {})", server.getName(), options.getReason());
        }

        Logger.debug("Start conditions validated for server: {}", server.getName());
    }

    private void prepareServerDirectory(AtlasServer server, StartOptions options) throws Exception {
        if (server.getWorkingDirectory() != null) {
            Logger.debug("Working directory already set for server: {}", server.getName());
            return;
        }

        ScalerConfig.Group groupConfig = this.getGroupConfigForServer(server);
        if (groupConfig == null) {
            throw new Exception("Cannot prepare directory without group config for server: " + server.getName());
        }

        AtlasBase atlasBase = AtlasBase.getInstance();
        if (atlasBase != null) {
            ServerLifecycleManager lifecycleManager = new ServerLifecycleManager();
            this.prepareServerDirectoryInternal(server, groupConfig);
        }

        Logger.debug("Directory preparation completed for server: {}", server.getName());
    }

    private void prepareServerDirectoryInternal(AtlasServer server, ScalerConfig.Group groupConfig) {
        DirectoryManager directoryManager = new DirectoryManager();

        String workingDirectory = directoryManager.createServerDirectory(server);
        server.setWorkingDirectory(workingDirectory);

        Logger.debug("Created server directory: {}", workingDirectory);
    }

    private void applyServerTemplates(AtlasServer server, StartOptions options) throws Exception {
        if (server.getWorkingDirectory() == null) {
            Logger.warn("Cannot apply templates - no working directory set for server: {}", server.getName());
            return;
        }

        ScalerConfig.Group groupConfig = this.getGroupConfigForServer(server);
        if (groupConfig == null) {
            throw new Exception("Cannot apply templates without group config for server: " + server.getName());
        }

        AtlasBase atlasBase = AtlasBase.getInstance();
        if (atlasBase != null) {
            boolean downloadOnStartup = atlasBase.getConfigManager().getAtlasConfig().getAtlas().getTemplates().isDownloadOnStartup();

            if (downloadOnStartup && groupConfig.getTemplates() != null && !groupConfig.getTemplates().isEmpty()) {
                TemplateManager templateManager = new TemplateManager(atlasBase.getConfigManager().getAtlasConfig().getAtlas().getTemplates());
                templateManager.applyTemplates(server.getWorkingDirectory(), groupConfig.getTemplates());
                templateManager.close();
                Logger.debug("Applied templates to server: {}", server.getName());
            } else {
                Logger.debug("Skipping template application for server: {} (downloadOnStartup={}, templates={})",
                        server.getName(), downloadOnStartup, groupConfig.getTemplates() != null ? groupConfig.getTemplates().size() : 0);
            }
        }
    }

    private void validateStartResources(AtlasServer server, StartOptions options) throws Exception {
        // Nothing for now, might add resource validation in the future

    }

    private AtlasServer createAndStartContainer(AtlasServer server, StartOptions options) throws Exception {
        try {
            ScalerConfig.Group groupConfig = this.getGroupConfigForServer(server);
            if (groupConfig == null) {
                throw new Exception("No group configuration found for server group: " + server.getGroup());
            }

            CompletableFuture<AtlasServer> createFuture = this.createServer(groupConfig, server);
            AtlasServer startedServer = createFuture.get(options.getTimeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS);

            if (this.logSubscribers.containsKey(server.getServerId()) && !this.logSubscribers.get(server.getServerId()).isEmpty()) {
                String containerId = this.serverContainerIds.get(server.getServerId());
                if (containerId != null) {
                    this.startLogStreaming(server.getServerId(), containerId);
                    Logger.debug("Restored log streaming for server: {}", server.getName());
                }
            }
            
            return startedServer;
        } catch (Exception e) {
            throw new Exception("Failed to create and start container for server: " + server.getName(), e);
        }
    }

    private ScalerConfig.Group getGroupConfigForServer(AtlasServer server) {
        try {
            AtlasBase atlasInstance = AtlasBase.getInstance();
            if (atlasInstance != null && atlasInstance.getScalerManager() != null) {
                Scaler scaler = atlasInstance.getScalerManager().getScaler(server.getGroup());
                if (scaler != null) {
                    return scaler.getScalerConfig().getGroup();
                }
            }
        } catch (Exception e) {
            Logger.warn("Failed to get group config for server {}: {}", server.getName(), e.getMessage());
        }
        return null;
    }

    private void addServerToTracking(AtlasServer server) {
        Logger.debug("Server tracking is now handled by scaler: {}", server.getName());
    }

    private void waitForServerReady(AtlasServer server, int timeoutSeconds) throws Exception {
        long startTime = System.currentTimeMillis();
        long timeoutMillis = timeoutSeconds * 1000L;

        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            if (server.getServerInfo() != null && server.getServerInfo().getStatus() == ServerStatus.RUNNING) {
                Logger.debug("Server ready: {}", server.getName());
                return;
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Exception("Interrupted while waiting for server to be ready: " + server.getName());
            }
        }

        throw new Exception("Server did not become ready within timeout: " + server.getName());
    }

    private void cleanupFailedStart(AtlasServer server) {
        try {
            Logger.debug("Cleaning up failed start for server: {}", server.getName());

            String containerId = this.serverContainerIds.remove(server.getServerId());
            if (containerId != null) {
                try {
                    this.dockerClient.removeContainerCmd(containerId).withForce(true).exec();
                    Logger.debug("Removed failed container: {}", containerId.substring(0, 12));
                } catch (Exception e) {
                    Logger.warn("Failed to remove container during cleanup: {}", e.getMessage());
                }
            }

            this.releaseServerPort(server);

        } catch (Exception e) {
            Logger.error("Error during failed start cleanup for server: {}", server.getName(), e);
        }
    }
    
    /**
     * Waits for a container to actually stop and then updates the server status.
     */
    private void waitForContainerStop(AtlasServer server, String containerId) {
        this.executorService.submit(() -> {
            int attempts = 0;
            int maxAttempts = 60;
            
            while (attempts < maxAttempts) {
                try {
                    InspectContainerResponse containerInfo = this.dockerClient.inspectContainerCmd(containerId).exec();
                    Boolean running = containerInfo.getState().getRunning();
                    
                    if (running == null || !running) {
                        Logger.debug("Container for server {} has stopped after {} ms", server.getName(), attempts * 500);

                        AtlasBase atlasInstance = AtlasBase.getInstance();
                        if (atlasInstance != null && atlasInstance.getScalerManager() != null) {
                            Scaler scaler = atlasInstance.getScalerManager().getScaler(server.getGroup());
                            if (scaler != null && scaler.isCurrentlyRestarting(server.getServerId())) {
                                Logger.debug("Server {} container stopped during restart - skipping status update to STOPPED", server.getName());
                                return;
                            }
                        }

                        if (server.getServerInfo() != null) {
                            ServerInfo stoppedInfo = ServerInfo.builder()
                                .status(ServerStatus.STOPPED)
                                .onlinePlayers(0)
                                .maxPlayers(server.getServerInfo().getMaxPlayers())
                                .onlinePlayerNames(new HashSet<>())
                                .build();
                            server.updateServerInfo(stoppedInfo);
                        }

                        if (AtlasBase.getInstance().getNettyServer() != null) {
                            AtlasBase.getInstance().getNettyServer().broadcastServerUpdate(server);
                        }

                        ServerLifecycleService lifecycleService = new ServerLifecycleService(AtlasBase.getInstance());
                        lifecycleService.cleanupServerResourcesAfterStop(server);

                        if (server.isShouldRestartAfterStop()) {
                            server.setShouldRestartAfterStop(false);
                            Logger.info("Restarting server {} after container stopped", server.getName());
                            lifecycleService.restartServer(server).exceptionally(throwable -> {
                                Logger.error("Failed to restart server {} after container stopped: {}", server.getName(), throwable.getMessage());
                                return null;
                            });
                        }
                        
                        return;
                    }
                    
                    Thread.sleep(500);
                    attempts++;
                } catch (Exception e) {
                    Logger.error("Error checking container status for {}: {}", server.getName(), e.getMessage());
                    return;
                }
            }
            
            Logger.warn("Container for server {} did not stop within 30 seconds", server.getName());
        });
    }
    
    private static class NetworkStatsCache {
        final long rxBytes;
        final long txBytes;
        final long timestamp;
        
        NetworkStatsCache(long rxBytes, long txBytes, long timestamp) {
            this.rxBytes = rxBytes;
            this.txBytes = txBytes;
            this.timestamp = timestamp;
        }
    }

    @Override
    public String getContainerIdForServer(String serverId) {
        return this.serverContainerIds.get(serverId);
    }
    
    @Override
    public void waitForContainerStopAndRestart(AtlasServer server, String containerId) {
        this.waitForContainerStop(server, containerId);
    }
}