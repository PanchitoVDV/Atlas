package be.esmay.atlas.base.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class WebSocketMessage {

    private final String type;
    private final JsonNode data;
    private final String message;
    private final Long timestamp;
    private final String serverId;
    private final String commandId;

    private WebSocketMessage(String type, JsonNode data, String message, String serverId, String commandId) {
        this.type = type;
        this.data = data;
        this.message = message;
        this.serverId = serverId;
        this.commandId = commandId;
        this.timestamp = System.currentTimeMillis();
    }

    public static WebSocketMessage create(String type) {
        return new WebSocketMessage(type, null, null, null, null);
    }

    public static WebSocketMessage create(String type, JsonNode data) {
        return new WebSocketMessage(type, data, null, null, null);
    }

    public static WebSocketMessage create(String type, String message) {
        return new WebSocketMessage(type, null, message, null, null);
    }

    public static WebSocketMessage create(String type, JsonNode data, String serverId) {
        return new WebSocketMessage(type, data, null, serverId, null);
    }

    public static WebSocketMessage log(String serverId, String logMessage) {
        return new WebSocketMessage("log", null, logMessage, serverId, null);
    }

    public static WebSocketMessage event(String event, String serverId) {
        return new WebSocketMessage("event", null, event, serverId, null);
    }

    public static WebSocketMessage commandResult(String commandId, boolean success, String message) {
        return new WebSocketMessage("command-result", null, message, null, commandId);
    }

    public static WebSocketMessage error(String message) {
        return new WebSocketMessage("error", null, message, null, null);
    }

    public static WebSocketMessage authChallenge() {
        return new WebSocketMessage("auth-challenge", null, null, null, null);
    }

    public static WebSocketMessage authResult(boolean success) {
        return new WebSocketMessage("auth-result", null, success ? "Authentication successful" : "Authentication failed", null, null);
    }

    public static WebSocketMessage serverInfo(JsonNode serverData) {
        return new WebSocketMessage("server-info", serverData, null, null, null);
    }
}