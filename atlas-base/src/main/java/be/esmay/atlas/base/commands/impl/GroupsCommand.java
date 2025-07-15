package be.esmay.atlas.base.commands.impl;

import be.esmay.atlas.base.AtlasBase;
import be.esmay.atlas.base.commands.AtlasCommand;
import be.esmay.atlas.base.config.impl.ScalerConfig;
import be.esmay.atlas.base.provider.StartOptions;
import be.esmay.atlas.base.scaler.Scaler;
import be.esmay.atlas.base.scaler.ScalerManager;
import be.esmay.atlas.base.utils.Logger;
import be.esmay.atlas.common.enums.ServerStatus;
import be.esmay.atlas.common.models.AtlasServer;

import java.util.Arrays;
import java.util.List;

public final class GroupsCommand implements AtlasCommand {

    @Override
    public String getName() {
        return "groups";
    }

    @Override
    public List<String> getAliases() {
        return List.of("group", "grp");
    }

    @Override
    public String getDescription() {
        return "Manage and view information about server groups and their scaling status.";
    }

    @Override
    public void execute(String[] args) {
        if (args.length == 0) {
            this.showHelp();
            return;
        }

        String subcommand = args[0].toLowerCase();
        switch (subcommand) {
            case "list" -> this.handleList();
            case "status" -> this.handleStatus(args);
            case "reload" -> this.handleReload();
            case "start" -> this.handleStart(args);
            case "stop" -> this.handleStop(args);
            case "restart" -> this.handleRestart(args);
            case "help" -> this.showHelp();
            default -> {
                Logger.error("Unknown subcommand: " + subcommand);
                this.showHelp();
            }
        }
    }

    @Override
    public String getUsage() {
        return "groups <subcommand> [args]";
    }

    private void showHelp() {
        Logger.info("Usage: groups <subcommand> [args]");
        Logger.info("");
        Logger.info("Subcommands:");
        Logger.info("  list              Show all server groups with utilization");
        Logger.info("  status <group>    Show detailed information for a specific group");
        Logger.info("  reload            Reload all group configurations from disk");
        Logger.info("  start <group>     Start all stopped servers in a group");
        Logger.info("  stop <group>      Stop all running servers in a group");
        Logger.info("  restart <group>   Restart all servers in a group");
        Logger.info("");
        Logger.info("Examples:");
        Logger.info("  groups list");
        Logger.info("  groups status lobby");
        Logger.info("  groups reload");
        Logger.info("  groups start lobby");
        Logger.info("  groups stop lobby");
        Logger.info("  groups restart lobby");
    }

    private void handleList() {
        ScalerManager scalerManager = AtlasBase.getInstance().getScalerManager();

        if (scalerManager.getScalers().isEmpty()) {
            Logger.info("No server groups configured.");
            return;
        }

        Logger.info("Server Groups (" + scalerManager.getScalers().size() + " total):");
        Logger.info("");

        String format = "%-15s %-12s %-8s %-8s %-10s %-8s";
        Logger.info(String.format(format, "Group", "Utilization", "Auto", "Manual", "Players", "Status"));
        Logger.info("-".repeat(75));

        for (Scaler scaler : scalerManager.getScalers()) {
            double utilization = scaler.getCurrentUtilization();
            int autoServers = scaler.getAutoScaledServers().size();
            int manualServers = scaler.getManuallyScaledServers().size();
            int totalPlayers = scaler.getTotalOnlinePlayers();

            String status = this.getGroupStatus(scaler);
            String utilizationStr = String.format("%.1f%%", utilization * 100);

            Logger.info(String.format(format,
                    scaler.getGroupName(),
                    utilizationStr,
                    autoServers,
                    manualServers,
                    totalPlayers,
                    status
            ));
        }
    }

