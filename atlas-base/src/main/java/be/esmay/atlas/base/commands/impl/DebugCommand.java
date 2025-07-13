package be.esmay.atlas.base.commands.impl;

import be.esmay.atlas.base.AtlasBase;
import be.esmay.atlas.base.commands.AtlasCommand;

import java.util.List;

public class DebugCommand implements AtlasCommand {

    @Override
    public String getName() {
        return "debug";
    }

    @Override
    public List<String> getAliases() {
        return List.of();
    }

    @Override
    public String getDescription() {
        return "Toggle debug mode to show/hide debug messages";
    }

    @Override
    public void execute(String[] args) {
        AtlasBase atlasBase = AtlasBase.getInstance();
        boolean currentDebugMode = atlasBase.isDebugMode();
        boolean newDebugMode = !currentDebugMode;

        atlasBase.setDebugMode(newDebugMode);
    }

    @Override
    public String getUsage() {
        return "debug";
    }
}