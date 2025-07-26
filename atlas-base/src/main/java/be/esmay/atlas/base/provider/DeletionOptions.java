package be.esmay.atlas.base.provider;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public final class DeletionOptions {
    
    /**
     * Why the server is being deleted
     */
    @Builder.Default
    private DeletionReason reason = DeletionReason.USER_COMMAND;
    
    /**
     * Whether to clean up the server's directory (applies to DYNAMIC servers)
     */
    @Builder.Default
    private boolean cleanupDirectory = true;
    
    /**
     * Whether to attempt graceful stop before force deletion
     */
    @Builder.Default
    private boolean gracefulStop = true;
    
    /**
     * How long to wait for operations to complete (seconds)
     */
    @Builder.Default
    private int timeoutSeconds = 30;
    
    /**
     * Whether to remove from scaler tracking immediately (for error recovery)
     */
    @Builder.Default
    private boolean removeFromTracking = true;
    
    /**
     * Create options for user-initiated deletion
     */
    public static DeletionOptions userCommand() {
        return DeletionOptions.builder()
            .reason(DeletionReason.USER_COMMAND)
            .gracefulStop(true)
            .cleanupDirectory(true)
            .build();
    }
    
    /**
     * Create options for scaling down
     */
    public static DeletionOptions scalingDown() {
        return DeletionOptions.builder()
            .reason(DeletionReason.SCALING_DOWN)
            .gracefulStop(true)
            .cleanupDirectory(true)
            .build();
    }
    
    /**
     * Create options for connection lost
     */
    public static DeletionOptions connectionLost() {
        return DeletionOptions.builder()
            .reason(DeletionReason.CONNECTION_LOST)
            .gracefulStop(false)
            .cleanupDirectory(true)
            .timeoutSeconds(10)
            .build();
    }
    
    /**
     * Create options for system shutdown
     */
    public static DeletionOptions systemShutdown() {
        return DeletionOptions.builder()
            .reason(DeletionReason.SYSTEM_SHUTDOWN)
            .gracefulStop(false)
            .cleanupDirectory(true)
            .timeoutSeconds(10)
            .build();
    }
    
    /**
     * Create options for error recovery/zombie cleanup
     */
    public static DeletionOptions errorRecovery() {
        return DeletionOptions.builder()
            .reason(DeletionReason.ERROR_RECOVERY)
            .gracefulStop(false)
            .cleanupDirectory(true)
            .timeoutSeconds(5)
            .removeFromTracking(true)
            .build();
    }
    
    /**
     * Create options for server restart
     */
    public static DeletionOptions serverRestart() {
        return DeletionOptions.builder()
            .reason(DeletionReason.SERVER_RESTART)
            .gracefulStop(true)
            .cleanupDirectory(false)
            .timeoutSeconds(30)
            .removeFromTracking(false)
            .build();
    }
}