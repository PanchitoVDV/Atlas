package be.esmay.atlas.base.api;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public final class OpenApiGenerator {

    public static OpenAPI generateOpenApiSpec() {
        OpenAPI openAPI = new OpenAPI();
        
        openAPI.setInfo(createInfo());
        openAPI.setServers(createServers());
        openAPI.setPaths(createPaths());
        openAPI.setComponents(createComponents());
        openAPI.addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
        
        return openAPI;
    }

    private static Info createInfo() {
        return new Info()
            .title("Atlas Server Management API")
            .description("REST API for managing Atlas server scaling and monitoring with real-time WebSocket support")
            .version("1.0.0")
            .contact(new Contact()
                .name("Atlas Development Team")
                .email("support@atlas.local"))
            .license(new License()
                .name("MIT License")
                .url("https://opensource.org/licenses/MIT"));
    }

    private static List<Server> createServers() {
        return List.of(
            new Server()
                .url("http://localhost:9090")
                .description("Local development server"),
            new Server()
                .url("https://api.atlas.local")
                .description("Production server")
        );
    }

    private static Paths createPaths() {
        Paths paths = new Paths();
        
        paths.addPathItem("/api/v1/status", createStatusPath());
        paths.addPathItem("/api/v1/servers", createServersPath());
        paths.addPathItem("/api/v1/servers/count", createServerCountPath());
        paths.addPathItem("/api/v1/players/count", createPlayerCountPath());
        paths.addPathItem("/api/v1/servers/{id}", createServerByIdPath());
        paths.addPathItem("/api/v1/servers/{id}/logs", createServerLogsPath());
        paths.addPathItem("/api/v1/servers/{id}/files", createServerFilesPath());
        paths.addPathItem("/api/v1/servers/{id}/files/contents", createFileContentsPath());
        paths.addPathItem("/api/v1/servers/{id}/files/download", createFileDownloadPath());
        paths.addPathItem("/api/v1/servers/{id}/files/upload", createFileUploadPath());
        paths.addPathItem("/api/v1/servers/{id}/files/mkdir", createMkdirPath());
        paths.addPathItem("/api/v1/servers/{id}/files/rename", createFileRenamePath());
        paths.addPathItem("/api/v1/servers/{id}/start", createServerActionPath("start"));
        paths.addPathItem("/api/v1/servers/{id}/stop", createServerActionPath("stop"));
        paths.addPathItem("/api/v1/servers/{id}/command", createServerCommandPath());
        paths.addPathItem("/api/v1/groups", createGroupsPath());
        paths.addPathItem("/api/v1/groups/{name}", createGroupByNamePath());
        paths.addPathItem("/api/v1/groups/{group}/scale", createScalePath());
        paths.addPathItem("/api/v1/scaling", createScalingPath());
        paths.addPathItem("/api/v1/metrics", createMetricsPath());
        paths.addPathItem("/api/v1/utilization", createUtilizationPath());
        paths.addPathItem("/api/v1/servers/{id}/ws", createWebSocketPath());
        
        return paths;
    }

    private static PathItem createStatusPath() {
        return new PathItem()
            .get(new Operation()
                .operationId("getStatus")
                .summary("Get Atlas status")
                .description("Returns the current status and health of the Atlas system")
                .addTagsItem("System")
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .responses(createStandardResponses()
                    .addApiResponse("200", new ApiResponse()
                        .description("Atlas status information")
                        .content(new Content()
                            .addMediaType("application/json", new MediaType()
                                .schema(new Schema<>().$ref("#/components/schemas/StatusResponse")))))));
    }

    private static PathItem createServersPath() {
        return new PathItem()
            .get(new Operation()
                .operationId("getServers")
                .summary("List servers")
                .description("Returns a list of all servers, optionally filtered by group")
                .addTagsItem("Servers")
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .addParametersItem(new Parameter()
                    .name("group")
                    .in("query")
                    .description("Filter servers by group name (case-insensitive)")
                    .required(false)
                    .style(Parameter.StyleEnum.FORM)
                    .explode(true)
                    .schema(new StringSchema()))
                .addParametersItem(new Parameter()
                    .name("status")
                    .in("query")
                    .description("Filter servers by status (STARTING, RUNNING, STOPPING, STOPPED, ERROR)")
                    .required(false)
                    .style(Parameter.StyleEnum.FORM)
                    .explode(true)
                    .schema(new StringSchema()))
                .addParametersItem(new Parameter()
                    .name("search")
                    .in("query")
                    .description("Search servers by name, ID, or group")
                    .required(false)
                    .style(Parameter.StyleEnum.FORM)
                    .explode(true)
                    .schema(new StringSchema()))
                .responses(createStandardResponses()
                    .addApiResponse("200", new ApiResponse()
                        .description("List of servers")
                        .content(new Content()
                            .addMediaType("application/json", new MediaType()
                                .schema(new Schema<>().$ref("#/components/schemas/ServerListResponse")))))))
            .post(new Operation()
                .operationId("createServers")
                .summary("Create servers")
                .description("Creates new servers in the specified group")
                .addTagsItem("Servers")
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .requestBody(new RequestBody()
                    .description("Server creation parameters")
                    .required(true)
                    .content(new Content()
                        .addMediaType("application/json", new MediaType()
                            .schema(new Schema<>().$ref("#/components/schemas/CreateServerRequest")))))
                .responses(createStandardResponses()
                    .addApiResponse("200", new ApiResponse()
                        .description("Server creation initiated")
                        .content(new Content()
                            .addMediaType("application/json", new MediaType()
                                .schema(new Schema<>().$ref("#/components/schemas/ApiResponse")))))));
    }

    private static PathItem createServerCountPath() {
        return new PathItem()
            .get(new Operation()
                .operationId("getServerCount")
                .summary("Get server count")
                .description("Returns the total number of servers and breakdown by status and group")
                .addTagsItem("Servers")
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .responses(createStandardResponses()
                    .addApiResponse("200", new ApiResponse()
                        .description("Server count information")
                        .content(new Content()
                            .addMediaType("application/json", new MediaType()
                                .schema(new Schema<>().$ref("#/components/schemas/ServerCountResponse")))))));
    }

    private static PathItem createPlayerCountPath() {
        return new PathItem()
            .get(new Operation()
                .operationId("getPlayerCount")
                .summary("Get player count")
                .description("Returns the total number of players online and breakdown by group and status")
                .addTagsItem("Players")
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .responses(createStandardResponses()
                    .addApiResponse("200", new ApiResponse()
                        .description("Player count information")
                        .content(new Content()
                            .addMediaType("application/json", new MediaType()
                                .schema(new Schema<>().$ref("#/components/schemas/PlayerCountResponse")))))));
    }

    private static PathItem createServerByIdPath() {
        Parameter serverIdParam = new Parameter()
            .name("id")
            .in("path")
            .description("Server ID")
            .required(true)
            .style(Parameter.StyleEnum.SIMPLE)
            .explode(false)
            .schema(new StringSchema());

        return new PathItem()
            .get(new Operation()
                .operationId("getServer")
                .summary("Get server details")
                .description("Returns detailed information about a specific server")
                .addTagsItem("Servers")
                .addParametersItem(serverIdParam)
                .responses(createStandardResponses()
                    .addApiResponse("200", new ApiResponse()
                        .description("Server details")
                        .content(new Content()
                            .addMediaType("application/json", new MediaType()
                                .schema(new Schema<>().$ref("#/components/schemas/ServerResponse")))))
                    .addApiResponse("404", new ApiResponse()
                        .description("Server not found"))))
            .delete(new Operation()
                .operationId("removeServer")
                .summary("Remove server")
                .description("Permanently removes a server")
                .addTagsItem("Servers")
                .addParametersItem(serverIdParam)
                .responses(createStandardResponses()
                    .addApiResponse("200", new ApiResponse()
                        .description("Server removal initiated")
                        .content(new Content()
                            .addMediaType("application/json", new MediaType()
                                .schema(new Schema<>().$ref("#/components/schemas/ApiResponse")))))
                    .addApiResponse("404", new ApiResponse()
                        .description("Server not found"))));
    }

    private static PathItem createServerLogsPath() {
        Parameter serverIdParam = new Parameter()
            .name("id")
            .in("path")
            .description("Server ID")
            .required(true)
            .style(Parameter.StyleEnum.SIMPLE)
            .explode(false)
            .schema(new StringSchema());

        Parameter linesParam = new Parameter()
            .name("lines")
            .in("query")
            .description("Number of log lines to retrieve (default: all)")
            .required(false)
            .schema(new Schema<Integer>().type("integer").minimum(BigDecimal.valueOf(1)));

        return new PathItem()
            .get(new Operation()
                .operationId("getServerLogs")
                .summary("Get server logs")
                .description("Returns recent log lines from a specific server")
                .addTagsItem("Servers")
                .addParametersItem(serverIdParam)
                .addParametersItem(linesParam)
                .responses(createStandardResponses()
                    .addApiResponse("200", new ApiResponse()
                        .description("Server logs")
                        .content(new Content()
                            .addMediaType("application/json", new MediaType()
                                .schema(new Schema<>().$ref("#/components/schemas/ServerLogsResponse")))))
                    .addApiResponse("404", new ApiResponse()
                        .description("Server not found"))));
    }

    private static PathItem createServerFilesPath() {
        Parameter serverIdParam = new Parameter()
            .name("id")
            .in("path")
            .description("Server ID")
            .required(true)
            .style(Parameter.StyleEnum.SIMPLE)
            .explode(false)
            .schema(new StringSchema());

        Parameter pathParam = new Parameter()
            .name("path")
            .in("query")
            .description("Path within server directory to list (default: /)")
            .required(false)
            .schema(new StringSchema().example("/plugins"));

        return new PathItem()
            .get(new Operation()
                .operationId("getServerFiles")
                .summary("List server files")
                .description("Returns a list of files and directories in the specified path within the server's working directory")
                .addTagsItem("Servers")
                .addParametersItem(serverIdParam)
                .addParametersItem(pathParam)
                .responses(createStandardResponses()
                    .addApiResponse("200", new ApiResponse()
                        .description("Server file listing")
                        .content(new Content()
                            .addMediaType("application/json", new MediaType()
                                .schema(new Schema<>().$ref("#/components/schemas/FileListResponse")))))
                    .addApiResponse("400", new ApiResponse()
                        .description("Invalid path or directory traversal attempt"))
                    .addApiResponse("403", new ApiResponse()
                        .description("Security violation - path outside server directory"))
                    .addApiResponse("404", new ApiResponse()
                        .description("Server not found or path does not exist"))));
    }

    private static PathItem createFileContentsPath() {
        Parameter serverIdParam = new Parameter()
            .name("id")
            .in("path")
            .description("Server ID")
            .required(true)
            .style(Parameter.StyleEnum.SIMPLE)
            .explode(false)
            .schema(new StringSchema());

        Parameter fileParam = new Parameter()
            .name("file")
            .in("query")
            .description("Path to the file within server directory")
            .required(true)
            .schema(new StringSchema().example("config/server.properties"));

        return new PathItem()
            .get(new Operation()
                .operationId("getFileContents")
                .summary("Get file contents")
                .description("Returns the contents of a specific file within the server's working directory")
                .addTagsItem("Files")
                .addParametersItem(serverIdParam)
                .addParametersItem(fileParam)
                .responses(createStandardResponses()
                    .addApiResponse("200", new ApiResponse()
                        .description("File contents")
                        .content(new Content()
                            .addMediaType("text/plain", new MediaType()
                                .schema(new StringSchema()))))
                    .addApiResponse("400", new ApiResponse()
                        .description("Missing file parameter or invalid path"))
                    .addApiResponse("403", new ApiResponse()
                        .description("Security violation - file outside server directory"))
                    .addApiResponse("404", new ApiResponse()
                        .description("Server not found or file does not exist"))))
            .put(new Operation()
                .operationId("writeFileContents")
                .summary("Write file contents")
                .description("Writes content to a file within the server's working directory. Creates directories if needed. Send the file content as the request body.")
                .addTagsItem("Files")
                .addParametersItem(serverIdParam)
                .addParametersItem(fileParam)
                .requestBody(new RequestBody()
                    .description("File content to write (send as request body)")
                    .required(false)
                    .content(new Content()
                        .addMediaType("text/plain", new MediaType()
                            .schema(new StringSchema()
                                .example("server.port=25565\ndifficulty=normal\nmax-players=20")))
                        .addMediaType("application/octet-stream", new MediaType()
                            .schema(new StringSchema()
                                .format("binary")
                                .example("Binary file content")))))
                .responses(createStandardResponses()
                    .addApiResponse("200", new ApiResponse()
                        .description("File written successfully")
                        .content(new Content()
                            .addMediaType("application/json", new MediaType()
                                .schema(new Schema<>().$ref("#/components/schemas/ApiResponse")))))
                    .addApiResponse("400", new ApiResponse()
                        .description("Missing file parameter or invalid path"))
                    .addApiResponse("403", new ApiResponse()
                        .description("Security violation - file outside server directory"))
                    .addApiResponse("404", new ApiResponse()
                        .description("Server not found"))))
            .delete(new Operation()
                .operationId("deleteFile")
                .summary("Delete file")
                .description("Deletes a file or directory within the server's working directory")
                .addTagsItem("Files")
                .addParametersItem(serverIdParam)
                .addParametersItem(fileParam)
                .responses(createStandardResponses()
                    .addApiResponse("200", new ApiResponse()
                        .description("File deleted successfully")
                        .content(new Content()
                            .addMediaType("application/json", new MediaType()
                                .schema(new Schema<>().$ref("#/components/schemas/ApiResponse")))))
                    .addApiResponse("400", new ApiResponse()
                        .description("Missing file parameter or invalid path"))
                    .addApiResponse("403", new ApiResponse()
                        .description("Security violation - file outside server directory"))
                    .addApiResponse("404", new ApiResponse()
                        .description("Server not found or file does not exist"))));
    }

    private static PathItem createFileDownloadPath() {
        Parameter serverIdParam = new Parameter()
            .name("id")
            .in("path")
            .description("Server ID")
            .required(true)
            .style(Parameter.StyleEnum.SIMPLE)
            .explode(false)
            .schema(new StringSchema());

        Parameter fileParam = new Parameter()
            .name("file")
            .in("query")
            .description("Path to the file within server directory to download")
            .required(true)
            .schema(new StringSchema().example("world/level.dat"));

        return new PathItem()
            .get(new Operation()
                .operationId("downloadFile")
                .summary("Download file")
                .description("Downloads a file from the server's working directory as an attachment")
                .addTagsItem("Files")
                .addParametersItem(serverIdParam)
                .addParametersItem(fileParam)
                .responses(createStandardResponses()
                    .addApiResponse("200", new ApiResponse()
                        .description("File download")
                        .content(new Content()
                            .addMediaType("application/octet-stream", new MediaType()
                                .schema(new StringSchema().format("binary")))))
                    .addApiResponse("400", new ApiResponse()
                        .description("Missing file parameter or invalid path"))
                    .addApiResponse("403", new ApiResponse()
                        .description("Security violation - file outside server directory"))
                    .addApiResponse("404", new ApiResponse()
                        .description("Server not found or file does not exist"))));
    }

    private static PathItem createFileUploadPath() {
        Parameter serverIdParam = new Parameter()
            .name("id")
            .in("path")
            .description("Server ID")
            .required(true)
            .style(Parameter.StyleEnum.SIMPLE)
            .explode(false)
            .schema(new StringSchema());

        Parameter pathParam = new Parameter()
            .name("path")
            .in("query")
            .description("Path where to upload the file within server directory")
            .required(true)
            .schema(new StringSchema().example("plugins/config.yml"));

        RequestBody requestBody = new RequestBody()
            .description("File content to upload (binary data, max 8GB)")
            .required(true)
            .content(new Content()
                .addMediaType("application/octet-stream", new MediaType()
                    .schema(new StringSchema().format("binary"))));

        return new PathItem()
            .post(new Operation()
                .operationId("uploadFile")
                .summary("Upload file")
                .description("Uploads a file to the server's working directory. Maximum file size is 8GB.")
                .addTagsItem("Files")
                .addParametersItem(serverIdParam)
                .addParametersItem(pathParam)
                .requestBody(requestBody)
                .responses(createStandardResponses()
                    .addApiResponse("200", new ApiResponse()
                        .description("File uploaded successfully")
                        .content(new Content()
                            .addMediaType("application/json", new MediaType()
                                .schema(new ObjectSchema()
                                    .addProperty("status", new StringSchema().example("success"))
                                    .addProperty("data", new ObjectSchema()
                                        .addProperty("path", new StringSchema().example("plugins/config.yml"))
                                        .addProperty("size", new Schema<>().type("integer").format("int64").example(2048L)))
                                    .addProperty("message", new StringSchema().example("File uploaded successfully"))
                                    .addProperty("timestamp", new Schema<>().type("integer").format("int64").example(1752700258719L))))))
                    .addApiResponse("400", new ApiResponse()
                        .description("Missing path parameter, invalid path, or no file data provided"))
                    .addApiResponse("403", new ApiResponse()
                        .description("Security violation - path outside server directory"))
                    .addApiResponse("404", new ApiResponse()
                        .description("Server not found"))
                    .addApiResponse("413", new ApiResponse()
                        .description("File too large (exceeds 8GB limit)"))));
    }

    private static PathItem createMkdirPath() {
        Parameter serverIdParam = new Parameter()
            .name("id")
            .in("path")
            .description("Server ID")
            .required(true)
            .style(Parameter.StyleEnum.SIMPLE)
            .explode(false)
            .schema(new StringSchema());

        RequestBody requestBody = new RequestBody()
            .description("Directory creation request")
            .required(true)
            .content(new Content()
                .addMediaType("application/json", new MediaType()
                    .schema(new ObjectSchema()
                        .addProperty("path", new StringSchema()
                            .description("Path of the directory to create")
                            .example("plugins/new-folder")))));

        return new PathItem()
            .post(new Operation()
                .operationId("createDirectory")
                .summary("Create directory")
                .description("Creates a new directory within the server's working directory")
                .addTagsItem("Files")
                .addParametersItem(serverIdParam)
                .requestBody(requestBody)
                .responses(createStandardResponses()
                    .addApiResponse("200", new ApiResponse()
                        .description("Directory created successfully")
                        .content(new Content()
                            .addMediaType("application/json", new MediaType()
                                .schema(new ObjectSchema()
                                    .addProperty("status", new StringSchema().example("success"))
                                    .addProperty("data", new ObjectSchema()
                                        .addProperty("path", new StringSchema().example("plugins/new-folder")))
                                    .addProperty("message", new StringSchema().example("Directory created successfully"))
                                    .addProperty("timestamp", new Schema<>().type("integer").format("int64").example(1752700258719L))))))
                    .addApiResponse("400", new ApiResponse()
                        .description("Missing path parameter, invalid path, or directory already exists"))
                    .addApiResponse("403", new ApiResponse()
                        .description("Security violation - path outside server directory"))
                    .addApiResponse("404", new ApiResponse()
                        .description("Server not found"))));
    }

    private static PathItem createFileRenamePath() {
        Parameter serverIdParam = new Parameter()
            .name("id")
            .in("path")
            .description("Server ID")
            .required(true)
            .style(Parameter.StyleEnum.SIMPLE)
            .explode(false)
            .schema(new StringSchema());

        return new PathItem()
            .post(new Operation()
                .operationId("renameFile")
                .summary("Rename/move file")
                .description("Renames or moves a file/directory within the server's working directory")
                .addTagsItem("Files")
                .addParametersItem(serverIdParam)
                .requestBody(new RequestBody()
                    .description("Rename operation details")
                    .required(true)
                    .content(new Content()
                        .addMediaType("application/json", new MediaType()
                            .schema(new ObjectSchema()
                                .addProperty("oldPath", new StringSchema()
                                    .description("Current path of the file/directory")
                                    .example("old-config.yml"))
                                .addProperty("newPath", new StringSchema()
                                    .description("New path for the file/directory")
                                    .example("config/server-config.yml"))
                                .required(List.of("oldPath", "newPath"))))))
                .responses(createStandardResponses()
                    .addApiResponse("200", new ApiResponse()
                        .description("File renamed successfully")
                        .content(new Content()
                            .addMediaType("application/json", new MediaType()
                                .schema(new Schema<>().$ref("#/components/schemas/ApiResponse")))))
                    .addApiResponse("400", new ApiResponse()
                        .description("Missing paths or invalid request body"))
                    .addApiResponse("403", new ApiResponse()
                        .description("Security violation - paths outside server directory"))
                    .addApiResponse("404", new ApiResponse()
                        .description("Server not found or source file does not exist"))
                    .addApiResponse("409", new ApiResponse()
                        .description("Destination already exists"))));
    }

    private static PathItem createServerActionPath(String action) {
        Parameter serverIdParam = new Parameter()
            .name("id")
            .in("path")
            .description("Server ID")
            .required(true)
            .style(Parameter.StyleEnum.SIMPLE)
            .explode(false)
            .schema(new StringSchema());

        return new PathItem()
            .post(new Operation()
                .operationId(action + "Server")
                .summary(action.substring(0, 1).toUpperCase() + action.substring(1) + " server")
                .description(action.substring(0, 1).toUpperCase() + action.substring(1) + "s the specified server")
                .addTagsItem("Servers")
                .addParametersItem(serverIdParam)
                .responses(createStandardResponses()
                    .addApiResponse("200", new ApiResponse()
                        .description("Server " + action + " initiated")
                        .content(new Content()
                            .addMediaType("application/json", new MediaType()
                                .schema(new Schema<>().$ref("#/components/schemas/ApiResponse")))))
                    .addApiResponse("404", new ApiResponse()
                        .description("Server not found"))));
    }

    private static PathItem createServerCommandPath() {
        Parameter serverIdParam = new Parameter()
            .name("id")
            .in("path")
            .description("Server ID")
            .required(true)
            .style(Parameter.StyleEnum.SIMPLE)
            .explode(false)
            .schema(new StringSchema());

        RequestBody requestBody = new RequestBody()
            .description("Command to execute")
            .required(true)
            .content(new Content()
                .addMediaType("application/json", new MediaType()
                    .schema(new Schema<>().$ref("#/components/schemas/ServerCommandRequest"))));

        return new PathItem()
            .post(new Operation()
                .operationId("executeServerCommand")
                .summary("Execute command on server")
                .description("Executes a command on the specified server via network communication")
                .addTagsItem("Servers")
                .addParametersItem(serverIdParam)
                .requestBody(requestBody)
                .responses(createStandardResponses()
                    .addApiResponse("200", new ApiResponse()
                        .description("Command executed successfully")
                        .content(new Content()
                            .addMediaType("application/json", new MediaType()
                                .schema(new Schema<>().$ref("#/components/schemas/ApiResponse")))))
                    .addApiResponse("400", new ApiResponse()
                        .description("Invalid request - missing or empty command"))
                    .addApiResponse("404", new ApiResponse()
                        .description("Server not found"))));
    }

    private static PathItem createGroupsPath() {
        return new PathItem()
            .get(new Operation()
                .operationId("getGroups")
                .summary("List scaling groups")
                .description("Returns detailed information about all available scaling groups including their configuration, current server count, and player statistics")
                .addTagsItem("Scaling")
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .responses(createStandardResponses()
                    .addApiResponse("200", new ApiResponse()
                        .description("List of scaling groups with detailed information")
                        .content(new Content()
                            .addMediaType("application/json", new MediaType()
                                .schema(new Schema<>().$ref("#/components/schemas/GroupListResponse")))))));
    }

    private static PathItem createGroupByNamePath() {
        Parameter groupNameParam = new Parameter()
            .name("name")
            .in("path")
            .description("Group name (case-insensitive)")
            .required(true)
            .style(Parameter.StyleEnum.SIMPLE)
            .explode(false)
            .schema(new StringSchema());

        return new PathItem()
            .get(new Operation()
                .operationId("getGroup")
                .summary("Get group details")
                .description("Returns detailed information about a specific scaling group by name (case-insensitive).")
                .addTagsItem("Scaling")
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .addParametersItem(groupNameParam)
                .responses(createStandardResponses()
                    .addApiResponse("200", new ApiResponse()
                        .description("Group details")
                        .content(new Content()
                            .addMediaType("application/json", new MediaType()
                                .schema(new Schema<>().$ref("#/components/schemas/GroupResponse")))))
                    .addApiResponse("404", new ApiResponse()
                        .description("Group not found"))));
    }

    private static PathItem createScalePath() {
        Parameter groupParam = new Parameter()
            .name("group")
            .in("path")
            .description("Group name")
            .required(true)
            .style(Parameter.StyleEnum.SIMPLE)
            .explode(false)
            .schema(new StringSchema());

        return new PathItem()
            .post(new Operation()
                .operationId("scaleGroup")
                .summary("Scale group")
                .description("Manually scales a group up or down")
                .addTagsItem("Scaling")
                .addParametersItem(groupParam)
                .requestBody(new RequestBody()
                    .description("Scaling parameters")
                    .required(true)
                    .content(new Content()
                        .addMediaType("application/json", new MediaType()
                            .schema(new Schema<>().$ref("#/components/schemas/ScaleRequest")))))
                .responses(createStandardResponses()
                    .addApiResponse("200", new ApiResponse()
                        .description("Scaling initiated")
                        .content(new Content()
                            .addMediaType("application/json", new MediaType()
                                .schema(new Schema<>().$ref("#/components/schemas/ApiResponse")))))
                    .addApiResponse("404", new ApiResponse()
                        .description("Group not found"))));
    }

    private static PathItem createScalingPath() {
        return new PathItem()
            .get(new Operation()
                .operationId("getScaling")
                .summary("Get scaling configuration")
                .description("Returns the current scaling configuration for all groups")
                .addTagsItem("Scaling")
                .responses(createStandardResponses()
                    .addApiResponse("200", new ApiResponse()
                        .description("Scaling configuration")
                        .content(new Content()
                            .addMediaType("application/json", new MediaType()
                                .schema(new Schema<>().$ref("#/components/schemas/ScalingResponse")))))));
    }

    private static PathItem createMetricsPath() {
        return new PathItem()
            .get(new Operation()
                .operationId("getMetrics")
                .summary("Get system metrics")
                .description("Returns system-wide metrics and statistics")
                .addTagsItem("Monitoring")
                .responses(createStandardResponses()
                    .addApiResponse("200", new ApiResponse()
                        .description("System metrics")
                        .content(new Content()
                            .addMediaType("application/json", new MediaType()
                                .schema(new Schema<>().$ref("#/components/schemas/MetricsResponse")))))));
    }

    private static PathItem createUtilizationPath() {
        return new PathItem()
            .get(new Operation()
                .operationId("getUtilization")
                .summary("Get system utilization")
                .description("Returns current system resource utilization including memory, disk, and bandwidth")
                .addTagsItem("Monitoring")
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .responses(createStandardResponses()
                    .addApiResponse("200", new ApiResponse()
                        .description("System utilization information")
                        .content(new Content()
                            .addMediaType("application/json", new MediaType()
                                .schema(new Schema<>().$ref("#/components/schemas/UtilizationResponse")))))));
    }

    private static PathItem createWebSocketPath() {
        Parameter serverIdParam = new Parameter()
            .name("id")
            .in("path")
            .description("Server ID")
            .required(true)
            .style(Parameter.StyleEnum.SIMPLE)
            .explode(false)
            .schema(new StringSchema());

        return new PathItem()
            .get(new Operation()
                .operationId("connectWebSocket")
                .summary("Server WebSocket connection")
                .description("Establishes a WebSocket connection for real-time communication with a specific server. " +
                    "Supports live log streaming, server control, and real-time statistics. " +
                    "\n\n**Outbound Message Types (Client to Server):**\n" +
                    "- `auth` - Authenticate with token: `{\"type\": \"auth\", \"token\": \"your-api-key\"}`\n" +
                    "- `subscribe` - Subscribe to streams: `{\"type\": \"subscribe\", \"streams\": [\"logs\", \"stats\", \"events\"], \"targets\": [\"serverId\", \"global\"]}`\n" +
                    "- `server-start` - Start server: `{\"type\": \"server-start\"}`\n" +
                    "- `server-stop` - Stop server: `{\"type\": \"server-stop\"}`\n" +
                    "- `server-restart` - Restart server: `{\"type\": \"server-restart\"}`\n" +
                    "- `server-command` - Execute command: `{\"type\": \"server-command\", \"command\": \"say Hello!\", \"id\": \"cmd123\"}`\n" +
                    "- `server-create` - Create new server: `{\"type\": \"server-create\", \"group\": \"Lobby\"}`\n" +
                    "- `server-remove` - Remove server: `{\"type\": \"server-remove\"}`\n" +
                    "- `scale` - Scale group: `{\"type\": \"scale\", \"group\": \"Lobby\", \"direction\": \"up\"}`\n" +
                    "- `get-logs-history` - Get log history: `{\"type\": \"get-logs-history\", \"serverId\": \"abc123\", \"lines\": 20}`\n" +
                    "\n**Inbound Message Types (Server to Client):**\n" +
                    "- `log` - Live log line: `{\"type\": \"log\", \"message\": \"[INFO] Player joined\", \"serverId\": \"abc123\", \"timestamp\": 1752700258719}`\n" +
                    "- `stats` - Server statistics: `{\"type\": \"stats\", \"data\": {\"cpu\": 15.2, \"ram\": {\"used\": 512, \"total\": 1024, \"percentage\": 50}, \"disk\": {...}, \"network\": {...}, \"players\": 5, \"maxPlayers\": 75, \"status\": \"RUNNING\"}, \"timestamp\": 1752700258719}`\n" +
                    "- `server-info` - Server details on connect: `{\"type\": \"server-info\", \"data\": {\"serverId\": \"abc123\", \"name\": \"lobby-1\", \"group\": \"Lobby\", \"status\": \"RUNNING\", ...}, \"timestamp\": 1752700258719}`\n" +
                    "- `command-result` - Command execution result: `{\"type\": \"command-result\", \"commandId\": \"cmd123\", \"message\": \"Command executed successfully\", \"timestamp\": 1752700258719}`\n" +
                    "- `event` - Server events: `{\"type\": \"event\", \"message\": \"restart-started\", \"serverId\": \"abc123\", \"timestamp\": 1752700258719}`\n" +
                    "- `logs-history` - Historical logs: `{\"type\": \"logs-history\", \"data\": {\"logs\": [\"log1\", \"log2\"]}, \"serverId\": \"abc123\", \"timestamp\": 1752700258719}`\n" +
                    "- `subscribe-result` - Subscription confirmation: `{\"type\": \"subscribe-result\", \"message\": \"Subscriptions updated\", \"timestamp\": 1752700258719}`\n" +
                    "- `auth-result` - Authentication result: `{\"type\": \"auth-result\", \"message\": \"Authentication successful\", \"timestamp\": 1752700258719}`\n" +
                    "- `auth-challenge` - Periodic re-auth: `{\"type\": \"auth-challenge\", \"timestamp\": 1752700258719}`\n" +
                    "- `error` - Error message: `{\"type\": \"error\", \"message\": \"Command failed\", \"timestamp\": 1752700258719}`")
                .addTagsItem("WebSocket")
                .addParametersItem(serverIdParam)
                .addParametersItem(new Parameter()
                    .name("auth")
                    .in("query")
                    .description("Bearer token for authentication (alternative to Authorization header)")
                    .required(false)
                    .style(Parameter.StyleEnum.FORM)
                    .explode(true)
                    .schema(new StringSchema()))
                .responses(new ApiResponses()
                    .addApiResponse("101", new ApiResponse()
                        .description("WebSocket connection established"))
                    .addApiResponse("401", new ApiResponse()
                        .description("Unauthorized - invalid or missing API key"))
                    .addApiResponse("404", new ApiResponse()
                        .description("Server not found"))));
    }

    private static ApiResponses createStandardResponses() {
        return new ApiResponses()
            .addApiResponse("401", new ApiResponse()
                .description("Unauthorized - invalid or missing API key"))
            .addApiResponse("500", new ApiResponse()
                .description("Internal server error"));
    }

    private static Operation createOperation(String operationId, String summary, String description, String tag) {
        return new Operation()
            .operationId(operationId)
            .summary(summary)
            .description(description)
            .addTagsItem(tag)
            .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }

    private static Components createComponents() {
        Components components = new Components();
        
        SecurityScheme bearerAuth = new SecurityScheme()
            .type(SecurityScheme.Type.HTTP)
            .scheme("bearer")
            .bearerFormat("JWT")
            .description("Bearer token authentication using API key");
        
        components.addSecuritySchemes("bearerAuth", bearerAuth);
        components.setSchemas(createSchemas());
        
        return components;
    }

    private static Map<String, Schema> createSchemas() {
        return Map.ofEntries(
            Map.entry("ApiResponse", new ObjectSchema()
                .addProperty("status", new StringSchema().example("success"))
                .addProperty("data", new ObjectSchema().nullable(true).example(null))
                .addProperty("message", new StringSchema().nullable(true).example("Server creation initiated"))
                .addProperty("timestamp", new Schema<>().type("integer").format("int64").example(1752700258719L))),
            
            Map.entry("StatusResponse", new ObjectSchema()
                .addProperty("status", new StringSchema().example("success"))
                .addProperty("data", new ObjectSchema()
                    .addProperty("running", new Schema<>().type("boolean").example(true))
                    .addProperty("debugMode", new Schema<>().type("boolean").example(false))
                    .addProperty("uptime", new Schema<>().type("integer").format("int64").example(1752700258719L)))
                .addProperty("timestamp", new Schema<>().type("integer").format("int64").example(1752700258719L))),
            
            Map.entry("ServerInfo", new ObjectSchema()
                .addProperty("status", new StringSchema().example("RUNNING").description("Current server status"))
                .addProperty("onlinePlayers", new Schema<>().type("integer").example(5).description("Current number of online players"))
                .addProperty("maxPlayers", new Schema<>().type("integer").example(75).description("Maximum number of players allowed"))
                .addProperty("onlinePlayerNames", new ArraySchema().items(new StringSchema()).description("List of online player names"))),

            Map.entry("AtlasServer", new ObjectSchema()
                .addProperty("serverId", new StringSchema().example("550e8400-e29b-41d4-a716-446655440000").description("Unique server identifier"))
                .addProperty("name", new StringSchema().example("lobby-1").description("Human-readable server name"))
                .addProperty("group", new StringSchema().example("Lobby").description("Server group name"))
                .addProperty("workingDirectory", new StringSchema().example("servers/Lobby/lobby-1#550e8400-e29b-41d4-a716-446655440000").description("Server working directory path"))
                .addProperty("address", new StringSchema().example("172.18.0.10").description("Server IP address"))
                .addProperty("port", new Schema<>().type("integer").example(25565).description("Server port"))
                .addProperty("type", new StringSchema().example("DYNAMIC").description("Server type (DYNAMIC or STATIC)"))
                .addProperty("createdAt", new Schema<>().type("integer").format("int64").example(1752700195234L).description("Server creation timestamp"))
                .addProperty("lastHeartbeat", new Schema<>().type("integer").format("int64").example(1752700258719L).description("Last heartbeat timestamp"))
                .addProperty("serviceProviderId", new StringSchema().example("4d97019952fe1ef1fb2e77a4c20040c0795c7794a5ae9121de0d6cf831a0736b").description("Service provider identifier"))
                .addProperty("shutdown", new Schema<>().type("boolean").example(false).description("Whether server is shutting down"))
                .addProperty("manuallyScaled", new Schema<>().type("boolean").example(true).description("Whether server was manually scaled"))
                .addProperty("serverInfo", new Schema<>().$ref("#/components/schemas/ServerInfo").description("Current server information and status"))),
            
            Map.entry("ServerListResponse", new ObjectSchema()
                .addProperty("status", new StringSchema().example("success"))
                .addProperty("data", new ArraySchema().items(new Schema<>().$ref("#/components/schemas/AtlasServer")))
                .addProperty("timestamp", new Schema<>().type("integer").format("int64").example(1752700258719L))),
            
            Map.entry("ServerResponse", new ObjectSchema()
                .addProperty("status", new StringSchema().example("success"))
                .addProperty("data", new Schema<>().$ref("#/components/schemas/AtlasServer"))
                .addProperty("timestamp", new Schema<>().type("integer").format("int64").example(1752700258719L))),
            
            Map.entry("ServerLogsResponse", new ObjectSchema()
                .addProperty("status", new StringSchema().example("success"))
                .addProperty("data", new ObjectSchema()
                    .addProperty("serverId", new StringSchema().example("server-123"))
                    .addProperty("lines", new Schema<>().type("integer").example(50))
                    .addProperty("logs", new ArraySchema().items(new StringSchema().example("[INFO] Server started successfully"))))
                .addProperty("message", new StringSchema().example("Server logs retrieved"))
                .addProperty("timestamp", new Schema<>().type("integer").format("int64").example(1752700258719L))),
            
            Map.entry("CreateServerRequest", new ObjectSchema()
                .addProperty("group", new StringSchema().example("Lobby").description("Group name"))
                .addProperty("count", new Schema<>().type("integer")._default(1).example(2).description("Number of servers to create"))
                .required(List.of("group"))),
            
            Map.entry("ScaleRequest", new ObjectSchema()
                .addProperty("direction", new StringSchema().example("up").description("Scaling direction: 'up' or 'down'"))
                .addProperty("count", new Schema<>().type("integer")._default(1).example(1).description("Number of servers to scale"))
                .required(List.of("direction"))),
            
            Map.entry("ServerCommandRequest", new ObjectSchema()
                .addProperty("command", new StringSchema().example("say Hello World!").description("Command to execute on the server"))
                .required(List.of("command"))),
            
            Map.entry("GroupListResponse", new ObjectSchema()
                .addProperty("status", new StringSchema().example("success"))
                .addProperty("data", new ArraySchema().items(new Schema<>().$ref("#/components/schemas/GroupInfo")))
                .addProperty("timestamp", new Schema<>().type("integer").format("int64").example(1752700258719L))),
            
            Map.entry("GroupInfo", new ObjectSchema()
                .addProperty("name", new StringSchema().example("Lobby").description("Group identifier"))
                .addProperty("displayName", new StringSchema().example("Lobby Servers").description("Human-readable group name"))
                .addProperty("priority", new Schema<>().type("integer").example(100).description("Group priority (higher values have higher priority)"))
                .addProperty("type", new StringSchema().example("MINECRAFT").description("Server type"))
                .addProperty("scalerType", new StringSchema().example("NormalScaler").description("Type of scaler implementation"))
                .addProperty("minServers", new Schema<>().type("integer").example(1).description("Minimum number of servers to maintain"))
                .addProperty("maxServers", new Schema<>().type("integer").example(10).description("Maximum number of servers allowed"))
                .addProperty("currentServers", new Schema<>().type("integer").example(3).description("Current number of servers in the group"))
                .addProperty("onlineServers", new Schema<>().type("integer").example(3).description("Number of servers currently running"))
                .addProperty("totalPlayers", new Schema<>().type("integer").example(45).description("Total players across all servers in the group"))
                .addProperty("totalCapacity", new Schema<>().type("integer").example(225).description("Total player capacity of running servers"))
                .addProperty("templates", new ArraySchema().items(new StringSchema()).example(List.of("lobby-template")).description("Available templates for this group"))
                .addProperty("scaling", new ObjectSchema()
                    .addProperty("type", new StringSchema().example("AUTO").description("Scaling type"))
                    .addProperty("scaleUpThreshold", new Schema<>().type("number").format("double").example(0.75).description("Utilization threshold to trigger scale up"))
                    .addProperty("scaleDownThreshold", new Schema<>().type("number").format("double").example(0.25).description("Utilization threshold to trigger scale down"))
                    .nullable(true)
                    .description("Scaling configuration (if available)"))),
            
            Map.entry("GroupResponse", new ObjectSchema()
                .addProperty("status", new StringSchema().example("success"))
                .addProperty("data", new Schema<>().$ref("#/components/schemas/GroupInfo"))
                .addProperty("timestamp", new Schema<>().type("integer").format("int64").example(1752700258719L))),
            
            Map.entry("ScalingResponse", new ObjectSchema()
                .addProperty("status", new StringSchema().example("success"))
                .addProperty("data", new ObjectSchema().additionalProperties(new ObjectSchema()
                    .addProperty("group", new StringSchema().example("Lobby"))
                    .addProperty("type", new StringSchema().example("NormalScaler"))))
                .addProperty("timestamp", new Schema<>().type("integer").format("int64").example(1752700258719L))),
            
            Map.entry("MetricsResponse", new ObjectSchema()
                .addProperty("status", new StringSchema().example("success"))
                .addProperty("data", new ObjectSchema()
                    .addProperty("totalServers", new Schema<>().type("integer").example(3))
                    .addProperty("totalPlayers", new Schema<>().type("integer").example(47))
                    .addProperty("serversByStatus", new ObjectSchema().additionalProperties(new Schema<>().type("integer"))))
                .addProperty("timestamp", new Schema<>().type("integer").format("int64").example(1752700258719L))),
            
            Map.entry("ServerCountResponse", new ObjectSchema()
                .addProperty("status", new StringSchema().example("success"))
                .addProperty("data", new ObjectSchema()
                    .addProperty("total", new Schema<>().type("integer").example(10).description("Total number of servers"))
                    .addProperty("byStatus", new ObjectSchema()
                        .additionalProperties(new Schema<>().type("integer"))
                        .description("Server count grouped by status"))
                    .addProperty("byGroup", new ObjectSchema()
                        .additionalProperties(new Schema<>().type("integer"))
                        .description("Server count grouped by group name")))
                .addProperty("timestamp", new Schema<>().type("integer").format("int64").example(1752700258719L))),
            
            Map.entry("PlayerCountResponse", new ObjectSchema()
                .addProperty("status", new StringSchema().example("success"))
                .addProperty("data", new ObjectSchema()
                    .addProperty("total", new Schema<>().type("integer").example(147).description("Total number of players online"))
                    .addProperty("capacity", new Schema<>().type("integer").example(300).description("Total server capacity"))
                    .addProperty("percentage", new Schema<>().type("number").format("double").example(49.0).description("Percentage of capacity used"))
                    .addProperty("byGroup", new ObjectSchema()
                        .additionalProperties(new Schema<>().type("integer"))
                        .description("Player count grouped by server group"))
                    .addProperty("byStatus", new ObjectSchema()
                        .additionalProperties(new Schema<>().type("integer"))
                        .description("Player count grouped by server status")))
                .addProperty("timestamp", new Schema<>().type("integer").format("int64").example(1752700258719L))),
            
            Map.entry("UtilizationResponse", new ObjectSchema()
                .addProperty("status", new StringSchema().example("success"))
                .addProperty("data", new ObjectSchema()
                    .addProperty("cpu", new ObjectSchema()
                        .addProperty("cores", new Schema<>().type("integer").example(8))
                        .addProperty("usage", new Schema<>().type("number").format("double").example(45.5))
                        .addProperty("formatted", new StringSchema().example("45.5%")))
                    .addProperty("memory", new ObjectSchema()
                        .addProperty("used", new Schema<>().type("integer").format("int64").example(25118949376L))
                        .addProperty("total", new Schema<>().type("integer").format("int64").example(33961984000L))
                        .addProperty("percentage", new Schema<>().type("number").format("double").example(74.0))
                        .addProperty("usedFormatted", new StringSchema().example("23.4 GB"))
                        .addProperty("totalFormatted", new StringSchema().example("31.6 GB")))
                    .addProperty("disk", new ObjectSchema()
                        .addProperty("used", new Schema<>().type("integer").format("int64").example(152465104896L))
                        .addProperty("total", new Schema<>().type("integer").format("int64").example(338811232256L))
                        .addProperty("percentage", new Schema<>().type("number").format("double").example(45.0))
                        .addProperty("usedFormatted", new StringSchema().example("142.0 GB"))
                        .addProperty("totalFormatted", new StringSchema().example("315.5 GB")))
                    .addProperty("bandwidth", new ObjectSchema()
                        .addProperty("used", new Schema<>().type("integer").format("int64").example(6871947674L))
                        .addProperty("total", new Schema<>().type("integer").format("int64").example(10737418240L))
                        .addProperty("percentage", new Schema<>().type("number").format("double").example(62.0))
                        .addProperty("receiveRate", new Schema<>().type("number").format("double").example(1073741824.0))
                        .addProperty("sendRate", new Schema<>().type("number").format("double").example(536870912.0))
                        .addProperty("usedFormatted", new StringSchema().example("1.8 GB/s"))
                        .addProperty("totalFormatted", new StringSchema().example("10.0 GB/s"))
                        .addProperty("receiveFormatted", new StringSchema().example("1.0 GB/s"))
                        .addProperty("sendFormatted", new StringSchema().example("512.0 MB/s"))))
                .addProperty("timestamp", new Schema<>().type("integer").format("int64").example(1752700258719L))),
            
            Map.entry("FileInfo", new ObjectSchema()
                .addProperty("name", new StringSchema().example("config.json"))
                .addProperty("mode", new StringSchema().example("rw-r--r--"))
                .addProperty("modeBits", new Schema<>().type("integer").example(644))
                .addProperty("size", new Schema<>().type("integer").format("int64").nullable(true).example(1024L))
                .addProperty("isFile", new Schema<>().type("boolean").example(true))
                .addProperty("isSymlink", new Schema<>().type("boolean").example(false))
                .addProperty("mimeType", new StringSchema().example("application/json"))
                .addProperty("createdAt", new StringSchema().format("date-time").example("2025-01-16T16:45:30Z"))
                .addProperty("modifiedAt", new StringSchema().format("date-time").example("2025-01-20T14:45:30Z"))),
            
            Map.entry("FileListResponse", new ObjectSchema()
                .addProperty("status", new StringSchema().example("success"))
                .addProperty("data", new ObjectSchema()
                    .addProperty("path", new StringSchema().example("/plugins"))
                    .addProperty("files", new ArraySchema()
                        .items(new Schema<>().$ref("#/components/schemas/FileInfo"))))
                .addProperty("timestamp", new Schema<>().type("integer").format("int64").example(1752700258719L)))
        );
    }
}