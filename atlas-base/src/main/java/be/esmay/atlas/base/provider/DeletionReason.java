package be.esmay.atlas.base.provider;

public enum DeletionReason {

    /**
     * Manual stop/remove command from user
     */
    USER_COMMAND,
    
    /**
     * Automatic scaling down
     */
    SCALING_DOWN,
    
    /**
     * Server disconnected/lost connection
     */
    CONNECTION_LOST,
    
    /**
     * System shutdown - clean up all servers
     */
    SYSTEM_SHUTDOWN,
    
    /**
     * Cleaning up failed/zombie server
     */
    ERROR_RECOVERY
}