package be.esmay.atlas.base.network.connection;

import be.esmay.atlas.base.AtlasBase;
import be.esmay.atlas.base.lifecycle.ServerLifecycleService;
import be.esmay.atlas.base.provider.DeletionOptions;
import be.esmay.atlas.base.utils.Logger;
import be.esmay.atlas.common.enums.ServerType;
import be.esmay.atlas.common.models.AtlasServer;
import be.esmay.atlas.common.network.packet.Packet;
import io.netty.channel.Channel;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class ConnectionManager {

    private final Map<Channel, Connection> connections;
    private final Map<String, Connection> serverConnections;
    private final ScheduledExecutorService scheduler;
    private final int connectionTimeout;

    public ConnectionManager(int connectionTimeout) {
        this.connections = new ConcurrentHashMap<>();
        this.serverConnections = new ConcurrentHashMap<>();
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.connectionTimeout = connectionTimeout;

        this.startHeartbeatCheck();
    }

    public void addConnection(Channel channel) {
        Connection connection = new Connection(channel);
        this.connections.put(channel, connection);

        Logger.debug("New connection from {}", connection.getRemoteAddress());
    }

    public void removeConnection(Channel channel) {
        Connection connection = this.connections.remove(channel);
        if (connection == null) {
            return;
        }

        if (connection.getServerId() != null) {
            this.serverConnections.remove(connection.getServerId());
            this.handleServerDisconnection(connection.getServerId());
        }

        Logger.debug("Connection from {} disconnected", connection.getRemoteAddress());
    }

    public Connection getConnection(Channel channel) {
        return this.connections.get(channel);
    }

    public Connection getServerConnection(String serverId) {
        return this.serverConnections.get(serverId);
    }

    public void registerServer(Connection connection, String serverId) {
        connection.setServerId(serverId);
        this.serverConnections.put(serverId, connection);
    }

    public void broadcastPacket(Packet packet) {
        for (Connection connection : this.connections.values()) {
            if (connection.isAuthenticated() && connection.isActive()) {
                connection.sendPacket(packet);
            }
        }
    }

    public void sendToServer(String serverId, Packet packet) {
        Connection connection = this.serverConnections.get(serverId);
        if (connection != null && connection.isActive()) {
            connection.sendPacket(packet);
        }
    }

    public Set<String> getConnectedServers() {
        return this.serverConnections.keySet();
    }

    public int getConnectionCount() {
        return this.connections.size();
    }

    public int getAuthenticatedConnectionCount() {
        return (int) this.connections.values().stream()
                .filter(Connection::isAuthenticated)
                .count();
    }

    public void shutdown() {
        this.scheduler.shutdown();

        for (Connection connection : this.connections.values()) {
            connection.disconnect();
        }

        this.connections.clear();
        this.serverConnections.clear();
    }

    private void startHeartbeatCheck() {
        this.scheduler.scheduleAtFixedRate(this::checkHeartbeats, 10, 10, TimeUnit.SECONDS);
    }

    private void checkHeartbeats() {
        long currentTime = System.currentTimeMillis();
        long timeoutMillis = this.connectionTimeout * 1000L;

        for (Connection connection : this.connections.values()) {
            if (connection.isAuthenticated() &&
                    (currentTime - connection.getLastHeartbeat()) > timeoutMillis) {

                Logger.warn("Connection from {} timed out (no heartbeat for {}s)", connection.getRemoteAddress(), (currentTime - connection.getLastHeartbeat()) / 1000);

                connection.disconnect();
            }
        }
    }

    private void handleServerDisconnection(String serverId) {
        Logger.debug("Server {} disconnected from Netty, marking as offline", serverId);

        AtlasBase atlasInstance = AtlasBase.getInstance();
        if (atlasInstance == null) {
            return;
        }

        AtlasServer server = this.findServerById(serverId);
        if (server == null) {
            return;
        }

        if (server.isShutdown()) {
            Logger.debug("Server {} is already being shutdown, skipping Netty disconnection handling", serverId);
            return;
        }

        ServerLifecycleService lifecycleService = new ServerLifecycleService(atlasInstance);

        if (server.getType() == ServerType.DYNAMIC) {
            lifecycleService.removeServer(server, DeletionOptions.connectionLost());
        } else {
            lifecycleService.stopServer(server);
        }
    }

    private AtlasServer findServerById(String serverId) {
        AtlasBase atlasInstance = AtlasBase.getInstance();
        if (atlasInstance == null || atlasInstance.getScalerManager() == null) {
            return null;
        }

        return atlasInstance.getScalerManager().getScalers().stream()
                .flatMap(scaler -> scaler.getServers().stream())
                .filter(server -> server.getServerId().equals(serverId))
                .findFirst()
                .orElse(null);
    }

}