package be.esmay.atlas.base.provider;

import be.esmay.atlas.base.config.impl.AtlasConfig;
import be.esmay.atlas.base.utils.Logger;
import lombok.Getter;

@Getter
public final class ProviderManager {

    private ServiceProvider provider;

    public void initialize(AtlasConfig atlasConfig) {
        this.provider = ProviderRegistry.get(atlasConfig.getAtlas().getServiceProvider());
        if (this.provider == null) {
            Logger.error("No valid service provider found in configuration.");
            return;
        }

        Logger.info("Loaded {} provider", this.provider.getName());
    }

    public void shutdown() {

    }

}
