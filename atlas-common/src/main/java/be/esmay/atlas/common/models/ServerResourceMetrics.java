package be.esmay.atlas.common.models;

import lombok.Builder;
import lombok.Data;

@Data
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
    
    public void setCpuUsage(double cpuUsage) {
        this.cpuUsage = Math.max(0, cpuUsage);
    }
    
    public void setMemoryUsed(long memoryUsed) {
        this.memoryUsed = Math.max(0, memoryUsed);
    }
    
    public void setMemoryTotal(long memoryTotal) {
        this.memoryTotal = Math.max(0, memoryTotal);
    }
    
    public void setDiskUsed(long diskUsed) {
        this.diskUsed = Math.max(0, diskUsed);
    }
    
    public void setDiskTotal(long diskTotal) {
        this.diskTotal = Math.max(0, diskTotal);
    }
    
    public void setNetworkReceiveBytes(long networkReceiveBytes) {
        this.networkReceiveBytes = Math.max(0, networkReceiveBytes);
    }
    
    public void setNetworkSendBytes(long networkSendBytes) {
        this.networkSendBytes = Math.max(0, networkSendBytes);
    }
    
    public void setNetworkReceiveBandwidth(double networkReceiveBandwidth) {
        this.networkReceiveBandwidth = Math.max(0, networkReceiveBandwidth);
    }
    
    public void setNetworkSendBandwidth(double networkSendBandwidth) {
        this.networkSendBandwidth = Math.max(0, networkSendBandwidth);
    }
    
    public void setLastUpdated(long lastUpdated) {
        this.lastUpdated = Math.max(0, lastUpdated);
    }
    
    public static ServerResourceMetricsBuilder builder() {
        return new ServerResourceMetricsBuilder();
    }
    
    public static class ServerResourceMetricsBuilder {
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
        
        public ServerResourceMetricsBuilder cpuUsage(double cpuUsage) {
            this.cpuUsage = Math.max(0, cpuUsage);
            return this;
        }
        
        public ServerResourceMetricsBuilder memoryUsed(long memoryUsed) {
            this.memoryUsed = Math.max(0, memoryUsed);
            return this;
        }
        
        public ServerResourceMetricsBuilder memoryTotal(long memoryTotal) {
            this.memoryTotal = Math.max(0, memoryTotal);
            return this;
        }
        
        public ServerResourceMetricsBuilder diskUsed(long diskUsed) {
            this.diskUsed = Math.max(0, diskUsed);
            return this;
        }
        
        public ServerResourceMetricsBuilder diskTotal(long diskTotal) {
            this.diskTotal = Math.max(0, diskTotal);
            return this;
        }
        
        public ServerResourceMetricsBuilder networkReceiveBytes(long networkReceiveBytes) {
            this.networkReceiveBytes = Math.max(0, networkReceiveBytes);
            return this;
        }
        
        public ServerResourceMetricsBuilder networkSendBytes(long networkSendBytes) {
            this.networkSendBytes = Math.max(0, networkSendBytes);
            return this;
        }
        
        public ServerResourceMetricsBuilder networkReceiveBandwidth(double networkReceiveBandwidth) {
            this.networkReceiveBandwidth = Math.max(0, networkReceiveBandwidth);
            return this;
        }
        
        public ServerResourceMetricsBuilder networkSendBandwidth(double networkSendBandwidth) {
            this.networkSendBandwidth = Math.max(0, networkSendBandwidth);
            return this;
        }
        
        public ServerResourceMetricsBuilder lastUpdated(long lastUpdated) {
            this.lastUpdated = Math.max(0, lastUpdated);
            return this;
        }
        
        public ServerResourceMetrics build() {
            ServerResourceMetrics metrics = new ServerResourceMetrics();
            metrics.cpuUsage = this.cpuUsage;
            metrics.memoryUsed = this.memoryUsed;
            metrics.memoryTotal = this.memoryTotal;
            metrics.diskUsed = this.diskUsed;
            metrics.diskTotal = this.diskTotal;
            metrics.networkReceiveBytes = this.networkReceiveBytes;
            metrics.networkSendBytes = this.networkSendBytes;
            metrics.networkReceiveBandwidth = this.networkReceiveBandwidth;
            metrics.networkSendBandwidth = this.networkSendBandwidth;
            metrics.lastUpdated = this.lastUpdated;
            return metrics;
        }
    }
    
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