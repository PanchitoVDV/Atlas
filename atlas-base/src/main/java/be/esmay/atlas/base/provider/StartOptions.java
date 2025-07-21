package be.esmay.atlas.base.provider;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public final class StartOptions {
    
    private StartReason reason;
    
    /**
     * Whether to prepare/create the server directory
     */
    @Builder.Default
    private boolean prepareDirectory = true;
    
    /**
     * Whether to apply templates to the server directory
     */
    @Builder.Default  
    private boolean applyTemplates = true;
    
    /**
     * Whether to validate that required resources are available before starting
     */
    @Builder.Default
    private boolean validateResources = true;
    
    /**
     * Whether to wait until the server reaches RUNNING status
     */
    @Builder.Default
    private boolean waitForReady = false;
    
    /**
     * Timeout in seconds for start operations
     */
    @Builder.Default
    private int timeoutSeconds = 120;
    
    /**
     * Whether to cleanup (remove container/directory) if start fails
     */
    @Builder.Default
    private boolean cleanupOnFailure = true;
    
    /**
     * Whether this server should be added to tracking after successful start
     */
    @Builder.Default
    private boolean addToTracking = true;
    
    /**
     * Create options for user-initiated server start
     */
    public static StartOptions userCommand() {
        return StartOptions.builder()
            .reason(StartReason.USER_COMMAND)
            .prepareDirectory(true)
            .applyTemplates(true)
            .validateResources(true)
            .waitForReady(false)
            .cleanupOnFailure(true)
            .build();
    }
    
    /**
     * Create options for auto-scaling server creation
     */
    public static StartOptions scalingUp() {
        return StartOptions.builder()
            .reason(StartReason.SCALING_UP)
            .prepareDirectory(true)
            .applyTemplates(true)
            .validateResources(true)
            .waitForReady(false)
            .cleanupOnFailure(true)
            .build();
    }
    
    /**
     * Create options for server restart
     */
    public static StartOptions restart() {
        return StartOptions.builder()
            .reason(StartReason.RESTART)
            .prepareDirectory(false)
            .applyTemplates(true)
            .validateResources(true)
            .waitForReady(false)
            .cleanupOnFailure(false)
            .build();
    }
    
    /**
     * Create options for recovery start after failure
     */
    public static StartOptions recovery() {
        return StartOptions.builder()
            .reason(StartReason.RECOVERY)
            .prepareDirectory(true)
            .applyTemplates(false)
            .validateResources(false)
            .waitForReady(false)
            .cleanupOnFailure(true)
            .build();
    }
    
    /**
     * Create options for system startup
     */
    public static StartOptions systemStartup() {
        return StartOptions.builder()
            .reason(StartReason.SYSTEM_STARTUP)
            .prepareDirectory(true)
            .applyTemplates(true)
            .validateResources(true)
            .waitForReady(false)
            .cleanupOnFailure(true)
            .build();
    }
}