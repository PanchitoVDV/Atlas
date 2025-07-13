package be.esmay.atlas.base.commands.impl;

import be.esmay.atlas.base.AtlasBase;
import be.esmay.atlas.base.commands.AtlasCommand;
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
        Logger.info("");
        Logger.info("Examples:");
        Logger.info("  scaling status");
        Logger.info("  scaling pause lobby");
        Logger.info("  scaling pause lobby     # Run again to resume");
        Logger.info("  scaling trigger lobby up");
        Logger.info("  scaling trigger lobby down");
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
            var conditions = scaler.getScalerConfig().getGroup().getScaling().getConditions();
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
}