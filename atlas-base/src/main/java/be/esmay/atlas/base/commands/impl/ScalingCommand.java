package be.esmay.atlas.base.commands.impl;

import be.esmay.atlas.base.AtlasBase;
import be.esmay.atlas.base.commands.AtlasCommand;
import be.esmay.atlas.base.config.impl.ScalerConfig;
import be.esmay.atlas.base.scaler.Scaler;
import be.esmay.atlas.base.scaler.ScalerManager;
import be.esmay.atlas.base.utils.Logger;

import java.util.Arrays;
import java.util.List;

public final class ScalingCommand implements AtlasCommand {

    @Override
    public String getName() {
        return "scaling";
    }

    @Override
    public List<String> getAliases() {
        return List.of("scale", "scaler");
    }

    @Override
    public String getDescription() {
        return "Control automatic scaling behavior for server groups.";
    }

    @Override
    public void execute(String[] args) {
        if (args.length == 0) {
            this.showHelp();
            return;
        }

        String subcommand = args[0].toLowerCase();
        switch (subcommand) {
            case "status" -> this.handleStatus();
            case "pause" -> this.handleTogglePause(args);
            case "trigger" -> this.handleTrigger(args);
            case "set" -> this.handleSet(args);
            case "get" -> this.handleGet(args);
            case "help" -> this.showHelp();
            default -> {
                Logger.error("Unknown subcommand: " + subcommand);
                this.showHelp();
            }
        }
    }

    @Override
    public String getUsage() {
        return "scaling <subcommand> [args]";
    }

    private void showHelp() {
        Logger.info("Usage: scaling <subcommand> [args]");
        Logger.info("");
        Logger.info("Subcommands:");
        Logger.info("  status               Show scaling status for all groups");
        Logger.info("  pause <group>        Toggle auto-scaling for a group (pause/resume)");
        Logger.info("  trigger <group> <up|down>  Force immediate scaling action");
        Logger.info("  set <group> <property> <value>  Modify scaling configuration");
        Logger.info("  get <group>          Display current scaling configuration");
        Logger.info("");
        Logger.info("Properties for 'set' command:");
        Logger.info("  min-servers          Minimum number of servers");
        Logger.info("  max-servers          Maximum number of servers (-1 for unlimited)");
        Logger.info("  scale-up-threshold   Utilization threshold to scale up (0.0-1.0)");
        Logger.info("  scale-down-threshold Utilization threshold to scale down (0.0-1.0)");
        Logger.info("");
        Logger.info("Examples:");
        Logger.info("  scaling status");
        Logger.info("  scaling pause lobby");
        Logger.info("  scaling trigger lobby up");
        Logger.info("  scaling set lobby min-servers 2");
        Logger.info("  scaling set lobby max-servers 10");
        Logger.info("  scaling set lobby scale-up-threshold 0.8");
        Logger.info("  scaling get lobby");
    }

    private void handleStatus() {
        ScalerManager scalerManager = AtlasBase.getInstance().getScalerManager();

        if (scalerManager.getScalers().isEmpty()) {
            Logger.info("No server groups configured.");
            return;
        }

        Logger.info("Scaling Status for All Groups:");
        Logger.info("");

        for (Scaler scaler : scalerManager.getScalers()) {
            String pausedStatus = scaler.isPaused() ? " [PAUSED]" : "";
            Logger.info("Group: " + scaler.getGroupName() + pausedStatus);
            Logger.info("  " + scaler.getScalingStatus());

            double utilization = scaler.getCurrentUtilization();
            ScalerConfig.Conditions conditions = scaler.getScalerConfig().getGroup().getScaling().getConditions();
            int currentAutoServers = scaler.getAutoScaledServers().size();
            int minServers = scaler.getScalerConfig().getGroup().getServer().getMinServers();
            int maxServers = scaler.getScalerConfig().getGroup().getServer().getMaxServers();

            String statusDetail;
            if (scaler.isPaused()) {
                statusDetail = "Paused - No automatic scaling";
            } else if (utilization >= conditions.getScaleUpThreshold()) {
                if (maxServers == -1 || currentAutoServers < maxServers) {
                    statusDetail = "Ready to scale UP (utilization above " + String.format("%.1f%%", conditions.getScaleUpThreshold() * 100) + " threshold)";
                } else {
                    statusDetail = "At maximum servers (" + maxServers + ") - cannot scale up";
                }
            } else if (utilization <= conditions.getScaleDownThreshold()) {
                if (currentAutoServers > minServers) {
                    statusDetail = "Ready to scale DOWN (utilization below " + String.format("%.1f%%", conditions.getScaleDownThreshold() * 100) + " threshold)";
                } else {
                    statusDetail = "At minimum servers (" + minServers + ") - cannot scale down";
                }
            } else {
                statusDetail = "Stable (utilization within normal range)";
            }

            Logger.info("  Status: " + statusDetail);
            Logger.info("");
        }
    }

    private void handleTogglePause(String[] args) {
        if (args.length < 2) {
            Logger.error("Usage: scaling pause <group>");
            return;
        }

        String groupName = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        Scaler scaler = AtlasBase.getInstance().getScalerManager().getScaler(groupName);

        if (scaler == null) {
            Logger.error("Group not found: " + groupName);
            return;
        }

        if (scaler.isPaused()) {
            scaler.resumeScaling();
            Logger.info("Auto-scaling has been RESUMED for group: " + groupName);
        } else {
            scaler.pauseScaling();
            Logger.info("Auto-scaling has been PAUSED for group: " + groupName);
            Logger.info("Manual scaling operations will still work.");
            Logger.info("Run the same command again to resume scaling.");
        }
    }

