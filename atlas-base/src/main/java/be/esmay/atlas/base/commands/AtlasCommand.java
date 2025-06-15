package be.esmay.atlas.base.commands;

import java.util.List;

public interface AtlasCommand {

    String getName();

    List<String> getAliases();

    String getDescription();

    void execute(String[] args);

    String getUsage();

}
