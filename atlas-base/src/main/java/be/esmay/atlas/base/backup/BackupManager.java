package be.esmay.atlas.base.backup;

import be.esmay.atlas.base.AtlasBase;
import be.esmay.atlas.base.config.impl.AtlasConfig;
import be.esmay.atlas.base.config.impl.ScalerConfig;
import be.esmay.atlas.base.utils.Logger;
import be.esmay.atlas.common.models.AtlasServer;
import lombok.RequiredArgsConstructor;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream;
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorOutputStream;

public final class BackupManager {

    private static final String BACKUPS_DIR = "backups";
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    
    private S3BackupUploader s3Uploader;

    private S3BackupUploader getS3Uploader() {
        if (this.s3Uploader == null) {
            AtlasConfig.S3 s3Config = AtlasBase.getInstance().getConfigManager().getAtlasConfig().getAtlas().getS3();
            this.s3Uploader = new S3BackupUploader(s3Config);
        }
        return this.s3Uploader;
    }

    public CompletableFuture<Void> executeServerBackup(AtlasServer server, ScalerConfig.BackupAction backupAction, String jobName) {
        return CompletableFuture.runAsync(() -> {
            Logger.debug("Backup started for server: {} (job: {})", server.getName(), jobName);
            try {
                String serverDirectory = server.getWorkingDirectory();
                if (serverDirectory == null || serverDirectory.isEmpty()) {
                    Logger.error("Server {} has no working directory configured for backup", server.getName());
                    return;
                }

                Path serverPath = Paths.get(serverDirectory);
                if (!Files.exists(serverPath)) {
                    Logger.error("Server directory does not exist: {}", serverPath);
                    return;
                }

                String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
                String date = LocalDateTime.now().format(DATE_FORMAT);

                String resolvedPath = this.resolveVariables(backupAction.getBackupPath(), server, timestamp, date);
                String resolvedFilename = this.resolveVariables(backupAction.getFilenamePattern(), server, timestamp, date);

                String fileExtension = this.getFileExtension(backupAction.getCompressionFormat());
                String finalFilename = resolvedFilename + fileExtension;

                Path localBackupDir = Paths.get(BACKUPS_DIR).resolve(resolvedPath);
                Files.createDirectories(localBackupDir);

                Path backupFile = localBackupDir.resolve(finalFilename);

                this.createCompressedBackup(serverPath, backupFile, backupAction);

                Logger.info("Created backup for server {}: {}", server.getName(), backupFile);

                this.cleanupLocalBackups(localBackupDir, server.getServerId(), backupAction.getLocalRetention());

                if (backupAction.isUploadToS3()) {
                    String s3Key = resolvedPath + "/" + finalFilename;
                    CompletableFuture<Void> uploadFuture = this.getS3Uploader().uploadFile(backupFile, backupAction.getS3Bucket(), s3Key);
                    uploadFuture.join();
                    this.getS3Uploader().cleanupOldBackups(backupAction.getS3Bucket(), resolvedPath, server.getServerId(), backupAction.getS3Retention());
                }

                Logger.debug("Backup completed successfully for server: {} (job: {})", server.getName(), jobName);

            } catch (Exception e) {
                Logger.error("Failed to backup server {}: {}", server.getName(), e.getMessage(), e);
            }
        });
    }

    public CompletableFuture<Void> executeGroupBackup(String groupName, List<AtlasServer> servers, ScalerConfig.BackupAction backupAction, String jobName) {
        return CompletableFuture.runAsync(() -> {
            Logger.debug("Group backup started for group: {} (job: {})", groupName, jobName);
            try {
                String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
                String date = LocalDateTime.now().format(DATE_FORMAT);

                String resolvedPath = this.resolveGroupVariables(backupAction.getBackupPath(), groupName, timestamp, date);
                String resolvedFilename = this.resolveGroupVariables(backupAction.getFilenamePattern(), groupName, timestamp, date);

                String fileExtension = this.getFileExtension(backupAction.getCompressionFormat());
                String finalFilename = resolvedFilename + fileExtension;

                Path localBackupDir = Paths.get(BACKUPS_DIR).resolve(resolvedPath);
                Files.createDirectories(localBackupDir);

                Path backupFile = localBackupDir.resolve(finalFilename);

                this.createGroupCompressedBackup(servers, backupFile, backupAction);

                Logger.info("Created group backup for {}: {}", groupName, backupFile);

                this.cleanupLocalBackups(localBackupDir, groupName, backupAction.getLocalRetention());

                if (backupAction.isUploadToS3()) {
                    String s3Key = resolvedPath + "/" + finalFilename;
                    CompletableFuture<Void> uploadFuture = this.getS3Uploader().uploadFile(backupFile, backupAction.getS3Bucket(), s3Key);
                    uploadFuture.join();
                    this.getS3Uploader().cleanupOldBackups(backupAction.getS3Bucket(), resolvedPath, groupName, backupAction.getS3Retention());
                }

                Logger.debug("Group backup completed successfully for group: {} (job: {})", groupName, jobName);

            } catch (Exception e) {
                Logger.error("Failed to backup group {}: {}", groupName, e.getMessage(), e);
            }
        });
    }

