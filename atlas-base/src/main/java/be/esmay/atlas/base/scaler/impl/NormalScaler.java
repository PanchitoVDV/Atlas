package be.esmay.atlas.base.scaler.impl;

import be.esmay.atlas.base.config.impl.ScalerConfig;
import be.esmay.atlas.base.scaler.Scaler;
import be.esmay.atlas.common.enums.ScaleType;

public final class NormalScaler extends Scaler {

    public NormalScaler(String groupName, ScalerConfig scalerConfig) {
        super(groupName, scalerConfig);
    }

    @Override
    public ScaleType needsScaling() {
        if (this.shutdown)
            return ScaleType.NONE;

        if (this.getAutoScaledServers().size() < this.getMinServers() || this.shouldScaleUp())
            return ScaleType.UP;

        if (this.shouldScaleDown())
            return ScaleType.DOWN;

        return ScaleType.NONE;
    }

}
