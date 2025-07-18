package be.esmay.atlas.base.metrics;

import be.esmay.atlas.base.utils.Logger;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class NetworkBandwidthMonitor {
    
    private static final String PROC_NET_DEV = "/proc/net/dev";
    private static final long POLL_INTERVAL_MS = 1000;
    
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ConcurrentHashMap<String, NetworkStats> previousStats = new ConcurrentHashMap<>();
    private final AtomicLong totalBytesReceived = new AtomicLong(0);
    private final AtomicLong totalBytesSent = new AtomicLong(0);
    private final AtomicLong lastUpdateTime = new AtomicLong(System.currentTimeMillis());
    
    private volatile double currentReceiveBandwidth = 0.0;
    private volatile double currentSendBandwidth = 0.0;
    private volatile long maxBandwidthBps = 10L * 1024 * 1024 * 1024;
    
    public void start() {
        this.detectNetworkSpeed();
        this.scheduler.scheduleAtFixedRate(this::updateBandwidth, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }
    
    public void stop() {
        this.scheduler.shutdown();
        try {
            if (!this.scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                this.scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            this.scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    public BandwidthStats getCurrentStats() {
        return new BandwidthStats(
            this.currentReceiveBandwidth,
            this.currentSendBandwidth,
            this.maxBandwidthBps,
            Math.max(this.currentReceiveBandwidth, this.currentSendBandwidth)
        );
    }
    
    private void updateBandwidth() {
        try {
            long currentTime = System.currentTimeMillis();
            long timeDeltaMs = currentTime - this.lastUpdateTime.getAndSet(currentTime);
            
            if (timeDeltaMs <= 0) return;
            
            double timeDeltaSec = timeDeltaMs / 1000.0;
            long totalRxBytes = 0;
            long totalTxBytes = 0;
            
            try (BufferedReader reader = new BufferedReader(new FileReader(PROC_NET_DEV))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains(":")) {
                        String[] parts = line.trim().split("\\s+");
                        if (parts.length >= 10) {
                            String iface = parts[0].replace(":", "");

                            if (iface.equals("lo") || iface.startsWith("veth") || 
                                iface.startsWith("br-") || iface.startsWith("docker")) {
                                continue;
                            }
                            
                            long rxBytes = Long.parseLong(parts[1]);
                            long txBytes = Long.parseLong(parts[9]);
                            
                            NetworkStats prev = this.previousStats.get(iface);
                            if (prev != null) {
                                long rxDelta = rxBytes - prev.rxBytes;
                                long txDelta = txBytes - prev.txBytes;
                                
                                if (rxDelta >= 0 && txDelta >= 0) {
                                    totalRxBytes += rxDelta;
                                    totalTxBytes += txDelta;
                                }
                            }
                            
                            this.previousStats.put(iface, new NetworkStats(rxBytes, txBytes));
                        }
                    }
                }
            }

            this.currentReceiveBandwidth = totalRxBytes / timeDeltaSec;
            this.currentSendBandwidth = totalTxBytes / timeDeltaSec;
            
            this.totalBytesReceived.addAndGet(totalRxBytes);
            this.totalBytesSent.addAndGet(totalTxBytes);
            
        } catch (IOException e) {
            Logger.debug("Failed to read network statistics: " + e.getMessage());
        }
    }
    
    private void detectNetworkSpeed() {
        try {
            Path sysClassNet = Paths.get("/sys/class/net");
            if (Files.exists(sysClassNet)) {
                Files.list(sysClassNet).forEach(ifacePath -> {
                    String iface = ifacePath.getFileName().toString();
                    if (!iface.equals("lo") && !iface.startsWith("veth") && 
                        !iface.startsWith("br-") && !iface.startsWith("docker")) {
                        
                        Path speedPath = ifacePath.resolve("speed");
                        if (Files.exists(speedPath)) {
                            try {
                                List<String> lines = Files.readAllLines(speedPath);
                                if (!lines.isEmpty()) {
                                    String speedStr = lines.get(0).trim();
                                    if (!speedStr.equals("-1")) {
                                        long speedMbps = Long.parseLong(speedStr);
                                        long speedBps = speedMbps * 1024 * 1024 / 8; // Convert Mbps to Bps
                                        this.maxBandwidthBps = Math.max(this.maxBandwidthBps, speedBps);
                                        Logger.debug("Detected network speed for " + iface + ": " + speedMbps + " Mbps");
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                });
            }
        } catch (Exception e) {
            Logger.debug("Failed to detect network speed, using default: " + e.getMessage());
        }
    }
    
    private static class NetworkStats {
        final long rxBytes;
        final long txBytes;
        
        NetworkStats(long rxBytes, long txBytes) {
            this.rxBytes = rxBytes;
            this.txBytes = txBytes;
        }
    }
    
    public static class BandwidthStats {
        public final double receiveBps;
        public final double sendBps;
        public final long maxBps;
        public final double usedBps;
        
        BandwidthStats(double receiveBps, double sendBps, long maxBps, double usedBps) {
            this.receiveBps = receiveBps;
            this.sendBps = sendBps;
            this.maxBps = maxBps;
            this.usedBps = usedBps;
        }
        
        public double getPercentage() {
            if (this.maxBps == 0) return 0;
            return (this.usedBps / this.maxBps) * 100;
        }
    }
}