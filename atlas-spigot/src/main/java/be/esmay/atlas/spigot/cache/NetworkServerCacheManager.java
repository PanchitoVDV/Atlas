package be.esmay.atlas.spigot.cache;

import be.esmay.atlas.common.enums.ServerStatus;
import be.esmay.atlas.common.models.AtlasServer;
import be.esmay.atlas.spigot.AtlasSpigotPlugin;
import be.esmay.atlas.spigot.events.AtlasServerUpdateEvent;
import lombok.Getter;
import org.bukkit.Bukkit;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Getter
public final class NetworkServerCacheManager {

    private final Map<String, AtlasServer> serverCache = new ConcurrentHashMap<>();

    public void updateAtlasServer(AtlasServer atlasServer) {
        AtlasServer previousInfo = this.serverCache.put(atlasServer.getServerId(), atlasServer);

        AtlasServerUpdateEvent event = new AtlasServerUpdateEvent(atlasServer, previousInfo);
        Bukkit.getScheduler().runTask(AtlasSpigotPlugin.getInstance(), () -> Bukkit.getPluginManager().callEvent(event));
    }

    public void removeServer(String serverId) {
        AtlasServer removedInfo = this.serverCache.remove(serverId);

        if (removedInfo == null) return;

        AtlasServerUpdateEvent event = new AtlasServerUpdateEvent(null, removedInfo);
        Bukkit.getScheduler().runTask(AtlasSpigotPlugin.getInstance(), () -> Bukkit.getPluginManager().callEvent(event));
    }

    public Optional<AtlasServer> getServer(String serverId) {
        return Optional.ofNullable(this.serverCache.get(serverId));
    }

    public Collection<AtlasServer> getAllServers() {
        return this.serverCache.values();
    }

    public List<AtlasServer> getServersByGroup(String group) {
        return this.serverCache.values().stream()
                .filter(server -> server.getGroup().equals(group))
                .collect(Collectors.toList());
    }

    public List<AtlasServer> getOnlineServers() {
        return this.serverCache.values().stream()
                .filter(server -> server.getServerInfo() != null && server.getServerInfo().getStatus() == ServerStatus.RUNNING)
                .collect(Collectors.toList());
    }

    public List<AtlasServer> getBackendServers() {
        return this.serverCache.values().stream()
                .filter(server -> !server.getGroup().equals("proxy"))
                .collect(Collectors.toList());
    }

    public List<AtlasServer> getProxyServers() {
        return this.serverCache.values().stream()
                .filter(server -> server.getGroup().equals("proxy"))
                .collect(Collectors.toList());
    }

    public int getTotalPlayers() {
        return this.serverCache.values().stream()
                .mapToInt(server -> server.getServerInfo() != null ? server.getServerInfo().getOnlinePlayers() : 0)
                .sum();
    }

    public int getTotalBackendPlayers() {
        return this.getBackendServers().stream()
                .mapToInt(server -> server.getServerInfo() != null ? server.getServerInfo().getOnlinePlayers() : 0)
                .sum();
    }

    public int getTotalProxyPlayers() {
        return this.getProxyServers().stream()
                .mapToInt(server -> server.getServerInfo() != null ? server.getServerInfo().getOnlinePlayers() : 0)
                .sum();
    }

    public void clear() {
        this.serverCache.clear();
    }

    public int getServerCount() {
        return this.serverCache.size();
    }

    public boolean hasServer(String serverId) {
        return this.serverCache.containsKey(serverId);
    }
}