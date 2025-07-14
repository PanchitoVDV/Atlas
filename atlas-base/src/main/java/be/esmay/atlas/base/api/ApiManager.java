package be.esmay.atlas.base.api;

import be.esmay.atlas.base.AtlasBase;
import be.esmay.atlas.base.config.impl.AtlasConfig;
import be.esmay.atlas.base.utils.Logger;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import lombok.Getter;

@Getter
public final class ApiManager {

    private final Vertx vertx;
    private AtlasConfig.Atlas config;
    private HttpServer httpServer;
    private Router router;
    private ApiAuthHandler authHandler;
    private WebSocketManager webSocketManager;
    private ApiRoutes apiRoutes;
    private ApiDocumentation apiDocumentation;

    private volatile boolean running = false;

    private final AtlasBase atlasBase;

    public ApiManager(AtlasBase atlasBase) {
        this.atlasBase = atlasBase;
        this.vertx = Vertx.vertx();
    }

    public Future<Void> start() {
        if (this.running) {
            return Future.succeededFuture();
        }

        try {
            this.config = this.atlasBase.getConfigManager().getAtlasConfig().getAtlas();
            this.authHandler = new ApiAuthHandler(this.config.getNetwork().getApiKey());
            this.webSocketManager = new WebSocketManager(this.authHandler);
            this.router = Router.router(this.vertx);
            this.apiRoutes = new ApiRoutes(this.router, this.authHandler);
            this.apiDocumentation = new ApiDocumentation(this.router);

            this.setupRoutes();
            
            this.httpServer = this.vertx.createHttpServer();
            
            return this.httpServer
                .requestHandler(this.router)
                .listen(this.config.getNetwork().getApiPort(), this.config.getNetwork().getApiHost())
                .onSuccess(server -> {
                    this.running = true;
                    Logger.info("API server started on " + this.config.getNetwork().getApiHost() + ":" + this.config.getNetwork().getApiPort());
                })
                .onFailure(throwable -> {
                    Logger.error("Failed to start API server", throwable);
                })
                .mapEmpty();
        } catch (Exception e) {
            Logger.error("Error during API server startup", e);
            return Future.failedFuture(e);
        }
    }

    public Future<Void> stop() {
        if (!this.running) {
            return Future.succeededFuture();
        }

        this.running = false;

        Future<Void> stopWebSocket = this.webSocketManager != null ? 
            this.webSocketManager.stop() : Future.succeededFuture();

        Future<Void> stopServer = this.httpServer != null ? 
            this.httpServer.close() : Future.succeededFuture();

        return Future.all(stopWebSocket, stopServer)
            .onSuccess(v -> Logger.info("API server stopped"))
            .onFailure(throwable -> Logger.error("Error stopping API server", throwable))
            .mapEmpty();
    }

    private void setupRoutes() {
        this.router.route("/api/v1/servers/:id/ws").handler(this.webSocketManager::handleWebSocket);
        this.apiRoutes.setupRoutes();
        this.apiDocumentation.setupDocumentationRoutes();
    }
}