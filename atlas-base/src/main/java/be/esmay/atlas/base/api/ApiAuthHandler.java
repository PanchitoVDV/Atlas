package be.esmay.atlas.base.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

@Getter
public final class ApiAuthHandler {

    private final String apiKey;
    private final ObjectMapper objectMapper;
    @Setter
    private WebSocketTokenManager tokenManager;

    public ApiAuthHandler(String apiKey) {
        this.apiKey = apiKey;
        this.objectMapper = new ObjectMapper();
    }

    public Handler<RoutingContext> authenticate() {
        return context -> {
            String authHeader = context.request().getHeader("Authorization");
            String token = this.extractBearerToken(authHeader);

            if (this.isValidToken(token)) {
                context.next();
            } else {
                this.sendUnauthorized(context);
            }
        };
    }

    public boolean isValidToken(String token) {
        return token != null && token.equals(this.apiKey);
    }
    
    public boolean isValidWebSocketToken(String token, String serverId) {
        if (token == null) {
            return false;
        }

        if (token.equals(this.apiKey)) {
            return true;
        }

        if (this.tokenManager != null) {
            return this.tokenManager.validateToken(token, serverId);
        }
        
        return false;
    }

    public String extractBearerToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        return authHeader.substring(7);
    }

    public String extractTokenFromQuery(String query) {
        if (query == null) {
            return null;
        }
        
        String[] params = query.split("&");
        for (String param : params) {
            String[] keyValue = param.split("=", 2);
            if (keyValue.length == 2 && keyValue[0].equals("auth")) {
                return keyValue[1];
            }
        }
        return null;
    }

    private void sendUnauthorized(RoutingContext context) {
        Map<String, Object> error = new HashMap<>();
        error.put("status", "error");
        error.put("message", "Unauthorized: Invalid or missing API key");
        
        try {
            String errorJson = this.objectMapper.writeValueAsString(error);
            context.response()
                .setStatusCode(401)
                .putHeader("Content-Type", "application/json")
                .end(errorJson);
        } catch (Exception e) {
            context.response()
                .setStatusCode(401)
                .putHeader("Content-Type", "application/json")
                .end("{\"status\":\"error\",\"message\":\"Unauthorized\"}");
        }
    }
}