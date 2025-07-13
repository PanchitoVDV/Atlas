package be.esmay.atlas.base.provider;

import be.esmay.atlas.base.config.impl.AtlasConfig;
import be.esmay.atlas.base.utils.Logger;
import lombok.Getter;

@Getter
public final class ProviderManager {

    private ServiceProvider provider;

    public void initialize(AtlasConfig atlasConfig) {
        Logger.info("Initializing {} provider", atlasConfig.getAtlas().getServiceProvider().getType().toUpperCase());

        this.provider = ProviderRegistry.get(atlasConfig.getAtlas().getServiceProvider());
        if (this.provider == null) {
            Logger.error("No valid service provider found in configuration.");
            return;
        }
    }

    public void shutdown() {
        if (this.provider != null) {
            Logger.info("Shutting down service provider: {}", this.provider.getName());
            this.provider.shutdown();
        }
    }

}
