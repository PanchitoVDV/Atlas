package be.esmay.atlas.base.provider;

public enum StartReason {

    /**
     * User manually started server via command or API
     */
    USER_COMMAND,
    
    /**
     * Auto-scaling system creating new server
     */
    SCALING_UP,
    
    /**
     * Restarting an existing server
     */
    RESTART,
    
    /**
     * Recovery start after crash or failure
     */
    RECOVERY,
    
    /**
     * System startup - starting servers when Atlas boots
     */
    SYSTEM_STARTUP
}