package be.esmay.atlas.base.commands.impl;

import be.esmay.atlas.base.AtlasBase;
import be.esmay.atlas.base.commands.AtlasCommand;
import be.esmay.atlas.base.config.impl.ScalerConfig;
import be.esmay.atlas.base.scaler.Scaler;
import be.esmay.atlas.base.scaler.ScalerManager;
import be.esmay.atlas.base.utils.Logger;
import be.esmay.atlas.common.enums.ServerStatus;
import be.esmay.atlas.common.models.ServerInfo;

import java.util.Arrays;
import java.util.List;

public final class MetricsCommand implements AtlasCommand {

    @Override
    public String getName() {
        return "metrics";
    }

    @Override
    public List<String> getAliases() {
        return List.of("stats", "status");
    }

    @Override
    public String getDescription() {
        return "Display system-wide metrics and utilization statistics.";
    }

    @Override
    public void execute(String[] args) {
        if (args.length == 0) {
            this.handleSystemMetrics();
        } else if (args[0].equalsIgnoreCase("help")) {
            this.showHelp();
        } else {
            String groupName = String.join(" ", args);
            this.handleGroupMetrics(groupName);
        }
    }

    @Override
    public String getUsage() {
        return "metrics [group]";
    }

    private void showHelp() {
        Logger.info("Usage: metrics [group]");
        Logger.info("");
        Logger.info("Arguments:");
        Logger.info("  [group]    Optional group name to show specific group metrics");
        Logger.info("");
        Logger.info("Examples:");
        Logger.info("  metrics         Show system-wide metrics");
        Logger.info("  metrics lobby   Show metrics for the lobby group");
    }

    private void handleSystemMetrics() {
        ScalerManager scalerManager = AtlasBase.getInstance().getScalerManager();
        
        if (scalerManager.getScalers().isEmpty()) {
            Logger.info("No server groups configured.");
            return;
        }

        Logger.info("=== Atlas System Metrics ===");
        Logger.info("");

        int totalGroups = scalerManager.getScalers().size();
        int totalServers = 0;
        int totalAutoServers = 0;
        int totalManualServers = 0;
        int totalPlayers = 0;
        int runningServers = 0;
        int startingServers = 0;
        int pausedGroups = 0;
        double totalUtilization = 0.0;

        for (Scaler scaler : scalerManager.getScalers()) {
            List<ServerInfo> autoServers = scaler.getAutoScaledServers();
            List<ServerInfo> manualServers = scaler.getManuallyScaledServers();
            
            totalServers += scaler.getServers().size();
            totalAutoServers += autoServers.size();
            totalManualServers += manualServers.size();
            totalPlayers += scaler.getTotalOnlinePlayers();
            totalUtilization += scaler.getCurrentUtilization();
            
            if (scaler.isPaused()) {
                pausedGroups++;
            }

            for (ServerInfo server : scaler.getServers()) {
                if (server.getStatus() == ServerStatus.RUNNING) {
                    runningServers++;
                } else if (server.getStatus() == ServerStatus.STARTING) {
                    startingServers++;
                }
            }
        }

        double avgUtilization = totalGroups > 0 ? totalUtilization / totalGroups : 0.0;

        Logger.info("Overall Statistics:");
        Logger.info("  Total Groups: " + totalGroups);
        Logger.info("  Total Servers: " + totalServers);
        Logger.info("    - Auto-scaled: " + totalAutoServers);
        Logger.info("    - Manual: " + totalManualServers);
        Logger.info("    - Running: " + runningServers);
        Logger.info("    - Starting: " + startingServers);
        Logger.info("  Total Players: " + totalPlayers);
        Logger.info("  Average Utilization: " + String.format("%.1f%%", avgUtilization * 100));
        Logger.info("  Paused Groups: " + pausedGroups + "/" + totalGroups);
        Logger.info("");

        Logger.info("Group Summary:");
        String format = "  %-15s %-10s %-8s %-8s %-10s %-8s";
        Logger.info(String.format(format, "Group", "Utilization", "Servers", "Players", "Status", "Paused"));
        Logger.info("  " + "-".repeat(70));

        for (Scaler scaler : scalerManager.getScalers()) {
            double utilization = scaler.getCurrentUtilization();
            int servers = scaler.getServers().size();
            int players = scaler.getTotalOnlinePlayers();
            String status = this.getGroupStatus(scaler);
            String paused = scaler.isPaused() ? "Yes" : "No";
            
            Logger.info(String.format(format,
                scaler.getGroupName(),
                String.format("%.1f%%", utilization * 100),
                servers,
                players,
                status,
                paused
            ));
        }

        Logger.info("");
        this.showCapacityAnalysis(scalerManager);
    }

    private void handleGroupMetrics(String groupName) {
        Scaler scaler = AtlasBase.getInstance().getScalerManager().getScaler(groupName);
        
        if (scaler == null) {
            Logger.error("Group not found: " + groupName);
            return;
        }

        Logger.info("=== Metrics for Group: " + groupName + " ===");
        Logger.info("");

        double utilization = scaler.getCurrentUtilization();
        int totalPlayers = scaler.getTotalOnlinePlayers();
        List<ServerInfo> autoServers = scaler.getAutoScaledServers();
        List<ServerInfo> manualServers = scaler.getManuallyScaledServers();
        
        Logger.info("Group Statistics:");
        Logger.info("  Current Utilization: " + String.format("%.1f%%", utilization * 100));
        Logger.info("  Total Players: " + totalPlayers);
        Logger.info("  Total Servers: " + scaler.getServers().size());
        Logger.info("    - Auto-scaled: " + autoServers.size());
        Logger.info("    - Manual: " + manualServers.size());
        Logger.info("  Scaling Status: " + (scaler.isPaused() ? "PAUSED" : "ACTIVE"));
        Logger.info("");

        ScalerConfig.Conditions conditions = scaler.getScalerConfig().getGroup().getScaling().getConditions();
        Logger.info("Configuration:");
        Logger.info("  Scale Up Threshold: " + String.format("%.1f%%", conditions.getScaleUpThreshold() * 100));
        Logger.info("  Scale Down Threshold: " + String.format("%.1f%%", conditions.getScaleDownThreshold() * 100));
        Logger.info("  Min Servers: " + scaler.getScalerConfig().getGroup().getServer().getMinServers());
        int maxServers = scaler.getScalerConfig().getGroup().getServer().getMaxServers();
        Logger.info("  Max Servers: " + (maxServers == -1 ? "Unlimited" : maxServers));
        Logger.info("");

        this.showServerStatusBreakdown(scaler);
        this.showGroupCapacityAnalysis(scaler);
    }

