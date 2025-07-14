package be.esmay.atlas.base.network.security;

import be.esmay.atlas.base.config.impl.AtlasConfig;
import be.esmay.atlas.base.utils.Logger;

public final class AuthenticationHandler {
    
    private final AtlasConfig.Network networkConfig;
    
    public AuthenticationHandler(AtlasConfig.Network networkConfig) {
        this.networkConfig = networkConfig;
    }
    
    public boolean authenticate(String token) {
        if (!this.networkConfig.isAuthRequired()) {
            return true;
        }
        
        if (token == null || token.isEmpty()) {
            Logger.warn("Authentication failed: No token provided");
            return false;
        }
        
        String expectedToken = this.networkConfig.getNettyKey();
        if (expectedToken == null || expectedToken.isEmpty()) {
            Logger.warn("Authentication failed: No netty-key configured");
            return false;
        }
        
        boolean authenticated = token.equals(expectedToken);
        
        if (!authenticated) {
            Logger.warn("Authentication failed: Invalid token");
        }
        
        return authenticated;
    }
    
    public String getPermissionLevel(String token) {
        if (this.authenticate(token)) {
            return "FULL";
        }
        return "NONE";
    }
    
}