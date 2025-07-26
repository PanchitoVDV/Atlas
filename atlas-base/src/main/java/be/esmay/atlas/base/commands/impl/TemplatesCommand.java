package be.esmay.atlas.base.commands.impl;

import be.esmay.atlas.base.AtlasBase;
import be.esmay.atlas.base.commands.AtlasCommand;
import be.esmay.atlas.base.template.TemplateManager;
import be.esmay.atlas.base.utils.Logger;

import java.util.List;

public final class TemplatesCommand implements AtlasCommand {

    @Override
    public String getName() {
        return "templates";
    }

    @Override
    public List<String> getAliases() {
        return List.of("template", "temp");
    }

    @Override
    public String getDescription() {
        return "Manage templates and template cache";
    }

    @Override
    public void execute(String[] args) {
        if (args.length == 0) {
            this.showHelp();
            return;
        }

        switch (args[0].toLowerCase()) {
            case "clearcache", "clear-cache", "clear" -> this.clearCache();
            case "help" -> this.showHelp();
            default -> {
                Logger.warn("Unknown templates subcommand: " + args[0]);
                this.showHelp();
            }
        }
    }

    @Override
    public String getUsage() {
        return "templates <clearcache|help>";
    }

    private void clearCache() {
        AtlasBase atlasBase = AtlasBase.getInstance();
        if (atlasBase == null) {
            Logger.error("Atlas instance not available");
            return;
        }

        TemplateManager templateManager = new TemplateManager(
            atlasBase.getConfigManager().getAtlasConfig().getAtlas().getTemplates(),
            atlasBase.getConfigManager().getAtlasConfig().getAtlas().getS3()
        );
        
        Logger.info("Clearing template cache...");
        boolean success = templateManager.clearCache();
        
        if (success) {
            Logger.info("Template cache cleared successfully");
        } else {
            Logger.error("Failed to clear template cache");
        }
        
        templateManager.close();
    }

    private void showHelp() {
        Logger.info("Templates Command Help:");
        Logger.info("  templates clearcache - Clear the S3 template cache");
        Logger.info("  templates help - Show this help message");
        Logger.info("");
        Logger.info("Aliases: template, temp");
        Logger.info("Usage: " + this.getUsage());
    }
}