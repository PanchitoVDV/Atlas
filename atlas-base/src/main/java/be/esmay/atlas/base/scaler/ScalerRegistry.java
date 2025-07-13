package be.esmay.atlas.base.scaler;

import be.esmay.atlas.base.config.impl.ScalerConfig;
import be.esmay.atlas.base.scaler.impl.NormalScaler;
import be.esmay.atlas.base.scaler.impl.ProxyScaler;
import be.esmay.atlas.base.utils.Logger;

import java.util.HashMap;
import java.util.Map;

public final class ScalerRegistry {

    private static final Map<String, Class<? extends Scaler>> SCALERS = new HashMap<>();

    static {
        SCALERS.put("NORMAL", NormalScaler.class);
        SCALERS.put("PROXY", ProxyScaler.class);
    }

    public static void registerScaler(String actionKey, Class<? extends Scaler> scalerClass) {
        SCALERS.put(actionKey, scalerClass);
    }

    public static Scaler get(String key, String groupName, ScalerConfig scalerConfig) {
        Class<? extends Scaler> providerClass = SCALERS.get(key.toUpperCase());
        if (providerClass == null) return null;

        try {
            return providerClass.getConstructor(String.class, ScalerConfig.class).newInstance(groupName, scalerConfig);
        } catch (Exception e) {
            Logger.error("Could not create provider with type " + key, e);
            return null;
        }
    }

}
