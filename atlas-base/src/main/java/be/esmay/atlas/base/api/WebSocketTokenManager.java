package be.esmay.atlas.base.api;

import be.esmay.atlas.base.utils.Logger;
import io.vertx.core.Vertx;
import lombok.Data;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class WebSocketTokenManager {
    
    private static final int TOKEN_VALIDITY_MINUTES = 15;
    private static final int TOKEN_LENGTH = 32;
    private static final int MAX_TOKENS_PER_MINUTE = 10;
    private static final long CLEANUP_INTERVAL_MS = 60000; // 1 minute
    
    private final SecureRandom secureRandom;
    private final Map<String, TokenInfo> tokens;
    private final Map<String, RateLimitInfo> rateLimits;
    private final Vertx vertx;
    
    public WebSocketTokenManager(Vertx vertx) {
        this.secureRandom = new SecureRandom();
        this.tokens = new ConcurrentHashMap<>();
        this.rateLimits = new ConcurrentHashMap<>();
        this.vertx = vertx;
        
        this.startCleanupTimer();
    }
    
    public TokenInfo generateToken(String apiKey, String serverId) {
        boolean rateLimitOk = this.checkRateLimit(apiKey);
        if (!rateLimitOk) {
            throw new SecurityException("Rate limit exceeded for token generation");
        }
        
        String token = this.generateSecureToken();
        long expiresAt = System.currentTimeMillis() + (TOKEN_VALIDITY_MINUTES * 60 * 1000);
        
        TokenInfo tokenInfo = new TokenInfo(token, apiKey, serverId, expiresAt);
        this.tokens.put(token, tokenInfo);
        
        Logger.debug("Generated WebSocket token for server " + serverId + ", expires at " + expiresAt);
        
        return tokenInfo;
    }
    
    public boolean validateToken(String token, String serverId) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        
        TokenInfo tokenInfo = this.tokens.get(token);
        if (tokenInfo == null) {
            return false;
        }
        
        long currentTime = System.currentTimeMillis();
        if (currentTime > tokenInfo.getExpiresAt()) {
            this.tokens.remove(token);
            Logger.debug("Token expired for server " + serverId);
            return false;
        }
        
        String expectedServerId = tokenInfo.getServerId();
        if (!expectedServerId.equals(serverId)) {
            Logger.warn("Token used for wrong server. Expected: " + expectedServerId + ", Got: " + serverId);
            return false;
        }
        
        return true;
    }
    
    public void revokeToken(String token) {
        TokenInfo removed = this.tokens.remove(token);
        if (removed != null) {
            String serverId = removed.getServerId();
            Logger.debug("Revoked token for server " + serverId);
        }
    }
    
    public void revokeTokensForServer(String serverId) {
        int count = 0;
        for (Map.Entry<String, TokenInfo> entry : this.tokens.entrySet()) {
            TokenInfo tokenInfo = entry.getValue();
            String tokenServerId = tokenInfo.getServerId();
            if (tokenServerId.equals(serverId)) {
                String tokenKey = entry.getKey();
                this.tokens.remove(tokenKey);
                count++;
            }
        }
        if (count > 0) {
            Logger.debug("Revoked " + count + " tokens for server " + serverId);
        }
    }
    
    private boolean checkRateLimit(String apiKey) {
        long now = System.currentTimeMillis();
        long windowStart = now - 60000; // 1 minute window
        
        RateLimitInfo rateLimitInfo = this.rateLimits.compute(apiKey, (key, existing) -> {
            if (existing == null) {
                AtomicInteger newCount = new AtomicInteger(1);
                return new RateLimitInfo(windowStart, newCount);
            }
            
            long existingWindowStart = existing.getWindowStart();
            if (existingWindowStart < windowStart) {
                existing.setWindowStart(windowStart);
                AtomicInteger count = existing.getCount();
                count.set(1);
            } else {
                AtomicInteger count = existing.getCount();
                count.incrementAndGet();
            }
            
            return existing;
        });
        
        AtomicInteger count = rateLimitInfo.getCount();
        int currentCount = count.get();
        return currentCount <= MAX_TOKENS_PER_MINUTE;
    }
    
    private String generateSecureToken() {
        byte[] bytes = new byte[TOKEN_LENGTH];
        this.secureRandom.nextBytes(bytes);
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString(bytes);
    }
    
    private void startCleanupTimer() {
        this.vertx.setPeriodic(CLEANUP_INTERVAL_MS, timerId -> {
            long now = System.currentTimeMillis();
            int removed = 0;

            for (Map.Entry<String, TokenInfo> entry : this.tokens.entrySet()) {
                TokenInfo tokenInfo = entry.getValue();
                long expiresAt = tokenInfo.getExpiresAt();
                if (expiresAt < now) {
                    String tokenKey = entry.getKey();
                    this.tokens.remove(tokenKey);
                    removed++;
                }
            }

            long windowStart = now - 120000; // 2 minutes ago
            this.rateLimits.entrySet().removeIf(entry -> {
                RateLimitInfo rateLimitInfo = entry.getValue();
                long entryWindowStart = rateLimitInfo.getWindowStart();
                return entryWindowStart < windowStart;
            });
            
            if (removed > 0) {
                Logger.debug("Cleaned up " + removed + " expired WebSocket tokens");
            }
        });
    }
    
    public void shutdown() {
        this.tokens.clear();
        this.rateLimits.clear();
    }
    
    @Data
    public static class TokenInfo {
        private final String token;
        private final String apiKey;
        private final String serverId;
        private final long expiresAt;
    }
    
    @Data
    private static class RateLimitInfo {
        private long windowStart;
        private final AtomicInteger count;
        
        public RateLimitInfo(long windowStart, AtomicInteger count) {
            this.windowStart = windowStart;
            this.count = count;
        }
    }
}