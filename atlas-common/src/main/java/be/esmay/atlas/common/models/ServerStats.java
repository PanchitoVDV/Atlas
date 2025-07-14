package be.esmay.atlas.common.models;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ServerStats {
    private final double cpuUsagePercent;
    private final long memoryUsedBytes;
    private final long memoryTotalBytes;
    private final long diskUsedBytes;
    private final long diskTotalBytes;
    private final long networkRxBytes;
    private final long networkTxBytes;
    private final long timestamp;

    public double getMemoryUsagePercent() {
        if (this.memoryTotalBytes == 0) {
            return 0.0;
        }
        return (double) this.memoryUsedBytes / this.memoryTotalBytes * 100.0;
    }

    public double getDiskUsagePercent() {
        if (this.diskTotalBytes == 0) {
            return 0.0;
        }
        return (double) this.diskUsedBytes / this.diskTotalBytes * 100.0;
    }
}