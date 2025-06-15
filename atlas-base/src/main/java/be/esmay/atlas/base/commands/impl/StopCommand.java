package be.esmay.atlas.base.commands.impl;

import be.esmay.atlas.base.AtlasBase;
import be.esmay.atlas.base.commands.AtlasCommand;
import be.esmay.atlas.base.utils.Logger;
import lombok.RequiredArgsConstructor;

import java.util.List;

@RequiredArgsConstructor
public final class StopCommand implements AtlasCommand {

    private final AtlasBase atlasBase;

    @Override
    public String getName() {
        return "stop";
    }

    @Override
    public List<String> getAliases() {
        return List.of("shutdown", "exit", "quit");
    }

    @Override
    public String getDescription() {
        return "Stops the Atlas server gracefully, allowing all tasks to complete and resources to be released.";
    }

    @Override
    public void execute(String[] args) {
        Logger.info("Shutting down Atlas...");
        this.atlasBase.shutdown();
        System.exit(0);
    }

    @Override
    public String getUsage() {
        return "stop";
    }

}