    private void handleTrigger(String[] args) {
        if (args.length < 3) {
            Logger.error("Usage: scaling trigger <group> <up|down>");
            return;
        }

        String direction = args[args.length - 1].toLowerCase();
        String groupName = String.join(" ", Arrays.copyOfRange(args, 1, args.length - 1));

        if (!direction.equals("up") && !direction.equals("down")) {
            Logger.error("Direction must be 'up' or 'down'");
            return;
        }

        Scaler scaler = AtlasBase.getInstance().getScalerManager().getScaler(groupName);

        if (scaler == null) {
            Logger.error("Group not found: " + groupName);
            return;
        }

        Logger.info("Triggering manual scaling " + direction + " for group: " + groupName);

        AtlasBase.getInstance().runAsync(() -> {
            try {
                if (direction.equals("up")) {
                    scaler.triggerScaleUp().get();
                    Logger.info("Manual scale up completed for group: " + groupName);
                } else {
                    scaler.triggerScaleDown().get();
                    Logger.info("Manual scale down completed for group: " + groupName);
                }
            } catch (Exception e) {
                Logger.error("Failed to trigger scaling " + direction + " for group " + groupName + ": " + e.getMessage());
            }
        });
    }

    private void handleSet(String[] args) {
        if (args.length < 4) {
            Logger.error("Usage: scaling set <group> <property> <value>");
            return;
        }

        String groupName = args[1];
        String property = args[2];
        String value = args[3];

        Scaler scaler = AtlasBase.getInstance().getScalerManager().getScaler(groupName);
        if (scaler == null) {
            Logger.error("Group not found: " + groupName);
            return;
        }

        switch (property) {
            case "min-servers" -> {
                try {
                    int minServers = Integer.parseInt(value);
                    if (minServers < 0) {
                        Logger.error("Minimum servers must be 0 or greater");
                        return;
                    }
                    scaler.setMinServers(minServers);
                    scaler.getScalerConfig().updateAndSave();
                    Logger.info("Set min-servers to {} for group {}", minServers, groupName);
                } catch (NumberFormatException e) {
                    Logger.error("Invalid number: " + value);
                } catch (IllegalArgumentException e) {
                    Logger.error("Failed to set min-servers: " + e.getMessage());
                }
            }
            case "max-servers" -> {
                try {
                    int maxServers = Integer.parseInt(value);
                    if (maxServers < -1) {
                        Logger.error("Maximum servers must be -1 (unlimited) or greater");
                        return;
                    }
                    scaler.setMaxServers(maxServers);
                    scaler.getScalerConfig().updateAndSave();
                    Logger.info("Set max-servers to {} for group {}", maxServers == -1 ? "unlimited" : maxServers, groupName);
                } catch (NumberFormatException e) {
                    Logger.error("Invalid number: " + value);
                } catch (IllegalArgumentException e) {
                    Logger.error("Failed to set max-servers: " + e.getMessage());
                }
            }
            case "scale-up-threshold" -> {
                try {
                    double threshold = Double.parseDouble(value);
                    if (threshold < 0.0 || threshold > 1.0) {
                        Logger.error("Scale-up threshold must be between 0.0 and 1.0");
                        return;
                    }
                    scaler.setScaleUpThreshold(threshold);
                    scaler.getScalerConfig().updateAndSave();
                    Logger.info("Set scale-up-threshold to {}% for group {}", (int)(threshold * 100), groupName);
                } catch (NumberFormatException e) {
                    Logger.error("Invalid number: " + value);
                }
            }
            case "scale-down-threshold" -> {
                try {
                    double threshold = Double.parseDouble(value);
                    if (threshold < 0.0 || threshold > 1.0) {
                        Logger.error("Scale-down threshold must be between 0.0 and 1.0");
                        return;
                    }
                    scaler.setScaleDownThreshold(threshold);
                    scaler.getScalerConfig().updateAndSave();
                    Logger.info("Set scale-down-threshold to {}% for group {}", (int)(threshold * 100), groupName);
                } catch (NumberFormatException e) {
                    Logger.error("Invalid number: " + value);
                }
            }
            default -> Logger.error("Unknown property: " + property);
        }
    }

    private void handleGet(String[] args) {
        if (args.length < 2) {
            Logger.error("Usage: scaling get <group>");
            return;
        }

        String groupName = args[1];
        Scaler scaler = AtlasBase.getInstance().getScalerManager().getScaler(groupName);
        
        if (scaler == null) {
            Logger.error("Group not found: " + groupName);
            return;
        }

        ScalerConfig.Group groupConfig = scaler.getScalerConfig().getGroup();
        ScalerConfig.Server serverConfig = groupConfig.getServer();
        ScalerConfig.Conditions conditions = groupConfig.getScaling().getConditions();

        Logger.info("Scaling Configuration for: " + groupName);
        Logger.info("");
        Logger.info("Server Limits:");
        Logger.info("  Min Servers: " + serverConfig.getMinServers());
        Logger.info("  Max Servers: " + (serverConfig.getMaxServers() == -1 ? "Unlimited" : serverConfig.getMaxServers()));
        Logger.info("");
        Logger.info("Scaling Thresholds:");
        Logger.info("  Scale Up: " + String.format("%.1f%%", conditions.getScaleUpThreshold() * 100));
        Logger.info("  Scale Down: " + String.format("%.1f%%", conditions.getScaleDownThreshold() * 100));
        Logger.info("");
        Logger.info("Current Status:");
        Logger.info("  Auto-scaled servers: " + scaler.getAutoScaledServers().size());
        Logger.info("  Manual servers: " + scaler.getManuallyScaledServers().size());
        Logger.info("  Current utilization: " + String.format("%.1f%%", scaler.getCurrentUtilization() * 100));
        Logger.info("  Scaling paused: " + (scaler.isPaused() ? "Yes" : "No"));
    }
}