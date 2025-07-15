package be.esmay.atlas.base.scaler.impl;

import be.esmay.atlas.base.config.impl.ScalerConfig;
import be.esmay.atlas.base.scaler.Scaler;
import be.esmay.atlas.base.utils.Logger;
import be.esmay.atlas.common.enums.ScaleType;
import be.esmay.atlas.common.enums.ServerStatus;
import be.esmay.atlas.common.models.AtlasServer;

import java.util.List;

public final class ProxyScaler extends Scaler {

    public ProxyScaler(String groupName, ScalerConfig scalerConfig) {
        super(groupName, scalerConfig);
    }

    @Override
    public ScaleType needsScaling() {
        if (this.shutdown) {
            return ScaleType.NONE;
        }

        if (this.getAutoScaledServers().size() < this.getMinServers()) {
            Logger.debug("Below minimum proxy servers for group: {}, scaling up", this.groupName);
            return ScaleType.UP;
        }

        if (this.shouldScaleUpProxy()) {
            return ScaleType.UP;
        }

        if (this.shouldScaleDownProxy()) {
            return ScaleType.DOWN;
        }

        return ScaleType.NONE;
    }

    private boolean shouldScaleUpProxy() {
        double utilization = this.getNetworkUtilization();
        double threshold = this.scalerConfig.getGroup().getScaling().getConditions().getScaleUpThreshold();
        int cooldownSeconds = this.getCooldownSeconds();

        int startingProxies = (int) this.getAutoScaledServers().stream()
                .filter(server -> server.getServerInfo() != null && server.getServerInfo().getStatus() == ServerStatus.STARTING)
                .count();

        boolean thresholdMet = utilization >= threshold;
        boolean canScale = this.canScaleUp();
        boolean cooldownExpired = java.time.Instant.now().isAfter(this.lastScaleUpTime.plusSeconds(cooldownSeconds));

        if (startingProxies > 0) {
            thresholdMet = utilization >= 0.9;

            if (thresholdMet) {
                Logger.debug("High network utilization ({}%) detected with {} starting proxies, allowing scale up", String.format("%.1f", utilization * 100), startingProxies);
            }
        }

        if (thresholdMet && canScale && !cooldownExpired) {
            Logger.debug("Proxy scale up conditions met for {} but in cooldown for {} more seconds", this.groupName, cooldownSeconds - java.time.Instant.now().getEpochSecond() + this.lastScaleUpTime.getEpochSecond());
        }

        return thresholdMet && canScale && cooldownExpired;
    }

    private boolean shouldScaleDownProxy() {
        double utilization = this.getNetworkUtilization();
        double threshold = this.scalerConfig.getGroup().getScaling().getConditions().getScaleDownThreshold();
        int cooldownSeconds = this.getCooldownSeconds();

        boolean thresholdMet = utilization <= threshold;
        boolean canScale = this.canScaleDown();
        boolean cooldownExpired = java.time.Instant.now().isAfter(this.lastScaleDownTime.plusSeconds(cooldownSeconds));

        if (thresholdMet && canScale && !cooldownExpired) {
            Logger.debug("Proxy scale down conditions met for {} but in cooldown for {} more seconds", this.groupName, cooldownSeconds - java.time.Instant.now().getEpochSecond() + this.lastScaleDownTime.getEpochSecond());
        }

        return thresholdMet && canScale && cooldownExpired;
    }

    private double getNetworkUtilization() {
        List<AtlasServer> autoScaledProxies = this.getAutoScaledServers();
        if (autoScaledProxies.isEmpty()) {
            return 0.0;
        }

        List<AtlasServer> runningProxies = autoScaledProxies.stream()
                .filter(server -> server.getServerInfo() != null && server.getServerInfo().getStatus() == ServerStatus.RUNNING)
                .toList();

        int avgProxyCapacity;
        if (!runningProxies.isEmpty()) {
            avgProxyCapacity = runningProxies.stream()
                    .mapToInt(server -> server.getServerInfo() != null ? server.getServerInfo().getMaxPlayers() : 0)
                    .sum() / runningProxies.size();
        } else {
            avgProxyCapacity = 100;
        }

        int totalNetworkPlayers = 0;
        int totalNetworkCapacity = 0;

        for (AtlasServer proxy : autoScaledProxies) {
            totalNetworkPlayers += proxy.getServerInfo() != null ? proxy.getServerInfo().getOnlinePlayers() : 0;

            if (proxy.getServerInfo() != null && proxy.getServerInfo().getStatus() == ServerStatus.RUNNING) {
                totalNetworkCapacity += proxy.getServerInfo().getMaxPlayers();
            } else if (proxy.getServerInfo() != null && proxy.getServerInfo().getStatus() == ServerStatus.STARTING) {
                totalNetworkCapacity += avgProxyCapacity;
            }
        }

        if (totalNetworkCapacity == 0) {
            return 0.0;
        }

        double utilization = (double) totalNetworkPlayers / totalNetworkCapacity;

        Logger.debug("Network utilization for proxy group {}: {}% (players: {}, capacity: {})", this.groupName, String.format("%.1f", utilization * 100), totalNetworkPlayers, totalNetworkCapacity);

        return utilization;
    }
}