    private void showServerStatusBreakdown(Scaler scaler) {
        Logger.info("Server Status Breakdown:");
        
        int running = 0, starting = 0, stopping = 0, stopped = 0, error = 0;
        int totalCapacity = 0;
        int usedCapacity = 0;
        
        for (ServerInfo server : scaler.getServers()) {
            switch (server.getStatus()) {
                case RUNNING -> running++;
                case STARTING -> starting++;
                case STOPPING -> stopping++;
                case STOPPED -> stopped++;
                case ERROR -> error++;
            }
            
            totalCapacity += server.getMaxPlayers();
            usedCapacity += server.getOnlinePlayers();
        }
        
        Logger.info("  Running: " + running);
        Logger.info("  Starting: " + starting);
        Logger.info("  Stopping: " + stopping);
        Logger.info("  Stopped: " + stopped);
        if (error > 0) {
            Logger.info("  Error: " + error);
        }
        Logger.info("");
        
        Logger.info("Capacity:");
        Logger.info("  Total Slots: " + totalCapacity);
        Logger.info("  Used Slots: " + usedCapacity);
        Logger.info("  Available Slots: " + (totalCapacity - usedCapacity));
        if (totalCapacity > 0) {
            Logger.info("  Utilization: " + String.format("%.1f%%", (double) usedCapacity / totalCapacity * 100));
        }

        Logger.info("");
    }

    private void showCapacityAnalysis(ScalerManager scalerManager) {
        Logger.info("Capacity Analysis:");
        
        int totalCapacity = 0;
        int usedCapacity = 0;
        int availableCapacity = 0;
        
        for (Scaler scaler : scalerManager.getScalers()) {
            for (ServerInfo server : scaler.getServers()) {
                totalCapacity += server.getMaxPlayers();
                usedCapacity += server.getOnlinePlayers();
            }
        }
        
        availableCapacity = totalCapacity - usedCapacity;
        
        Logger.info("  Total Capacity: " + totalCapacity + " player slots");
        Logger.info("  Used Capacity: " + usedCapacity + " players");
        Logger.info("  Available Capacity: " + availableCapacity + " slots");
        
        if (totalCapacity > 0) {
            double systemUtilization = (double) usedCapacity / totalCapacity * 100;
            Logger.info("  System Utilization: " + String.format("%.1f%%", systemUtilization));
            
            if (systemUtilization > 80) {
                Logger.info("  ⚠ High system utilization detected!");
            } else if (systemUtilization < 20) {
                Logger.info("  ℹ Low system utilization - consider reviewing scaling settings");
            }
        }
    }

    private void showGroupCapacityAnalysis(Scaler scaler) {
        Logger.info("Capacity Recommendations:");
        
        double utilization = scaler.getCurrentUtilization();
        ScalerConfig.Conditions conditions = scaler.getScalerConfig().getGroup().getScaling().getConditions();
        
        if (scaler.isPaused()) {
            Logger.info("  ⏸ Scaling is paused - no automatic adjustments will occur");
        } else if (utilization >= conditions.getScaleUpThreshold()) {
            Logger.info("  ↗ Utilization above scale-up threshold - more servers may be created");
        } else if (utilization <= conditions.getScaleDownThreshold()) {
            Logger.info("  ↘ Utilization below scale-down threshold - servers may be removed");
        } else {
            Logger.info("  ✓ Utilization within optimal range");
        }
        
        int currentServers = scaler.getAutoScaledServers().size();
        int minServers = scaler.getScalerConfig().getGroup().getServer().getMinServers();
        int maxServers = scaler.getScalerConfig().getGroup().getServer().getMaxServers();
        
        if (currentServers <= minServers) {
            Logger.info("  ℹ At minimum server count");
        } else if (maxServers != -1 && currentServers >= maxServers) {
            Logger.info("  ⚠ At maximum server count - cannot scale up further");
        }
    }

    private String getGroupStatus(Scaler scaler) {
        if (scaler.isPaused()) {
            return "PAUSED";
        }
        
        double utilization = scaler.getCurrentUtilization();
        ScalerConfig.Conditions conditions = scaler.getScalerConfig().getGroup().getScaling().getConditions();
        int currentAutoServers = scaler.getAutoScaledServers().size();
        int minServers = scaler.getScalerConfig().getGroup().getServer().getMinServers();
        int maxServers = scaler.getScalerConfig().getGroup().getServer().getMaxServers();
        
        if (utilization >= conditions.getScaleUpThreshold()) {
            if (maxServers == -1 || currentAutoServers < maxServers) {
                return "SCALING";
            } else {
                return "AT_MAX";
            }
        } else if (utilization <= conditions.getScaleDownThreshold()) {
            if (currentAutoServers > minServers) {
                return "REDUCING";
            } else {
                return "AT_MIN";
            }
        } else {
            return "STABLE";
        }
    }
}