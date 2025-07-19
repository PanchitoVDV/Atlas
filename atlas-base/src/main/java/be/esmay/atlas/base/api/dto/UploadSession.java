package be.esmay.atlas.base.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Data
@Builder
public final class UploadSession {
    
    private final String uploadId;
    private final String serverId;
    private final String targetPath;
    private final String workingDirectory;
    private final int chunkSize;
    private final LocalDateTime createdAt;
    private final Set<Integer> receivedChunks;
    private final long totalSize;
    private final int totalChunks;
    
    public static UploadSession create(String uploadId, String serverId, String targetPath, String workingDirectory, long totalSize, int chunkSize) {
        int totalChunks = (int) Math.ceil((double) totalSize / chunkSize);
        
        return UploadSession.builder()
            .uploadId(uploadId)
            .serverId(serverId)
            .targetPath(targetPath)
            .workingDirectory(workingDirectory)
            .chunkSize(chunkSize)
            .createdAt(LocalDateTime.now())
            .receivedChunks(ConcurrentHashMap.newKeySet())
            .totalSize(totalSize)
            .totalChunks(totalChunks)
            .build();
    }
    
    public boolean isComplete() {
        return this.receivedChunks.size() == this.totalChunks;
    }
    
    public double getProgress() {
        if (this.totalChunks == 0) return 1.0;
        return (double) this.receivedChunks.size() / this.totalChunks;
    }
    
    public void markChunkReceived(int chunkNumber) {
        this.receivedChunks.add(chunkNumber);
    }
}