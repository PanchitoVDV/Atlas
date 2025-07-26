package be.esmay.atlas.base.commands.impl;

import be.esmay.atlas.base.AtlasBase;
import be.esmay.atlas.base.commands.AtlasCommand;
import be.esmay.atlas.base.config.ConfigManager;
import be.esmay.atlas.base.config.impl.AtlasConfig;
import be.esmay.atlas.base.utils.Logger;

import java.util.List;

public final class ConfigCommand implements AtlasCommand {

    @Override
    public String getName() {
        return "config";
    }

    @Override
    public List<String> getAliases() {
        return List.of("cfg", "configuration");
    }

    @Override
    public String getDescription() {
        return "View and manage Atlas configuration settings.";
    }

    @Override
    public void execute(String[] args) {
        if (args.length == 0) {
            this.showHelp();
            return;
        }

        String subcommand = args[0].toLowerCase();
        switch (subcommand) {
            case "show" -> this.handleShow();
            case "reload" -> this.handleReload();
            case "help" -> this.showHelp();
            default -> {
                Logger.error("Unknown subcommand: " + subcommand);
                this.showHelp();
            }
        }
    }

    @Override
    public String getUsage() {
        return "config <subcommand>";
    }

    private void showHelp() {
        Logger.info("Usage: config <subcommand>");
        Logger.info("");
        Logger.info("Subcommands:");
        Logger.info("  show      Display current Atlas configuration");
        Logger.info("  reload    Reload Atlas configuration from disk");
        Logger.info("");
        Logger.info("Examples:");
        Logger.info("  config show");
        Logger.info("  config reload");
    }

    private void handleShow() {
        ConfigManager configManager = AtlasBase.getInstance().getConfigManager();
        AtlasConfig.Atlas config = configManager.getAtlasConfig().getAtlas();

        Logger.info("=== Atlas Configuration ===");
        Logger.info("");

        Logger.info("Network:");
        AtlasConfig.Network network = config.getNetwork();
        if (network != null) {
            Logger.info("  Port: " + network.getPort());
            Logger.info("  API Port: " + network.getApiPort());
            Logger.info("  API Key: " + (network.getApiKey() != null ? "[CONFIGURED]" : "[NOT SET]"));
        } else {
            Logger.info("  [NOT CONFIGURED]");
        }
        Logger.info("");

        Logger.info("Service Provider:");
        AtlasConfig.ServiceProvider serviceProvider = config.getServiceProvider();
        if (serviceProvider != null) {
            Logger.info("  Type: " + serviceProvider.getType());
            
            AtlasConfig.Docker docker = serviceProvider.getDocker();
            if (docker != null) {
                Logger.info("  Docker:");
                Logger.info("    Network: " + docker.getNetwork());
                Logger.info("    Auto Create Network: " + docker.isAutoCreateNetwork());
            }
        } else {
            Logger.info("  [NOT CONFIGURED]");
        }
        Logger.info("");

        Logger.info("Templates:");
        AtlasConfig.Templates templates = config.getTemplates();
        if (templates != null) {
            Logger.info("  Download on Startup: " + templates.isDownloadOnStartup());
            Logger.info("  Cleanup Dynamic on Shutdown: " + templates.isCleanupDynamicOnShutdown());
        } else {
            Logger.info("  [NOT CONFIGURED]");
        }
        Logger.info("");

        Logger.info("Scaling:");
        AtlasConfig.Scaling scaling = config.getScaling();
        if (scaling != null) {
            Logger.info("  Check Interval: " + scaling.getCheckInterval() + " seconds");
            Logger.info("  Cooldown: " + scaling.getCooldown() + " seconds");
        } else {
            Logger.info("  [NOT CONFIGURED]");
        }

    }

    private void handleReload() {
        Logger.info("Reloading Atlas configuration...");
        
        try {
            ConfigManager configManager = AtlasBase.getInstance().getConfigManager();
            configManager.reloadConfiguration();
            
            Logger.info("Atlas configuration reloaded successfully.");
            Logger.info("Note: Some changes may require a restart to take effect.");
            Logger.info("To reload server groups, use: groups reload");
        } catch (Exception e) {
            Logger.error("Failed to reload configuration: " + e.getMessage());
        }
    }
}