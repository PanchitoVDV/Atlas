package be.esmay.atlas.base.provider.impl;

import be.esmay.atlas.base.config.impl.AtlasConfig;
import be.esmay.atlas.base.config.impl.ScalerConfig;
import be.esmay.atlas.base.provider.ServiceProvider;
import be.esmay.atlas.base.utils.Logger;
import be.esmay.atlas.common.enums.ServerStatus;
import be.esmay.atlas.common.models.ServerInfo;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.LogContainerCmd;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.Info;
import com.github.dockerjava.api.model.Network;
import com.github.dockerjava.api.model.NetworkSettings;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
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
    private final Map<String, ServerInfo> servers;
    private final Map<String, String> serverContainerIds;
    private final Map<String, Closeable> logStreamConnections;
    private final Map<String, Map<String, Consumer<String>>> logSubscribers;
    private final ExecutorService executorService;

    private static final Map<String, CompletableFuture<Void>> IMAGE_PULL_FUTURES = new ConcurrentHashMap<>();
    private static final Set<String> PULLED_IMAGES = ConcurrentHashMap.newKeySet();

    public DockerServiceProvider(AtlasConfig.ServiceProvider serviceProviderConfig) {
        super("docker");
        this.dockerConfig = serviceProviderConfig.getDocker();
        this.servers = new ConcurrentHashMap<>();
        this.serverContainerIds = new ConcurrentHashMap<>();
        this.logStreamConnections = new ConcurrentHashMap<>();
        this.logSubscribers = new ConcurrentHashMap<>();
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

        if (this.dockerConfig.isAutoCreateNetwork() && this.dockerConfig.getNetwork() != null) {
            this.createNetworkIfNotExists();
        }

        this.cleanupOldAtlasContainers();
    }

    @Override
    public CompletableFuture<ServerInfo> createServer(ScalerConfig.Group groupConfig, ServerInfo serverInfo) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String containerId = this.createDockerContainer(groupConfig, serverInfo);
                if (containerId == null) {
                    throw new RuntimeException("Failed to create Docker container");
                }

                this.serverContainerIds.put(serverInfo.getServerId(), containerId);
                this.dockerClient.startContainerCmd(containerId).exec();

                InspectContainerResponse containerInfo = this.dockerClient.inspectContainerCmd(containerId).exec();
                String ipAddress = this.getContainerIpAddress(containerInfo);
                ServerInfo updatedServer = ServerInfo.builder()
                        .serverId(serverInfo.getServerId())
                        .name(serverInfo.getName())
                        .group(serverInfo.getGroup())
                        .workingDirectory(serverInfo.getWorkingDirectory())
                        .address(ipAddress)
                        .port(25565)
                        .type(serverInfo.getType())
                        .status(ServerStatus.STARTING)
                        .onlinePlayers(0)
                        .maxPlayers(serverInfo.getMaxPlayers())
                        .onlinePlayerNames(new HashSet<>())
                        .createdAt(serverInfo.getCreatedAt())
                        .lastHeartbeat(System.currentTimeMillis())
                        .serviceProviderId(containerId)
                        .isManuallyScaled(serverInfo.isManuallyScaled())
                        .build();

                this.servers.put(serverInfo.getServerId(), updatedServer);
                Logger.debug("Created Docker container: {} (ID: {}, Container: {})",
                        serverInfo.getName(), serverInfo.getServerId(), containerId);

                return updatedServer;
            } catch (Exception e) {
                Logger.error("Failed to create Docker server: {}", e.getMessage());
                throw new RuntimeException(e);
            }
        }, this.executorService);
    }

    @Override
    public CompletableFuture<Void> startServer(ServerInfo serverInfo) {
        return CompletableFuture.runAsync(() -> {
            try {
                String containerId = this.serverContainerIds.get(serverInfo.getServerId());
                if (containerId == null) {
                    Logger.warn("Container ID not found for server: {}", serverInfo.getServerId());
                    return;
                }

                this.dockerClient.startContainerCmd(containerId).exec();

                serverInfo.setStatus(ServerStatus.STARTING);
                serverInfo.setLastHeartbeat(System.currentTimeMillis());

                Logger.info("Started Docker container: {}", containerId);
            } catch (Exception e) {
                Logger.error("Error starting Docker server: {}", e.getMessage());
            }
        }, this.executorService);
    }

    @Override
    public CompletableFuture<Void> stopServer(ServerInfo serverInfo) {
        return CompletableFuture.runAsync(() -> {
            try {
                String containerId = this.serverContainerIds.get(serverInfo.getServerId());
                if (containerId == null) {
                    Logger.warn("Container ID not found for server: {}", serverInfo.getServerId());
                    return;
                }

                serverInfo.setStatus(ServerStatus.STOPPING);

                this.dockerClient.stopContainerCmd(containerId).withTimeout(30).exec();

                serverInfo.setStatus(ServerStatus.STOPPED);
                serverInfo.setOnlinePlayers(0);
                serverInfo.setOnlinePlayerNames(new HashSet<>());

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

                ServerInfo serverInfo = this.servers.get(serverId);

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

                    if (isDynamic && serverInfo != null && serverInfo.getWorkingDirectory() != null) {
                        volumePath = serverInfo.getWorkingDirectory();
                        if (!volumePath.startsWith("/")) {
                            volumePath = System.getProperty("user.dir") + "/" + volumePath;
                        }
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

    private void cleanupServerVolume(String volumePath, String serverId) {
        try {
            File volumeDir = new File(volumePath);
            if (volumeDir.exists() && volumeDir.isDirectory()) {
                this.deleteDirectoryRecursively(volumeDir);
                Logger.debug("Cleaned up volume directory for dynamic server: {}", serverId);
            }
        } catch (Exception e) {
            Logger.warn("Failed to cleanup volume directory for server {}: {}", serverId, e.getMessage());
        }
    }

    private void deleteDirectoryRecursively(File directory) throws IOException {
        if (!directory.exists()) {
            return;
        }

        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    this.deleteDirectoryRecursively(file);
                } else {
                    if (!file.delete()) {
                        throw new IOException("Failed to delete file: " + file.getAbsolutePath());
                    }
                }
            }
        }

        if (!directory.delete()) {
            throw new IOException("Failed to delete directory: " + directory.getAbsolutePath());
        }
    }

    @Override
    public CompletableFuture<Optional<ServerInfo>> getServer(String serverId) {
        return CompletableFuture.completedFuture(Optional.ofNullable(this.servers.get(serverId)));
    }

    @Override
    public CompletableFuture<List<ServerInfo>> getAllServers() {
        return CompletableFuture.completedFuture(new ArrayList<>(this.servers.values()));
    }

    @Override
    public CompletableFuture<List<ServerInfo>> getServersByGroup(String group) {
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
    public CompletableFuture<Boolean> updateServerStatus(String serverId, ServerInfo updatedInfo) {
        return CompletableFuture.supplyAsync(() -> {
            if (!this.servers.containsKey(serverId)) {
                Logger.warn("Server not found for update: {}", serverId);
                return false;
            }

            this.servers.put(serverId, updatedInfo);
            Logger.debug("Updated server status for: {}", updatedInfo.getName());
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

    private String createDockerContainer(ScalerConfig.Group groupConfig, ServerInfo serverInfo) {
        try {
            ScalerConfig.Docker dockerGroupConfig = groupConfig.getServiceProvider().getDocker();

            List<String> envVars = new ArrayList<>();
            envVars.add("EULA=TRUE");
            envVars.add("SERVER_NAME=" + serverInfo.getName());
            envVars.add("SERVER_GROUP=" + serverInfo.getGroup());
            envVars.add("SERVER_UUID=" + serverInfo.getServerId());
            envVars.add("ATLAS_MANAGED=true");
            envVars.add("SERVER_TYPE=" + groupConfig.getServer().getType());

            if (dockerGroupConfig.getEnvironment() != null) {
                dockerGroupConfig.getEnvironment().forEach((key, value) ->
                        envVars.add(key + "=" + value));
            }

            Map<String, String> labels = new HashMap<>();
            labels.put("atlas.managed", "true");
            labels.put("atlas.server-id", serverInfo.getServerId());
            labels.put("atlas.group", serverInfo.getGroup());
            labels.put("atlas.server-name", serverInfo.getName());
            labels.put("atlas.version", "1.0");
            labels.put("atlas.server-type", groupConfig.getServer().getType());
            labels.put("atlas.dynamic", String.valueOf(serverInfo.getType().name().equals("DYNAMIC")));

            CreateContainerCmd createCmd = this.dockerClient.createContainerCmd(dockerGroupConfig.getImage())
                    .withName("atlas-" + serverInfo.getName())
                    .withLabels(labels)
                    .withEnv(envVars)
                    .withBinds(this.createBinds(dockerGroupConfig, serverInfo))
                    .withNetworkMode(this.dockerConfig.getNetwork());

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

    private Bind[] createBinds(ScalerConfig.Docker dockerConfig, ServerInfo serverInfo) {
        List<Bind> binds = new ArrayList<>();

        if (serverInfo.getWorkingDirectory() != null) {
            String mountPath = dockerConfig.getVolumeMountPath();
            if (mountPath == null || mountPath.isEmpty()) {
                mountPath = "/data";
            }

            String absoluteWorkingDir = serverInfo.getWorkingDirectory();
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

            List<com.github.dockerjava.api.model.Container> containers = this.dockerClient.listContainersCmd()
                    .withLabelFilter(Map.of("atlas.managed", "true"))
                    .withShowAll(true)
                    .exec();

            int containerCount = containers.size();
            if (containerCount == 0) {
                Logger.info("No Atlas-managed containers found");
                return;
            }

            Logger.info("Found {} Atlas-managed containers to remove", containerCount);

            for (com.github.dockerjava.api.model.Container container : containers) {
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
            List<com.github.dockerjava.api.model.Container> containers = this.dockerClient.listContainersCmd()
                    .withLabelFilter(Map.of("atlas.managed", "true"))
                    .withShowAll(true)
                    .exec();

            List<String> dynamicVolumePaths = new ArrayList<>();

            if (!containers.isEmpty()) {
                Logger.debug("Found {} old Atlas containers to clean up", containers.size());

                for (com.github.dockerjava.api.model.Container container : containers) {
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
                        this.deleteDirectoryRecursively(serverDir);
                        cleanedCount++;
                        Logger.debug("Cleaned up orphaned dynamic server volume: {}", serverDir.getName());
                    } catch (Exception e) {
                        Logger.debug("Failed to cleanup volume directory {}: {}", serverDir.getName(), e.getMessage());
                    }
                }

                boolean hasRunningContainer = false;
                try {
                    List<com.github.dockerjava.api.model.Container> runningContainers = this.dockerClient.listContainersCmd()
                            .withLabelFilter(Map.of("atlas.managed", "true"))
                            .exec();

                    for (com.github.dockerjava.api.model.Container container : runningContainers) {
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
                        this.deleteDirectoryRecursively(serverDir);
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
}