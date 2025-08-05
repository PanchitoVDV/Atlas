package be.esmay.atlas.common.models;

import lombok.Data;

@Data
public class ScalingCondition {

    private String name;
    private int threshold;
    private int cooldownSeconds;
    private boolean enabled;
    
    private String scaleUpMetadataCondition;
    private String scaleDownProtectedCondition;

}