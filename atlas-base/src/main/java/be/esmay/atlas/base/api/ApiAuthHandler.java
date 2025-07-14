package be.esmay.atlas.base.api;

import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import lombok.Getter;

@Getter
public final class ApiAuthHandler {

    private final String apiKey;

    public ApiAuthHandler(String apiKey) {
        this.apiKey = apiKey;
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
        JsonObject error = new JsonObject()
            .put("status", "error")
            .put("message", "Unauthorized: Invalid or missing API key");
        
        context.response()
            .setStatusCode(401)
            .putHeader("Content-Type", "application/json")
            .end(error.encode());
    }
}