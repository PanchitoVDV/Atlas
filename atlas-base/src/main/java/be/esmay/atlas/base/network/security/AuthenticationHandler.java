package be.esmay.atlas.base.network.security;

import be.esmay.atlas.base.config.impl.AtlasConfig;
import be.esmay.atlas.base.network.NettyServer;
import be.esmay.atlas.base.utils.Logger;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public final class AuthenticationHandler {

    private final NettyServer nettyServer;

    public boolean authenticate(String token) {
        if (token == null || token.isEmpty()) {
            Logger.warn("Authentication failed: No token provided");
            return false;
        }

        String expectedToken = this.nettyServer.getNettyKey();
        if (expectedToken == null || expectedToken.isEmpty()) {
            Logger.warn("Authentication failed: No netty-key generated");
            return false;
        }

        boolean authenticated = token.equals(expectedToken);

        if (!authenticated) {
            Logger.warn("Authentication failed: Invalid token");
        }

        return authenticated;
    }

}