package be.esmay.atlas.base.scaler;

import be.esmay.atlas.base.config.impl.ScalerConfig;
import lombok.Getter;
import org.spongepowered.configurate.ConfigurationNode;

@Getter
public abstract class Scaler {

    protected final String groupName;
    protected final ScalerConfig scalerConfig;

    public Scaler(String groupName, ScalerConfig scalerConfig) {
        this.groupName = groupName;
        this.scalerConfig = scalerConfig;
    }

}
