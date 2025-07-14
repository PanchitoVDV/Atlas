package be.esmay.atlas.base.directory;

import be.esmay.atlas.base.utils.Logger;
import be.esmay.atlas.common.enums.ServerType;
import be.esmay.atlas.common.models.ServerInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Stream;

public final class DirectoryManager {

    private static final String SERVERS_DIR = "servers";

    public String createServerDirectory(ServerInfo server) {
        try {
            String directoryPath = this.generateServerDirectoryPath(server);
            Path serverPath = Paths.get(directoryPath);

            Files.createDirectories(serverPath);
            Logger.debug("Created server directory: " + directoryPath);

            return directoryPath;
        } catch (IOException e) {
            Logger.error("Failed to create server directory for " + server.getServerId(), e);
            throw new RuntimeException("Directory creation failed", e);
        }
    }

    public void cleanupServerDirectory(ServerInfo server) {
        if (server.getType() == ServerType.STATIC) {
            Logger.debug("Preserving static server directory: " + server.getServerId());
            return;
        }

        try {
            String directoryPath = this.generateServerDirectoryPath(server);
            Path serverPath = Paths.get(directoryPath);

            if (Files.exists(serverPath)) {
                this.deleteDirectoryRecursively(serverPath);
                Logger.debug("Cleaned up dynamic server directory: " + directoryPath);
            }
        } catch (IOException e) {
            Logger.error("Failed to cleanup directory for " + server.getServerId(), e);
        }
    }

    public boolean directoryExists(ServerInfo server) {
        String directoryPath = this.generateServerDirectoryPath(server);
        return Files.exists(Paths.get(directoryPath));
    }

    public String generateServerDirectoryPath(ServerInfo server) {
        String groupName = server.getGroup();
        String serverName = server.getName();

        if (server.getType() == ServerType.STATIC) {
            return SERVERS_DIR + "/" + groupName + "/" + serverName;
        }

        String serverUuid = server.getServerId();
        return SERVERS_DIR + "/" + groupName + "/" + serverName + "#" + serverUuid;
    }


    public void deleteDirectoryRecursively(Path directory) throws IOException {
        this.makeDirectoryTreeWritable(directory);

        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    Logger.warn("Could not delete: " + path + " - " + e.getMessage());
                }
            });
        }
    }

    private void makeDirectoryTreeWritable(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.forEach(path -> path.toFile().setWritable(true));
        } catch (IOException e) {
            Logger.warn("Failed to make directory tree writable: " + directory + " - " + e.getMessage());
        }
    }

    public boolean isStaticServerIdValid(String serverId) {
        return serverId.matches(".*-\\d+$");
    }


}