    private String resolveVariables(String template, AtlasServer server, String timestamp, String date) {
        return template
                .replace("{server-id}", server.getServerId())
                .replace("{server-name}", server.getName())
                .replace("{group}", server.getGroup())
                .replace("{timestamp}", timestamp)
                .replace("{date}", date);
    }

    private String resolveGroupVariables(String template, String groupName, String timestamp, String date) {
        return template
                .replace("{group}", groupName)
                .replace("{timestamp}", timestamp)
                .replace("{date}", date)
                .replace("{server-id}", "group")
                .replace("{server-name}", groupName);
    }

    private String getFileExtension(String compressionFormat) {
        return switch (compressionFormat.toLowerCase()) {
            case "tar.gz" -> ".tar.gz";
            case "tar.xz" -> ".tar.xz";
            case "tar.zst" -> ".tar.zst";
            case "tar.lz4" -> ".tar.lz4";
            default -> ".tar.gz";
        };
    }

    private void createCompressedBackup(Path sourcePath, Path targetFile, ScalerConfig.BackupAction backupAction) throws IOException {
        String format = backupAction.getCompressionFormat().toLowerCase();
        int level = backupAction.getCompressionLevel();

        try (FileOutputStream fos = new FileOutputStream(targetFile.toFile());
             BufferedOutputStream bos = new BufferedOutputStream(fos)) {

            switch (format) {
                case "tar.gz" -> {
                    GZIPOutputStream gzos = new GZIPOutputStream(bos);
                    this.createTarArchive(sourcePath, gzos, backupAction);
                    gzos.finish();
                }
                case "tar.xz" -> {
                    XZCompressorOutputStream xzos = new XZCompressorOutputStream(bos, level);
                    this.createTarArchive(sourcePath, xzos, backupAction);
                    xzos.finish();
                }
                case "tar.zst" -> {
                    ZstdCompressorOutputStream zstdos = new ZstdCompressorOutputStream(bos, level);
                    this.createTarArchive(sourcePath, zstdos, backupAction);
                    zstdos.close();
                }
                case "tar.lz4" -> {
                    FramedLZ4CompressorOutputStream lz4os = new FramedLZ4CompressorOutputStream(bos);
                    this.createTarArchive(sourcePath, lz4os, backupAction);
                    lz4os.finish();
                }
                default -> {
                    GZIPOutputStream gzos = new GZIPOutputStream(bos);
                    this.createTarArchive(sourcePath, gzos, backupAction);
                    gzos.finish();
                }
            }
        }
    }

    private void createGroupCompressedBackup(List<AtlasServer> servers, Path targetFile, ScalerConfig.BackupAction backupAction) throws IOException {
        String format = backupAction.getCompressionFormat().toLowerCase();
        int level = backupAction.getCompressionLevel();

        try (FileOutputStream fos = new FileOutputStream(targetFile.toFile());
             BufferedOutputStream bos = new BufferedOutputStream(fos)) {

            switch (format) {
                case "tar.xz" -> {
                    XZCompressorOutputStream xzos = new XZCompressorOutputStream(bos, level);
                    this.createGroupTarArchive(servers, xzos, backupAction);
                    xzos.finish();
                }
                case "tar.zst" -> {
                    ZstdCompressorOutputStream zstdos = new ZstdCompressorOutputStream(bos, level);
                    this.createGroupTarArchive(servers, zstdos, backupAction);
                    zstdos.close();
                }
                case "tar.lz4" -> {
                    FramedLZ4CompressorOutputStream lz4os = new FramedLZ4CompressorOutputStream(bos);
                    this.createGroupTarArchive(servers, lz4os, backupAction);
                    lz4os.finish();
                }
                default -> {
                    GZIPOutputStream gzos = new GZIPOutputStream(bos);
                    this.createGroupTarArchive(servers, gzos, backupAction);
                    gzos.finish();
                }
            }
        }
    }

