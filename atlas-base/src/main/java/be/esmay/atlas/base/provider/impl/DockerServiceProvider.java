package be.esmay.atlas.base.provider.impl;

import be.esmay.atlas.base.AtlasBase;
import be.esmay.atlas.base.config.impl.AtlasConfig;
import be.esmay.atlas.base.config.impl.ScalerConfig;
import be.esmay.atlas.base.directory.DirectoryManager;
import be.esmay.atlas.base.provider.ServiceProvider;
import be.esmay.atlas.base.utils.Logger;
import be.esmay.atlas.common.enums.ServerStatus;
import be.esmay.atlas.common.models.AtlasServer;
import be.esmay.atlas.common.models.ServerInfo;
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
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.Info;
import com.github.dockerjava.api.model.Network;
import com.github.dockerjava.api.model.NetworkSettings;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.api.model.StatisticNetworksConfig;
import com.github.dockerjava.api.model.Statistics;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
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
    private final Map<String, AtlasServer> servers;
    private final Map<String, String> serverContainerIds;
    private final Map<String, Closeable> logStreamConnections;
    private final Map<String, Map<String, Consumer<String>>> logSubscribers;
    private final ExecutorService executorService;

    private final Set<Integer> usedProxyPorts;
    private static final int PROXY_PORT_START = 25565;

    private static final Map<String, CompletableFuture<Void>> IMAGE_PULL_FUTURES = new ConcurrentHashMap<>();
    private static final Set<String> PULLED_IMAGES = ConcurrentHashMap.newKeySet();

    private final String cachedHostIp;

    public DockerServiceProvider(AtlasConfig.ServiceProvider serviceProviderConfig) {
        super("docker");
        this.dockerConfig = serviceProviderConfig.getDocker();
        this.servers = new ConcurrentHashMap<>();
        this.serverContainerIds = new ConcurrentHashMap<>();
        this.logStreamConnections = new ConcurrentHashMap<>();
        this.logSubscribers = new ConcurrentHashMap<>();
        this.usedProxyPorts = ConcurrentHashMap.newKeySet();
        this.executorService = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "Docker-Provider");
            thread.setDaemon(true);
            return thread;
        });

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
                String containerId = this.createDockerContainer(groupConfig, atlasServer);
                if (containerId == null) {
                    throw new RuntimeException("Failed to create Docker container");
                }

                this.serverContainerIds.put(atlasServer.getServerId(), containerId);
                this.dockerClient.startContainerCmd(containerId).exec();

                InspectContainerResponse containerInfo = this.dockerClient.inspectContainerCmd(containerId).exec();
                String ipAddress = this.getContainerIpAddress(containerInfo);

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
                        .port(25565)
                        .type(atlasServer.getType())
                        .createdAt(atlasServer.getCreatedAt())
                        .serviceProviderId(containerId)
                        .isManuallyScaled(atlasServer.isManuallyScaled())
                        .lastHeartbeat(System.currentTimeMillis())
                        .serverInfo(serverInfo)
                        .build();

                this.servers.put(atlasServer.getServerId(), updatedServer);
                Logger.debug("Created Docker container: {} (ID: {}, Container: {})", atlasServer.getName(), atlasServer.getServerId(), containerId);

                return updatedServer;
            } catch (Exception e) {
                Logger.error("Failed to create Docker server: {}", e.getMessage());
                throw new RuntimeException(e);
            }
        }, this.executorService);
    }

    @Override
    public CompletableFuture<Void> startServer(AtlasServer atlasServer) {
        return CompletableFuture.runAsync(() -> {
            try {
                String containerId = this.serverContainerIds.get(atlasServer.getServerId());
                if (containerId == null) {
                    Logger.warn("Container ID not found for server: {}", atlasServer.getServerId());
                    return;
                }

                this.dockerClient.startContainerCmd(containerId).exec();

                if (atlasServer.getServerInfo() != null) {
                    atlasServer.getServerInfo().setStatus(ServerStatus.STARTING);
                    atlasServer.setLastHeartbeat(System.currentTimeMillis());
                }

                Logger.info("Started Docker container: {}", containerId);
            } catch (Exception e) {
                Logger.error("Error starting Docker server: {}", e.getMessage());
            }
        }, this.executorService);
    }

    @Override
    public CompletableFuture<Void> stopServer(AtlasServer atlasServer) {
        return CompletableFuture.runAsync(() -> {
            try {
                String containerId = this.serverContainerIds.get(atlasServer.getServerId());
                if (containerId == null) {
                    Logger.warn("Container ID not found for server: {}", atlasServer.getServerId());
                    return;
                }

                if (atlasServer.getServerInfo() != null) {
                    atlasServer.getServerInfo().setStatus(ServerStatus.STOPPING);
                }

                this.dockerClient.stopContainerCmd(containerId).withTimeout(30).exec();
                this.waitForContainerStop(containerId);

                if (atlasServer.getServerInfo() != null) {
                    atlasServer.getServerInfo().setStatus(ServerStatus.STOPPED);
                    atlasServer.getServerInfo().setOnlinePlayers(0);
                    atlasServer.getServerInfo().setOnlinePlayerNames(new HashSet<>());
                }

                Logger.debug("Stopped Docker container: {}", containerId);
            } catch (Exception e) {
                Logger.error("Error stopping Docker server: {}", e.getMessage());
            }
        }, this.executorService);
    }

    @Override
    public CompletableFuture<Boolean> deleteServer(String serverId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String containerId = this.serverContainerIds.get(serverId);
                if (containerId == null) {
                    Logger.warn("Container ID not found for server: {}", serverId);
                    return false;
                }

                AtlasServer serverInfo = this.servers.get(serverId);
                if (serverInfo == null) {
                    Logger.warn("Server info not found for server: {}", serverId);
                    return false;
                }

                if (serverInfo.isShutdown()) {
                    Logger.debug("Server {} is already being deleted, skipping duplicate deletion", serverId);
                    return true;
                }

                serverInfo.setShutdown(true);
                Logger.debug("Marked server {} as shutting down", serverId);

                Closeable logConnection = this.logStreamConnections.remove(serverId);
                if (logConnection != null) {
                    try {
                        logConnection.close();
                    } catch (IOException e) {
                        Logger.warn("Error closing log stream: {}", e.getMessage());
                    }
                }

                boolean isDynamic = false;
                String volumePath = null;
                try {
                    InspectContainerResponse containerInfo = this.dockerClient.inspectContainerCmd(containerId).exec();
                    Map<String, String> labels = containerInfo.getConfig().getLabels();
                    String dynamicLabel = labels == null ? null : labels.get("atlas.dynamic");
                    isDynamic = dynamicLabel != null && dynamicLabel.equals("true");

                    if (isDynamic && serverInfo.getWorkingDirectory() != null) {
                        volumePath = serverInfo.getWorkingDirectory();
                        if (!volumePath.startsWith("/")) {
                            volumePath = System.getProperty("user.dir") + "/" + volumePath;
                        }
                    }

                    if (this.isProxyServer(serverInfo.getGroup())) {
                        this.releaseProxyPortForContainer(containerId);
                    }

                    if (Boolean.TRUE.equals(containerInfo.getState().getRunning())) {
                        Logger.debug("Stopping container {} before removal", containerId.substring(0, 12));
                        this.dockerClient.stopContainerCmd(containerId).exec();
                        this.waitForContainerStop(containerId);
                    }
                } catch (Exception e) {
                    Logger.debug("Could not inspect container for cleanup info: {}", e.getMessage());
                }

                this.dockerClient.removeContainerCmd(containerId).withForce(true).exec();
                this.waitForContainerDeletion(containerId);

                if (isDynamic && volumePath != null) {
                    this.cleanupServerVolume(volumePath, serverId);
                }

                this.servers.remove(serverId);
                this.serverContainerIds.remove(serverId);
                this.logSubscribers.remove(serverId);

                Logger.debug("Deleted Docker container: {} (Server: {})", containerId, serverId);
                return true;
            } catch (Exception e) {
                Logger.error("Error deleting Docker server: {}", e.getMessage());
                return false;
            }
        }, this.executorService);
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

        Logger.warn("Container {} may not be fully deleted after 10 seconds", containerId.substring(0, 12));
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

        Logger.warn("Container {} may not be fully stopped after 10 seconds", containerId.substring(0, 12));
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
        return CompletableFuture.completedFuture(Optional.ofNullable(this.servers.get(serverId)));
    }

    @Override
    public CompletableFuture<List<AtlasServer>> getAllServers() {
        return CompletableFuture.completedFuture(new ArrayList<>(this.servers.values()));
    }

    @Override
    public CompletableFuture<List<AtlasServer>> getServersByGroup(String group) {
        return CompletableFuture.supplyAsync(() -> this.servers.values().stream()
                .filter(server -> server.getGroup().equals(group))
                .collect(Collectors.toList())
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
            if (!this.servers.containsKey(serverId)) {
                Logger.warn("Server not found for update: {}", serverId);
                return false;
            }

            this.servers.put(serverId, updatedServer);
            Logger.debug("Updated server status for: {}", updatedServer.getName());
            return true;
        });
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

            AtlasBase atlasInstance = AtlasBase.getInstance();
            if (atlasInstance != null && atlasInstance.getConfigManager() != null) {
                AtlasConfig.Network networkConfig = atlasInstance.getConfigManager().getAtlasConfig().getAtlas().getNetwork();
                envVars.add("ATLAS_HOST=" + this.getHostIpForContainers());
                envVars.add("ATLAS_PORT=" + networkConfig.getPort());
                envVars.add("ATLAS_NETTY_KEY=" + networkConfig.getNettyKey());
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
                    .withName("atlas-" + atlasServer.getServerId())
                    .withLabels(labels)
                    .withEnv(envVars)
                    .withBinds(this.createBinds(dockerGroupConfig, atlasServer))
                    .withNetworkMode(this.dockerConfig.getNetwork());

            if (this.isProxyServer(atlasServer.getGroup())) {
                int hostPort = this.getNextAvailableProxyPort();
                createCmd = createCmd.withPortBindings(PortBinding.parse(hostPort + ":25565"));

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

            CreateContainerResponse container = createCmd.exec();
            return container.getId();
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
                    if (!address.isLoopbackAddress() && address instanceof Inet4Address) {
                        Logger.debug("Using host IP for Atlas host: {}", address.getHostAddress());
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Logger.warn("Failed to get host network interface: {}", e.getMessage());
        }

        try {
            InetAddress localHost = InetAddress.getLocalHost();
            String hostAddress = localHost.getHostAddress();
            if (!hostAddress.equals("127.0.0.1")) {
                Logger.debug("Using local host IP for Atlas host: {}", hostAddress);
                return hostAddress;
            }
        } catch (Exception e) {
            Logger.warn("Failed to resolve local host: {}", e.getMessage());
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

            for (Container container : containers) {
                String containerId = container.getId();
                String containerName = container.getNames()[0];

                try {
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

            this.servers.clear();
            this.serverContainerIds.clear();
            this.logSubscribers.clear();

            Logger.info("Successfully removed all {} Atlas-managed containers", containerCount);

        } catch (Exception e) {
            Logger.error("Error during container cleanup: {}", e.getMessage());
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

    private int getNextAvailableProxyPort() {
        int port = PROXY_PORT_START;
        while (this.usedProxyPorts.contains(port)) {
            port++;
        }
        this.usedProxyPorts.add(port);
        return port;
    }

    private void releaseProxyPort(int port) {
        this.usedProxyPorts.remove(port);
    }

    private boolean isProxyServer(String group) {
        return group != null && group.equalsIgnoreCase("proxy");
    }

    private void releaseProxyPortForContainer(String containerId) {
        try {
            InspectContainerResponse containerInfo = this.dockerClient.inspectContainerCmd(containerId).exec();
            if (containerInfo.getNetworkSettings() != null &&
                    containerInfo.getNetworkSettings().getPorts() != null) {

                Ports ports = containerInfo.getNetworkSettings().getPorts();
                for (Map.Entry<ExposedPort, Ports.Binding[]> entry : ports.getBindings().entrySet()) {
                    ExposedPort exposedPort = entry.getKey();
                    Ports.Binding[] bindings = entry.getValue();

                    if (bindings != null && exposedPort.getPort() == 25565) {
                        for (Ports.Binding binding : bindings) {
                            try {
                                int hostPort = Integer.parseInt(binding.getHostPortSpec());
                                this.releaseProxyPort(hostPort);
                                Logger.debug("Released proxy port {} for container {}", hostPort, containerId.substring(0, 12));
                            } catch (NumberFormatException e) {
                                Logger.debug("Could not parse host port: {}", binding.getHostPortSpec());
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Logger.debug("Could not release proxy port for container {}: {}", containerId.substring(0, 12), e.getMessage());
        }
    }
}