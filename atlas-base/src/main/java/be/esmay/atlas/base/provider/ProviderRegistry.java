package be.esmay.atlas.base.provider;

import be.esmay.atlas.base.config.impl.AtlasConfig;
import be.esmay.atlas.base.provider.impl.InMemoryServiceProvider;
import be.esmay.atlas.base.utils.Logger;

import java.util.HashMap;
import java.util.Map;

public final class ProviderRegistry {

    private static final Map<String, Class<? extends ServiceProvider>> PROVIDERS = new HashMap<>();

    static {
        PROVIDERS.put("IN_MEMORY", InMemoryServiceProvider.class);
    }

    public static void registerScaler(String actionKey, Class<? extends ServiceProvider> scalerClass) {
        PROVIDERS.put(actionKey, scalerClass);
    }

    public static ServiceProvider get(AtlasConfig.ServiceProvider serviceProviderConfig) {
        String key = serviceProviderConfig.getType();

        Class<? extends ServiceProvider> providerClass = PROVIDERS.get(key.toUpperCase());
        if (providerClass == null) return null;

        try {
            return providerClass.getConstructor(AtlasConfig.ServiceProvider.class).newInstance(serviceProviderConfig);
        } catch (Exception e) {
            Logger.error("Could not create provider with type " + key, e);
            return null;
        }
    }

}