    private void createTarArchive(Path sourcePath, java.io.OutputStream outputStream, ScalerConfig.BackupAction backupAction) throws IOException {
        try (TarArchiveOutputStream taos = new TarArchiveOutputStream(outputStream)) {
            taos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);

            Files.walk(sourcePath)
                    .filter(Files::isRegularFile)
                    .filter(path -> this.shouldIncludeFile(path, sourcePath, backupAction))
                    .forEach(path -> {
                        try {
                            Path relativePath = sourcePath.relativize(path);
                            TarArchiveEntry entry = new TarArchiveEntry(path.toFile(), relativePath.toString());
                            taos.putArchiveEntry(entry);

                            try (FileInputStream fis = new FileInputStream(path.toFile());
                                 BufferedInputStream bis = new BufferedInputStream(fis)) {
                                bis.transferTo(taos);
                            }
                            taos.closeArchiveEntry();
                        } catch (IOException e) {
                            Logger.warn("Failed to add file to backup: {}", path, e);
                        }
                    });
        }
    }

    private void createGroupTarArchive(List<AtlasServer> servers, java.io.OutputStream outputStream, ScalerConfig.BackupAction backupAction) throws IOException {
        try (TarArchiveOutputStream taos = new TarArchiveOutputStream(outputStream)) {
            taos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);

            for (AtlasServer server : servers) {
                String serverDirectory = server.getWorkingDirectory();
                if (serverDirectory == null || serverDirectory.isEmpty()) {
                    Logger.warn("Server {} has no working directory, skipping in group backup", server.getName());
                    continue;
                }

                Path serverPath = Paths.get(serverDirectory);
                if (!Files.exists(serverPath)) {
                    Logger.warn("Server directory does not exist: {}, skipping", serverPath);
                    continue;
                }

                Files.walk(serverPath)
                        .filter(Files::isRegularFile)
                        .filter(path -> this.shouldIncludeFile(path, serverPath, backupAction))
                        .forEach(path -> {
                            try {
                                Path relativePath = Paths.get(server.getName()).resolve(serverPath.relativize(path));
                                TarArchiveEntry entry = new TarArchiveEntry(path.toFile(), relativePath.toString());
                                taos.putArchiveEntry(entry);

                                try (FileInputStream fis = new FileInputStream(path.toFile());
                                     BufferedInputStream bis = new BufferedInputStream(fis)) {
                                    bis.transferTo(taos);
                                }
                                taos.closeArchiveEntry();
                            } catch (IOException e) {
                                Logger.warn("Failed to add file to group backup: {}", path, e);
                            }
                        });
            }
        }
    }

    private boolean shouldIncludeFile(Path filePath, Path basePath, ScalerConfig.BackupAction backupAction) {
        Path relativePath = basePath.relativize(filePath);
        String relativePathString = relativePath.toString().replace('\\', '/');

        List<String> includePatterns = backupAction.getIncludePatterns();
        if (includePatterns != null && !includePatterns.isEmpty()) {
            boolean matches = includePatterns.stream()
                    .anyMatch(pattern -> this.matchesGlobPattern(relativePathString, pattern));
            if (!matches) {
                return false;
            }
        }

        List<String> excludePatterns = backupAction.getExcludePatterns();
        if (excludePatterns != null && !excludePatterns.isEmpty()) {
            boolean matches = excludePatterns.stream()
                    .anyMatch(pattern -> this.matchesGlobPattern(relativePathString, pattern));
            if (matches) {
                return false;
            }
        }

        return true;
    }

    private boolean matchesGlobPattern(String path, String pattern) {
        String regex = pattern
                .replace(".", "\\.")
                .replace("**", "DOUBLE_STAR")
                .replace("*", "[^/]*")
                .replace("?", "[^/]")
                .replace("DOUBLE_STAR", ".*");

        if (!regex.startsWith("^")) {
            regex = "^" + regex;
        }
        if (!regex.endsWith("$")) {
            regex = regex + "$";
        }
        
        return Pattern.matches(regex, path);
    }

    private void cleanupLocalBackups(Path backupDir, String prefix, int retention) {
        try {
            if (retention <= 0) {
                return;
            }

            java.io.File[] backupFiles = backupDir.toFile().listFiles((dir, name) -> name.startsWith(prefix + "_"));
            if (backupFiles == null) {
                return;
            }

            Arrays.sort(backupFiles, Comparator.comparingLong(java.io.File::lastModified).reversed());

            for (int i = retention; i < backupFiles.length; i++) {
                java.io.File fileToDelete = backupFiles[i];
                boolean deleted = fileToDelete.delete();
                if (deleted) {
                    Logger.info("Deleted old local backup: {}", fileToDelete.getName());
                } else {
                    Logger.warn("Failed to delete old local backup: {}", fileToDelete.getName());
                }
            }

        } catch (Exception e) {
            Logger.error("Failed to cleanup local backups", e);
        }
    }

    public void shutdown() {
        if (this.s3Uploader != null) {
            this.s3Uploader.shutdown();
        }
    }
}