package be.esmay.atlas.common.models;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public final class ServerResourceMetrics {
    
    private double cpuUsage;
    private long memoryUsed;
    private long memoryTotal;
    private long diskUsed;
    private long diskTotal;
    private long networkReceiveBytes;
    private long networkSendBytes;
    private double networkReceiveBandwidth;
    private double networkSendBandwidth;
    private long lastUpdated;
    
    public double getMemoryPercentage() {
        if (this.memoryTotal == 0) return 0;
        return (double) this.memoryUsed / this.memoryTotal * 100;
    }
    
    public double getDiskPercentage() {
        if (this.diskTotal == 0) return 0;
        return (double) this.diskUsed / this.diskTotal * 100;
    }
    
    public double getNetworkTotalBandwidth() {
        return this.networkReceiveBandwidth + this.networkSendBandwidth;
    }
}