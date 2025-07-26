package be.esmay.atlas.base.commands.impl;

import be.esmay.atlas.base.AtlasBase;
import be.esmay.atlas.base.commands.AtlasCommand;
import be.esmay.atlas.base.cron.CronScheduler;
import be.esmay.atlas.base.utils.Logger;

import java.util.List;

public final class CronCommand implements AtlasCommand {

    private final CronScheduler cronScheduler;

    public CronCommand() {
        this.cronScheduler = AtlasBase.getInstance().getCronScheduler();
    }

    @Override
    public String getName() {
        return "cron";
    }

    @Override
    public List<String> getAliases() {
        return List.of("cronjob", "schedule");
    }

    @Override
    public String getDescription() {
        return "Manage and execute cron jobs manually.";
    }

    @Override
    public String getUsage() {
        return "cron <list|execute> [args]";
    }

    @Override
    public void execute(String[] args) {
        if (args.length == 0) {
            this.showHelp();
            return;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "list" -> this.handleList(args);
            case "execute", "run" -> this.handleExecute(args);
            case "help" -> this.showHelp();
            default -> {
                Logger.error("Unknown cron subcommand: {}", subCommand);
                this.showHelp();
            }
        }
    }

    private void handleList(String[] args) {
        if (args.length == 1) {
            List<String> allJobs = this.cronScheduler.listAllCronJobs();
            if (allJobs.isEmpty()) {
                Logger.info("No cron jobs found across all groups.");
                return;
            }

            Logger.info("All cron jobs:");
            for (String job : allJobs) {
                Logger.info("  {}", job);
            }
        } else if (args.length == 2) {
            String groupName = args[1];
            List<String> groupJobs = this.cronScheduler.listCronJobs(groupName);
            
            if (groupJobs.isEmpty()) {
                Logger.info("No cron jobs found for group: {}", groupName);
                return;
            }

            Logger.info("Cron jobs for group '{}':", groupName);
            for (String job : groupJobs) {
                Logger.info("  {}", job);
            }
        } else {
            Logger.error("Usage: cron list [group]");
        }
    }

    private void handleExecute(String[] args) {
        if (args.length != 3) {
            Logger.error("Usage: cron execute <group> <job-name>");
            return;
        }

        String groupName = args[1];
        String jobName = args[2];

        boolean success = this.cronScheduler.executeJobManually(groupName, jobName);
        
        if (!success) {
            Logger.error("Failed to execute cron job '{}' for group '{}'", jobName, groupName);
        }
    }

    private void showHelp() {
        Logger.info("Cron Job Management Commands:");
        Logger.info("  cron list                    - List all cron jobs across all groups");
        Logger.info("  cron list <group>            - List cron jobs for a specific group");
        Logger.info("  cron execute <group> <job>   - Execute a cron job manually");
        Logger.info("  cron help                    - Show this help message");
        Logger.info("");
        Logger.info("Examples:");
        Logger.info("  cron list");
        Logger.info("  cron list lobby");
        Logger.info("  cron execute lobby daily-backup");
    }
}