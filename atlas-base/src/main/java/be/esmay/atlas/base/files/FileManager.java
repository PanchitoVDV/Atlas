package be.esmay.atlas.base.files;

import be.esmay.atlas.base.api.dto.FileInfo;
import be.esmay.atlas.base.api.dto.FileListResponse;
import be.esmay.atlas.base.api.dto.UploadSession;
import be.esmay.atlas.base.utils.Logger;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import io.vertx.ext.web.RoutingContext;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class FileManager {
    
    private static final String TEMPLATES_DIR = "templates";
    private final Map<String, UploadSession> uploadSessions = new ConcurrentHashMap<>();

    public FileListResponse listFiles(String workingDirectory, String requestedPath) throws Exception {
        this.validateWorkingDirectory(workingDirectory);
        
        Path serverBasePath = Paths.get(workingDirectory).toAbsolutePath().normalize();
        Path targetPath = this.resolvePath(serverBasePath, requestedPath);
        
        this.validatePathWithinServer(targetPath, serverBasePath);
        
        if (!Files.exists(targetPath)) {
            throw new IllegalArgumentException("Path does not exist: " + requestedPath);
        }
        
        if (!Files.isDirectory(targetPath)) {
            throw new IllegalArgumentException("Path is not a directory: " + requestedPath);
        }

        List<FileInfo> fileInfos = new ArrayList<>();
        
        try (var directoryStream = Files.newDirectoryStream(targetPath)) {
            for (Path filePath : directoryStream) {
                FileInfo fileInfo = this.createFileInfo(filePath);
                fileInfos.add(fileInfo);
            }
        }

        fileInfos.sort((a, b) -> {
            if (a.isFile() != b.isFile()) {
                return a.isFile() ? 1 : -1;
            }
            return a.getName().compareToIgnoreCase(b.getName());
        });

        this.validatePathWithinServer(targetPath, serverBasePath);
        
        return FileListResponse.builder()
            .path(requestedPath)
            .files(fileInfos)
            .build();
    }

    public String readFileContents(String workingDirectory, String requestedFilePath) throws Exception {
        this.validateWorkingDirectory(workingDirectory);
        
        Path serverBasePath = Paths.get(workingDirectory).toAbsolutePath().normalize();
        Path targetPath = this.resolvePath(serverBasePath, requestedFilePath);
        
        this.validatePathWithinServer(targetPath, serverBasePath);
        
        if (!Files.exists(targetPath)) {
            throw new IllegalArgumentException("File does not exist: " + requestedFilePath);
        }
        
        if (!Files.isRegularFile(targetPath)) {
            throw new IllegalArgumentException("Path is not a regular file: " + requestedFilePath);
        }

        try {
            return Files.readString(targetPath);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read file contents: " + e.getMessage(), e);
        }
    }

    public void writeFileContents(String workingDirectory, String requestedFilePath, String content) throws Exception {
        this.validateWorkingDirectory(workingDirectory);
        
        Path serverBasePath = Paths.get(workingDirectory).toAbsolutePath().normalize();
        Path targetPath = this.resolvePath(serverBasePath, requestedFilePath);
        
        this.validatePathWithinServer(targetPath, serverBasePath);

        Path parentDir = targetPath.getParent();
        if (parentDir != null && !Files.exists(parentDir)) {
            Files.createDirectories(parentDir);
        }

        try {
            Files.writeString(targetPath, content);
        } catch (Exception e) {
            throw new RuntimeException("Failed to write file contents: " + e.getMessage(), e);
        }

        this.validatePathWithinServer(targetPath, serverBasePath);
    }

    public void deleteFile(String workingDirectory, String requestedFilePath) throws Exception {
        this.validateWorkingDirectory(workingDirectory);
        
        Path serverBasePath = Paths.get(workingDirectory).toAbsolutePath().normalize();
        Path targetPath = this.resolvePath(serverBasePath, requestedFilePath);
        
        this.validatePathWithinServer(targetPath, serverBasePath);
        
        if (!Files.exists(targetPath)) {
            throw new IllegalArgumentException("File does not exist: " + requestedFilePath);
        }

        try {
            if (Files.isDirectory(targetPath)) {
                Files.walk(targetPath)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to delete: " + path, e);
                        }
                    });
            } else {
                Files.delete(targetPath);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete file/directory: " + e.getMessage(), e);
        }

        this.validatePathWithinServer(targetPath, serverBasePath);
    }

    public void downloadFile(RoutingContext context, String workingDirectory, String requestedFilePath) throws Exception {
        this.validateWorkingDirectory(workingDirectory);
        
        Path serverBasePath = Paths.get(workingDirectory).toAbsolutePath().normalize();
        Path targetPath = this.resolvePath(serverBasePath, requestedFilePath);
        
        this.validatePathWithinServer(targetPath, serverBasePath);
        
        if (!Files.exists(targetPath)) {
            throw new IllegalArgumentException("File does not exist: " + requestedFilePath);
        }
        
        if (!Files.isRegularFile(targetPath)) {
            throw new IllegalArgumentException("Path is not a regular file: " + requestedFilePath);
        }

        String fileName = targetPath.getFileName().toString();

        String contentType;
        try {
            contentType = Files.probeContentType(targetPath);
            if (contentType == null) {
                contentType = "application/octet-stream";
            }
        } catch (Exception e) {
            contentType = "application/octet-stream";
        }

        try {
            byte[] fileBytes = Files.readAllBytes(targetPath);
            
            context.response()
                .putHeader("Content-Type", contentType)
                .putHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                .putHeader("Content-Length", String.valueOf(fileBytes.length))
                .end(io.vertx.core.buffer.Buffer.buffer(fileBytes));
                
        } catch (Exception e) {
            throw new RuntimeException("Failed to read file for download: " + e.getMessage(), e);
        }

        this.validatePathWithinServer(targetPath, serverBasePath);
    }

    public CompletableFuture<Long> uploadFileStream(String workingDirectory, String targetPath, ReadStream<Buffer> bodyStream) throws Exception {
        this.validateWorkingDirectory(workingDirectory);
        
        Path serverBasePath = Paths.get(workingDirectory).toAbsolutePath().normalize();
        Path targetFilePath = this.resolvePath(serverBasePath, targetPath);
        
        this.validatePathWithinServer(targetFilePath, serverBasePath);

        Path parentDir = targetFilePath.getParent();
        if (parentDir != null && !Files.exists(parentDir)) {
            Files.createDirectories(parentDir);
        }

        if (Files.exists(targetFilePath) && Files.isDirectory(targetFilePath)) {
            throw new IllegalArgumentException("Cannot upload file: target is a directory: " + targetPath);
        }

        CompletableFuture<Long> future = new CompletableFuture<>();
        final long maxFileSize = 8L * 1024 * 1024 * 1024; // 8GB limit
        
        try {
            FileChannel fileChannel = FileChannel.open(targetFilePath, 
                StandardOpenOption.CREATE, 
                StandardOpenOption.WRITE, 
                StandardOpenOption.TRUNCATE_EXISTING);
            
            final long[] totalBytesWritten = {0};
            
            bodyStream.handler(buffer -> {
                try {
                    if (totalBytesWritten[0] + buffer.length() > maxFileSize) {
                        try {
                            fileChannel.close();
                            Files.deleteIfExists(targetFilePath);
                        } catch (IOException e) {
                            Logger.error("Failed to cleanup after size limit exceeded", e);
                        }
                        future.completeExceptionally(new RuntimeException("File size exceeds maximum limit of 8GB"));
                        return;
                    }

                    int bytesWritten = fileChannel.write(buffer.getByteBuf().nioBuffer());
                    totalBytesWritten[0] += bytesWritten;
                } catch (IOException e) {
                    Logger.error("Failed to write chunk to file: " + targetPath, e);
                    try {
                        fileChannel.close();
                        Files.deleteIfExists(targetFilePath);
                    } catch (IOException closeEx) {
                        Logger.error("Failed to close file channel", closeEx);
                    }
                    future.completeExceptionally(new RuntimeException("Failed to write to file: " + e.getMessage(), e));
                }
            });
            
            bodyStream.endHandler(v -> {
                try {
                    fileChannel.close();
                    this.validatePathWithinServer(targetFilePath, serverBasePath);
                    future.complete(totalBytesWritten[0]);
                } catch (Exception e) {
                    Logger.error("Failed to finalize file upload: " + targetPath, e);
                    future.completeExceptionally(new RuntimeException("Failed to finalize upload: " + e.getMessage(), e));
                }
            });
            
            bodyStream.exceptionHandler(throwable -> {
                try {
                    fileChannel.close();
                } catch (IOException e) {
                    Logger.error("Failed to close file channel after exception", e);
                }
                future.completeExceptionally(new RuntimeException("Upload failed: " + throwable.getMessage(), throwable));
            });
            
        } catch (Exception e) {
            future.completeExceptionally(new RuntimeException("Failed to open file for upload: " + e.getMessage(), e));
        }
        
        return future;
    }

    public void createDirectory(String workingDirectory, String directoryPath) throws Exception {
        this.validateWorkingDirectory(workingDirectory);
        
        Path serverBasePath = Paths.get(workingDirectory).toAbsolutePath().normalize();
        Path targetDirPath = this.resolvePath(serverBasePath, directoryPath);
        
        this.validatePathWithinServer(targetDirPath, serverBasePath);
        
        if (Files.exists(targetDirPath)) {
            if (Files.isDirectory(targetDirPath)) {
                throw new IllegalArgumentException("Directory already exists: " + directoryPath);
            } else {
                throw new IllegalArgumentException("Cannot create directory: file exists with same name: " + directoryPath);
            }
        }

        try {
            Files.createDirectories(targetDirPath);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create directory: " + e.getMessage(), e);
        }

        this.validatePathWithinServer(targetDirPath, serverBasePath);
    }

    public void renameFile(String workingDirectory, String oldFilePath, String newFilePath) throws Exception {
        this.validateWorkingDirectory(workingDirectory);
        
        Path serverBasePath = Paths.get(workingDirectory).toAbsolutePath().normalize();
        Path oldTargetPath = this.resolvePath(serverBasePath, oldFilePath);
        Path newTargetPath = this.resolvePath(serverBasePath, newFilePath);

        this.validatePathWithinServer(oldTargetPath, serverBasePath);
        this.validatePathWithinServer(newTargetPath, serverBasePath);
        
        if (!Files.exists(oldTargetPath)) {
            throw new IllegalArgumentException("Source file does not exist: " + oldFilePath);
        }
        
        if (Files.exists(newTargetPath)) {
            throw new IllegalArgumentException("Destination already exists: " + newFilePath);
        }

        Path newParentDir = newTargetPath.getParent();
        if (newParentDir != null && !Files.exists(newParentDir)) {
            Files.createDirectories(newParentDir);
        }

        try {
            Files.move(oldTargetPath, newTargetPath);
        } catch (Exception e) {
            throw new RuntimeException("Failed to rename/move file: " + e.getMessage(), e);
        }

        this.validatePathWithinServer(newTargetPath, serverBasePath);
    }

    public boolean isValidPath(String path) {
        if (path == null) {
            return false;
        }

        String normalizedPath = Paths.get(path).normalize().toString();

        if (normalizedPath.contains("..") || normalizedPath.startsWith("..")) {
            return false;
        }

        if (!normalizedPath.startsWith("/") && !normalizedPath.equals(".")) {
            normalizedPath = "/" + normalizedPath;
        }

        normalizedPath = normalizedPath.replace("\\", "/");

        return !normalizedPath.contains("../") && !normalizedPath.contains("/..");
    }
    
    private void validateWorkingDirectory(String workingDirectory) {
        if (workingDirectory == null || workingDirectory.isEmpty()) {
            throw new IllegalArgumentException("Working directory cannot be null or empty");
        }
    }
    
    private Path resolvePath(Path serverBasePath, String requestedPath) {
        String normalizedPath = requestedPath;
        if (normalizedPath.equals("/") || normalizedPath.isEmpty()) {
            normalizedPath = ".";
        }

        if (normalizedPath.startsWith("/")) {
            normalizedPath = normalizedPath.substring(1);
        }
        
        return serverBasePath.resolve(normalizedPath).normalize();
    }
    
    private void validatePathWithinServer(Path targetPath, Path serverBasePath) {
        if (!targetPath.startsWith(serverBasePath)) {
            throw new SecurityException("Path traversal detected: requested path is outside server directory");
        }
    }
    
    private FileInfo createFileInfo(Path filePath) throws Exception {
        BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
        
        String fileName = filePath.getFileName().toString();
        boolean isDirectory = attrs.isDirectory();
        boolean isSymlink = attrs.isSymbolicLink();

        Long fileSize = isDirectory ? null : attrs.size();

        LocalDateTime createdAt = attrs.creationTime().toInstant()
            .atZone(ZoneId.systemDefault()).toLocalDateTime();
        LocalDateTime modifiedAt = attrs.lastModifiedTime().toInstant()
            .atZone(ZoneId.systemDefault()).toLocalDateTime();

        String mimeType;
        if (isDirectory) {
            mimeType = "inode/directory";
        } else {
            try {
                mimeType = Files.probeContentType(filePath);
                if (mimeType == null) {
                    mimeType = "application/octet-stream";
                }
            } catch (Exception e) {
                mimeType = "application/octet-stream";
            }
        }

        String mode;
        int modeBits;
        if (isDirectory) {
            mode = "rwxr-xr-x";
            modeBits = 755;
        } else {
            mode = "rw-r--r--";
            modeBits = 644;
        }
        
        return FileInfo.builder()
            .name(fileName)
            .mode(mode)
            .modeBits(modeBits)
            .size(fileSize)
            .isFile(!isDirectory)
            .isSymlink(isSymlink)
            .mimeType(mimeType)
            .createdAt(createdAt)
            .modifiedAt(modifiedAt)
            .build();
    }

    public UploadSession startChunkedUpload(String workingDirectory, String targetPath, long totalSize, Integer chunkSize) throws Exception {
        this.validateWorkingDirectory(workingDirectory);
        
        Path serverBasePath = Paths.get(workingDirectory).toAbsolutePath().normalize();
        Path targetFilePath = this.resolvePath(serverBasePath, targetPath);
        
        this.validatePathWithinServer(targetFilePath, serverBasePath);

        if (Files.exists(targetFilePath) && Files.isDirectory(targetFilePath)) {
            throw new IllegalArgumentException("Cannot upload file: target is a directory: " + targetPath);
        }

        Path parentDir = targetFilePath.getParent();
        if (parentDir != null && !Files.exists(parentDir)) {
            Files.createDirectories(parentDir);
        }

        int defaultChunkSize = 1048576;
        int finalChunkSize = chunkSize != null ? chunkSize : defaultChunkSize;

        if (finalChunkSize < 65536) {
            throw new IllegalArgumentException("Chunk size must be at least 64KB (65536 bytes)");
        }
        if (finalChunkSize > 524288000) {
            throw new IllegalArgumentException("Chunk size must not exceed 500MB (524288000 bytes)");
        }
        
        String uploadId = UUID.randomUUID().toString();
        String serverId = "";
        
        UploadSession session = UploadSession.create(uploadId, serverId, targetPath, workingDirectory, totalSize, finalChunkSize);
        this.uploadSessions.put(uploadId, session);

        Path chunksDir = this.getChunksDirectory(uploadId);
        Files.createDirectories(chunksDir);
        
        return session;
    }

    public void uploadChunk(String uploadId, int chunkNumber, byte[] chunkData) throws Exception {
        UploadSession session = this.uploadSessions.get(uploadId);
        if (session == null) {
            throw new IllegalArgumentException("Upload session not found: " + uploadId);
        }

        if (chunkNumber < 0 || chunkNumber >= session.getTotalChunks()) {
            throw new IllegalArgumentException("Invalid chunk number: " + chunkNumber + ". Expected 0-" + (session.getTotalChunks() - 1));
        }

        if (session.getReceivedChunks().contains(chunkNumber)) {
            return;
        }

        Path chunkFile = this.getChunkFile(uploadId, chunkNumber);
        Files.write(chunkFile, chunkData);

        session.markChunkReceived(chunkNumber);
        
        Logger.debug("Received chunk {} of {} for upload {}", chunkNumber + 1, session.getTotalChunks(), uploadId);
    }

    public long completeChunkedUpload(String uploadId) throws Exception {
        UploadSession session = this.uploadSessions.get(uploadId);
        if (session == null) {
            throw new IllegalArgumentException("Upload session not found: " + uploadId);
        }
        
        if (!session.isComplete()) {
            throw new IllegalArgumentException("Upload incomplete. Received " + session.getReceivedChunks().size() + " of " + session.getTotalChunks() + " chunks");
        }

        this.validateWorkingDirectory(session.getWorkingDirectory());
        Path serverBasePath = Paths.get(session.getWorkingDirectory()).toAbsolutePath().normalize();
        Path targetFilePath = this.resolvePath(serverBasePath, session.getTargetPath());
        this.validatePathWithinServer(targetFilePath, serverBasePath);
        
        long totalSize = 0;
        
        try (FileChannel outputChannel = FileChannel.open(targetFilePath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (int i = 0; i < session.getTotalChunks(); i++) {
                Path chunkFile = this.getChunkFile(uploadId, i);
                
                if (!Files.exists(chunkFile)) {
                    throw new IllegalStateException("Chunk file missing: " + i);
                }
                
                try (FileChannel chunkChannel = FileChannel.open(chunkFile, StandardOpenOption.READ)) {
                    long transferred = chunkChannel.transferTo(0, chunkChannel.size(), outputChannel);
                    totalSize += transferred;
                }
            }
        }

        this.cleanupUploadSession(uploadId);
        
        Logger.info("Completed chunked upload {} -> {} ({} bytes)", uploadId, session.getTargetPath(), totalSize);
        
        return totalSize;
    }

    public UploadSession getUploadSession(String uploadId) {
        return this.uploadSessions.get(uploadId);
    }
    
    private Path getChunksDirectory(String uploadId) {
        return Paths.get(System.getProperty("java.io.tmpdir"), "atlas-uploads", uploadId);
    }
    
    private Path getChunkFile(String uploadId, int chunkNumber) {
        return this.getChunksDirectory(uploadId).resolve("chunk-" + chunkNumber);
    }
    
    private void cleanupUploadSession(String uploadId) throws Exception {
        this.uploadSessions.remove(uploadId);
        
        Path chunksDir = this.getChunksDirectory(uploadId);
        if (Files.exists(chunksDir)) {
            Files.walk(chunksDir)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (Exception e) {
                        Logger.debug("Failed to delete upload temp file: " + path, e);
                    }
                });
        }
    }

    public FileListResponse listTemplateFiles(String requestedPath) throws Exception {
        Path templatesBasePath = Paths.get(TEMPLATES_DIR).toAbsolutePath().normalize();
        
        if (!Files.exists(templatesBasePath)) {
            Files.createDirectories(templatesBasePath);
        }
        
        Path targetPath = this.resolveTemplatePath(templatesBasePath, requestedPath);
        this.validatePathWithinTemplates(targetPath, templatesBasePath);
        
        if (!Files.exists(targetPath)) {
            throw new IllegalArgumentException("Template path does not exist: " + requestedPath);
        }
        
        if (!Files.isDirectory(targetPath)) {
            throw new IllegalArgumentException("Template path is not a directory: " + requestedPath);
        }

        List<FileInfo> fileInfos = new ArrayList<>();
        
        try (var directoryStream = Files.newDirectoryStream(targetPath)) {
            for (Path filePath : directoryStream) {
                FileInfo fileInfo = this.createFileInfo(filePath);
                fileInfos.add(fileInfo);
            }
        }

        fileInfos.sort((a, b) -> {
            if (a.isFile() != b.isFile()) {
                return a.isFile() ? 1 : -1;
            }
            return a.getName().compareToIgnoreCase(b.getName());
        });
        
        return FileListResponse.builder()
            .path(requestedPath)
            .files(fileInfos)
            .build();
    }

    public String readTemplateFileContents(String requestedFilePath) throws Exception {
        Path templatesBasePath = Paths.get(TEMPLATES_DIR).toAbsolutePath().normalize();
        Path targetPath = this.resolveTemplatePath(templatesBasePath, requestedFilePath);
        
        this.validatePathWithinTemplates(targetPath, templatesBasePath);
        
        if (!Files.exists(targetPath)) {
            throw new IllegalArgumentException("Template file does not exist: " + requestedFilePath);
        }
        
        if (!Files.isRegularFile(targetPath)) {
            throw new IllegalArgumentException("Template path is not a regular file: " + requestedFilePath);
        }

        try {
            return Files.readString(targetPath);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read template file contents: " + e.getMessage(), e);
        }
    }

    public void writeTemplateFileContents(String requestedFilePath, String content) throws Exception {
        Path templatesBasePath = Paths.get(TEMPLATES_DIR).toAbsolutePath().normalize();
        
        if (!Files.exists(templatesBasePath)) {
            Files.createDirectories(templatesBasePath);
        }
        
        Path targetPath = this.resolveTemplatePath(templatesBasePath, requestedFilePath);
        this.validatePathWithinTemplates(targetPath, templatesBasePath);

        Path parentDir = targetPath.getParent();
        if (parentDir != null && !Files.exists(parentDir)) {
            Files.createDirectories(parentDir);
        }

        try {
            Files.writeString(targetPath, content);
        } catch (Exception e) {
            throw new RuntimeException("Failed to write template file contents: " + e.getMessage(), e);
        }
    }

    public void deleteTemplateFile(String requestedFilePath) throws Exception {
        Path templatesBasePath = Paths.get(TEMPLATES_DIR).toAbsolutePath().normalize();
        Path targetPath = this.resolveTemplatePath(templatesBasePath, requestedFilePath);
        
        this.validatePathWithinTemplates(targetPath, templatesBasePath);
        
        if (!Files.exists(targetPath)) {
            throw new IllegalArgumentException("Template file does not exist: " + requestedFilePath);
        }

        try {
            if (Files.isDirectory(targetPath)) {
                Files.walk(targetPath)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to delete: " + path, e);
                        }
                    });
            } else {
                Files.delete(targetPath);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete template file/directory: " + e.getMessage(), e);
        }
    }

    public void downloadTemplateFile(RoutingContext context, String requestedFilePath) throws Exception {
        Path templatesBasePath = Paths.get(TEMPLATES_DIR).toAbsolutePath().normalize();
        Path targetPath = this.resolveTemplatePath(templatesBasePath, requestedFilePath);
        
        this.validatePathWithinTemplates(targetPath, templatesBasePath);
        
        if (!Files.exists(targetPath)) {
            throw new IllegalArgumentException("Template file does not exist: " + requestedFilePath);
        }
        
        if (!Files.isRegularFile(targetPath)) {
            throw new IllegalArgumentException("Template path is not a regular file: " + requestedFilePath);
        }

        String fileName = targetPath.getFileName().toString();

        String contentType;
        try {
            contentType = Files.probeContentType(targetPath);
            if (contentType == null) {
                contentType = "application/octet-stream";
            }
        } catch (Exception e) {
            contentType = "application/octet-stream";
        }

        try {
            byte[] fileBytes = Files.readAllBytes(targetPath);
            
            context.response()
                .putHeader("Content-Type", contentType)
                .putHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                .putHeader("Content-Length", String.valueOf(fileBytes.length))
                .end(io.vertx.core.buffer.Buffer.buffer(fileBytes));
                
        } catch (Exception e) {
            throw new RuntimeException("Failed to read template file for download: " + e.getMessage(), e);
        }
    }

    public CompletableFuture<Long> uploadTemplateFileStream(String targetPath, ReadStream<Buffer> bodyStream) throws Exception {
        Path templatesBasePath = Paths.get(TEMPLATES_DIR).toAbsolutePath().normalize();
        
        if (!Files.exists(templatesBasePath)) {
            Files.createDirectories(templatesBasePath);
        }
        
        Path targetFilePath = this.resolveTemplatePath(templatesBasePath, targetPath);
        this.validatePathWithinTemplates(targetFilePath, templatesBasePath);

        Path parentDir = targetFilePath.getParent();
        if (parentDir != null && !Files.exists(parentDir)) {
            Files.createDirectories(parentDir);
        }

        if (Files.exists(targetFilePath) && Files.isDirectory(targetFilePath)) {
            throw new IllegalArgumentException("Cannot upload template file: target is a directory: " + targetPath);
        }

        CompletableFuture<Long> future = new CompletableFuture<>();
        final long maxFileSize = 8L * 1024 * 1024 * 1024; // 8GB limit
        
        try {
            FileChannel fileChannel = FileChannel.open(targetFilePath, 
                StandardOpenOption.CREATE, 
                StandardOpenOption.WRITE, 
                StandardOpenOption.TRUNCATE_EXISTING);
            
            final long[] totalBytesWritten = {0};
            
            bodyStream.handler(buffer -> {
                try {
                    if (totalBytesWritten[0] + buffer.length() > maxFileSize) {
                        try {
                            fileChannel.close();
                            Files.deleteIfExists(targetFilePath);
                        } catch (IOException e) {
                            Logger.error("Failed to cleanup after size limit exceeded", e);
                        }
                        future.completeExceptionally(new RuntimeException("File size exceeds maximum limit of 8GB"));
                        return;
                    }

                    int bytesWritten = fileChannel.write(buffer.getByteBuf().nioBuffer());
                    totalBytesWritten[0] += bytesWritten;
                } catch (IOException e) {
                    Logger.error("Failed to write chunk to template file: " + targetPath, e);
                    try {
                        fileChannel.close();
                        Files.deleteIfExists(targetFilePath);
                    } catch (IOException closeEx) {
                        Logger.error("Failed to close file channel", closeEx);
                    }
                    future.completeExceptionally(new RuntimeException("Failed to write to template file: " + e.getMessage(), e));
                }
            });
            
            bodyStream.endHandler(v -> {
                try {
                    fileChannel.close();
                    future.complete(totalBytesWritten[0]);
                } catch (Exception e) {
                    Logger.error("Failed to finalize template file upload: " + targetPath, e);
                    future.completeExceptionally(new RuntimeException("Failed to finalize upload: " + e.getMessage(), e));
                }
            });
            
            bodyStream.exceptionHandler(throwable -> {
                try {
                    fileChannel.close();
                } catch (IOException e) {
                    Logger.error("Failed to close file channel after exception", e);
                }
                future.completeExceptionally(new RuntimeException("Template upload failed: " + throwable.getMessage(), throwable));
            });
            
        } catch (Exception e) {
            future.completeExceptionally(new RuntimeException("Failed to open template file for upload: " + e.getMessage(), e));
        }
        
        return future;
    }

    public void createTemplateDirectory(String directoryPath) throws Exception {
        Path templatesBasePath = Paths.get(TEMPLATES_DIR).toAbsolutePath().normalize();
        
        if (!Files.exists(templatesBasePath)) {
            Files.createDirectories(templatesBasePath);
        }
        
        Path targetDirPath = this.resolveTemplatePath(templatesBasePath, directoryPath);
        this.validatePathWithinTemplates(targetDirPath, templatesBasePath);
        
        if (Files.exists(targetDirPath)) {
            if (Files.isDirectory(targetDirPath)) {
                throw new IllegalArgumentException("Template directory already exists: " + directoryPath);
            } else {
                throw new IllegalArgumentException("Cannot create template directory: file exists with same name: " + directoryPath);
            }
        }

        try {
            Files.createDirectories(targetDirPath);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create template directory: " + e.getMessage(), e);
        }
    }

    public void renameTemplateFile(String oldFilePath, String newFilePath) throws Exception {
        Path templatesBasePath = Paths.get(TEMPLATES_DIR).toAbsolutePath().normalize();
        Path oldTargetPath = this.resolveTemplatePath(templatesBasePath, oldFilePath);
        Path newTargetPath = this.resolveTemplatePath(templatesBasePath, newFilePath);

        this.validatePathWithinTemplates(oldTargetPath, templatesBasePath);
        this.validatePathWithinTemplates(newTargetPath, templatesBasePath);
        
        if (!Files.exists(oldTargetPath)) {
            throw new IllegalArgumentException("Source template file does not exist: " + oldFilePath);
        }
        
        if (Files.exists(newTargetPath)) {
            throw new IllegalArgumentException("Destination template already exists: " + newFilePath);
        }

        Path newParentDir = newTargetPath.getParent();
        if (newParentDir != null && !Files.exists(newParentDir)) {
            Files.createDirectories(newParentDir);
        }

        try {
            Files.move(oldTargetPath, newTargetPath);
        } catch (Exception e) {
            throw new RuntimeException("Failed to rename/move template file: " + e.getMessage(), e);
        }
    }
    
    private Path resolveTemplatePath(Path templatesBasePath, String requestedPath) {
        String normalizedPath = requestedPath;
        if (normalizedPath.equals("/") || normalizedPath.isEmpty()) {
            normalizedPath = ".";
        }

        if (normalizedPath.startsWith("/")) {
            normalizedPath = normalizedPath.substring(1);
        }
        
        return templatesBasePath.resolve(normalizedPath).normalize();
    }
    
    private void validatePathWithinTemplates(Path targetPath, Path templatesBasePath) {
        if (!targetPath.startsWith(templatesBasePath)) {
            throw new SecurityException("Path traversal detected: requested path is outside templates directory");
        }
    }
}