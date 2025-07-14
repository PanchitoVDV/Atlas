package be.esmay.atlas.base.api;

import be.esmay.atlas.base.AtlasBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.swagger.v3.oas.models.OpenAPI;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

public final class ApiDocumentation {

    private final Router router;
    private final ObjectMapper objectMapper;
    private final OpenAPI openApiSpec;

    public ApiDocumentation(Router router) {
        this.router = router;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.openApiSpec = OpenApiGenerator.generateOpenApiSpec();
    }

    public void setupDocumentationRoutes() {
        this.router.get("/api/docs").handler(this::serveScalarUI);
        this.router.get("/api/docs/openapi.json").handler(this::serveOpenApiSpec);
        this.router.get("/api/docs/openapi.yaml").handler(this::serveOpenApiSpecYaml);
    }

    private void serveScalarUI(RoutingContext context) {
        String apiPort = String.valueOf(AtlasBase.getInstance().getConfigManager().getAtlasConfig().getAtlas().getNetwork().getApiPort());
        String scalarHtml = this.generateScalarHtml(apiPort);
        
        context.response()
            .putHeader("Content-Type", "text/html; charset=utf-8")
            .end(scalarHtml);
    }

    private void serveOpenApiSpec(RoutingContext context) {
        try {
            String jsonSpec = this.objectMapper.writeValueAsString(this.openApiSpec);

            jsonSpec = this.fixScalarValidationIssues(jsonSpec);
            
            context.response()
                .putHeader("Content-Type", "application/json")
                .putHeader("Access-Control-Allow-Origin", "*")
                .putHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
                .putHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
                .end(jsonSpec);
        } catch (Exception e) {
            context.response()
                .setStatusCode(500)
                .end("Failed to generate OpenAPI specification");
        }
    }
    
    private String fixScalarValidationIssues(String jsonSpec) {
        jsonSpec = jsonSpec.replace("\"style\":\"FORM\"", "\"style\":\"form\"");
        jsonSpec = jsonSpec.replace("\"style\":\"SIMPLE\"", "\"style\":\"simple\"");

        jsonSpec = jsonSpec.replace("\"type\":\"HTTP\"", "\"type\":\"http\"");

        jsonSpec = jsonSpec.replaceAll("\"[^\"]+\":null,?", "");

        jsonSpec = jsonSpec.replaceAll(",+", ",");
        jsonSpec = jsonSpec.replace(",}", "}");
        jsonSpec = jsonSpec.replace(",]", "]");
        jsonSpec = jsonSpec.replace("{,", "{");
        jsonSpec = jsonSpec.replace("[,", "[");
        
        return jsonSpec;
    }

    private void serveOpenApiSpecYaml(RoutingContext context) {
        context.response()
            .putHeader("Content-Type", "application/yaml")
            .putHeader("Access-Control-Allow-Origin", "*")
            .putHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
            .putHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
            .end("# YAML format not implemented - use JSON endpoint");
    }

    private String generateScalarHtml(String apiPort) {
        return "<!doctype html>" +
            "<html>" +
            "<head>" +
            "<title>Atlas API Documentation</title>" +
            "<meta charset=\"utf-8\" />" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />" +
            "</head>" +
            "<body>" +
            "<script " +
            "id=\"api-reference\" " +
            "data-url=\"/api/docs/openapi.json\" " +
            "data-configuration='{\"theme\":\"saturn\",\"layout\":\"modern\"}'" +
            "></script>" +
            "<script src=\"https://cdn.jsdelivr.net/npm/@scalar/api-reference\"></script>" +
            "</body>" +
            "</html>";
    }
}