    private void handleStatus(String[] args) {
        if (args.length < 2) {
            Logger.error("Usage: groups status <group>");
            return;
        }

        String groupName = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        Scaler scaler = AtlasBase.getInstance().getScalerManager().getScaler(groupName);

        if (scaler == null) {
            Logger.error("Group not found: " + groupName);
            Logger.info("Available groups:");
            for (Scaler availableScaler : AtlasBase.getInstance().getScalerManager().getScalers()) {
                Logger.info("  - " + availableScaler.getGroupName());
            }
            return;
        }

        Logger.info("Group Status: " + groupName);
        Logger.info("");
        Logger.info("  Name: " + scaler.getGroupName());
        Logger.info("  Current Utilization: " + String.format("%.1f%%", scaler.getCurrentUtilization() * 100));
        Logger.info("  Total Players: " + scaler.getTotalOnlinePlayers());
        Logger.info("");

        List<AtlasServer> autoServers = scaler.getAutoScaledServers();
        List<AtlasServer> manualServers = scaler.getManuallyScaledServers();

        Logger.info("Server Counts:");
        Logger.info("  Auto-scaled: " + autoServers.size());
        Logger.info("  Manual: " + manualServers.size());
        Logger.info("  Total: " + scaler.getServers().size());
        Logger.info("");

        ScalerConfig.Group groupConfig = scaler.getScalerConfig().getGroup();
        ScalerConfig.Scaling scaling = groupConfig.getScaling();
        ScalerConfig.Conditions conditions = scaling.getConditions();

        Logger.info("Configuration:");
        Logger.info("  Min Servers: " + groupConfig.getServer().getMinServers());
        Logger.info("  Max Servers: " + (groupConfig.getServer().getMaxServers() == -1 ? "Unlimited" : groupConfig.getServer().getMaxServers()));
        Logger.info("  Scale Up Threshold: " + String.format("%.1f%%", conditions.getScaleUpThreshold() * 100));
        Logger.info("  Scale Down Threshold: " + String.format("%.1f%%", conditions.getScaleDownThreshold() * 100));
        int cooldown = AtlasBase.getInstance().getConfigManager().getAtlasConfig().getAtlas().getScaling().getCooldown();
        Logger.info("  Cooldown: " + cooldown + " seconds");
        Logger.info("");

        Logger.info("Scaling Status:");
        Logger.info("  " + scaler.getScalingStatus());

        if (!scaler.getServers().isEmpty()) {
            Logger.info("");
            Logger.info("Servers:");

            String serverFormat = "  %-12s %-10s %-8s %-8s %s";
            Logger.info(String.format(serverFormat, "Name", "Status", "Players", "Manual", "Address"));
            Logger.info("  " + "-".repeat(55));

            for (AtlasServer server : scaler.getServers()) {
                String players = (server.getServerInfo() != null ? server.getServerInfo().getOnlinePlayers() : 0) + "/" + (server.getServerInfo() != null ? server.getServerInfo().getMaxPlayers() : 0);
                String manual = server.isManuallyScaled() ? "Yes" : "No";
                String address = server.getAddress() + ":" + server.getPort();

                Logger.info(String.format(serverFormat,
                        server.getName(),
                        server.getServerInfo() != null ? server.getServerInfo().getStatus() : "UNKNOWN",
                        players,
                        manual,
                        address
                ));
            }
        }
    }

    private void handleReload() {
        Logger.info("Reloading server group configurations...");

        try {
            AtlasBase.getInstance().getScalerManager().reloadScalers();
            Logger.info("Server group configurations reloaded successfully.");
        } catch (Exception e) {
            Logger.error("Failed to reload group configurations: " + e.getMessage());
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

    private void handleStart(String[] args) {
        if (args.length < 2) {
            Logger.error("Usage: groups start <group>");
            return;
        }

        String groupName = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        Scaler scaler = AtlasBase.getInstance().getScalerManager().getScaler(groupName);

        if (scaler == null) {
            Logger.error("Group not found: " + groupName);
            return;
        }

        Logger.info("Starting all stopped servers in group: " + groupName);

        AtlasBase.getInstance().runAsync(() -> {
            int startedCount = 0;
            for (AtlasServer server : scaler.getServers()) {
                if (server.getServerInfo() != null && server.getServerInfo().getStatus() == ServerStatus.STOPPED) {
                    try {
                        AtlasBase.getInstance().getProviderManager().getProvider().startServerCompletely(server, StartOptions.userCommand()).get();
                        startedCount++;
                        Logger.info("Started server: " + server.getName());
                    } catch (Exception e) {
                        Logger.error("Failed to start server {}: {}", server.getName(), e.getMessage());
                    }
                }
            }
            Logger.info("Started {} servers in group: {}", startedCount, groupName);
        });
    }

    private void handleStop(String[] args) {
        if (args.length < 2) {
            Logger.error("Usage: groups stop <group>");
            return;
        }

        String groupName = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        Scaler scaler = AtlasBase.getInstance().getScalerManager().getScaler(groupName);

        if (scaler == null) {
            Logger.error("Group not found: " + groupName);
            return;
        }

        Logger.info("Stopping all running servers in group: " + groupName);

        AtlasBase.getInstance().runAsync(() -> {
            int stoppedCount = 0;
            for (AtlasServer server : scaler.getServers()) {
                if (server.getServerInfo() != null && server.getServerInfo().getStatus() == ServerStatus.RUNNING) {
                    try {
                        AtlasBase.getInstance().getServerManager().stopServer(server).get();
                        stoppedCount++;
                        Logger.info("Stopped server: " + server.getName());
                    } catch (Exception e) {
                        Logger.error("Failed to stop server {}: {}", server.getName(), e.getMessage());
                    }
                }
            }
            Logger.info("Stopped {} servers in group: {}", stoppedCount, groupName);
        });
    }

    private void handleRestart(String[] args) {
        if (args.length < 2) {
            Logger.error("Usage: groups restart <group>");
            return;
        }

        String groupName = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        Scaler scaler = AtlasBase.getInstance().getScalerManager().getScaler(groupName);

        if (scaler == null) {
            Logger.error("Group not found: " + groupName);
            return;
        }

        Logger.info("Restarting all servers in group: " + groupName);

        AtlasBase.getInstance().runAsync(() -> {
            int restartedCount = 0;
            for (AtlasServer server : scaler.getServers()) {
                if (server.getServerInfo() != null && server.getServerInfo().getStatus() == ServerStatus.RUNNING) {
                    try {
                        AtlasBase.getInstance().getServerManager().stopServer(server).get();

                        Thread.sleep(1000);
                        AtlasBase.getInstance().getProviderManager().getProvider().startServerCompletely(server, StartOptions.restart()).get();
                        restartedCount++;
                        Logger.info("Restarted server: " + server.getName());
                    } catch (Exception e) {
                        Logger.error("Failed to restart server {}: {}", server.getName(), e.getMessage());
                    }
                }
            }
            Logger.info("Restarted {} servers in group: {}", restartedCount, groupName);
        });
    }
}