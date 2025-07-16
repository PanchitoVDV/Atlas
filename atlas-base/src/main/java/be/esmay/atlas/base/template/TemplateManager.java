package be.esmay.atlas.base.template;

import be.esmay.atlas.base.config.impl.AtlasConfig;
import be.esmay.atlas.base.utils.Logger;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public final class TemplateManager {

    private static final String TEMPLATES_DIR = "templates";
    
    private final S3TemplateManager s3Manager;

    public TemplateManager(AtlasConfig.Templates templatesConfig) {
        this.s3Manager = templatesConfig.getS3() != null && templatesConfig.getS3().isEnabled()
            ? new S3TemplateManager(templatesConfig.getS3())
            : null;
    }

    public void applyTemplates(String serverDirectoryPath, List<String> templates) {
        if (templates == null || templates.isEmpty()) {
            Logger.debug("No templates to apply for directory: " + serverDirectoryPath);
            return;
        }

        Path serverPath = Paths.get(serverDirectoryPath);
        
        for (String template : templates) {
            this.applyTemplate(serverPath, template);
        }
    }

    public void applyTemplate(Path serverDirectory, String templatePath) {
        try {
            Path templateSourcePath = this.resolveTemplatePath(templatePath);
            
            if (templateSourcePath == null) {
                Logger.warn("Template not found: " + templatePath);
                return;
            }
            
            if (Files.isDirectory(templateSourcePath)) {
                this.copyDirectoryContents(templateSourcePath, serverDirectory);
            } else {
                this.copyFile(templateSourcePath, serverDirectory);
            }
            
            Logger.debug("Applied template: " + templatePath);
        } catch (IOException e) {
            Logger.error("Failed to apply template " + templatePath, e);
        }
    }

    private void copyDirectoryContents(Path source, Path target) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            paths.forEach(sourcePath -> {
                try {
                    Path relativePath = source.relativize(sourcePath);
                    Path targetPath = target.resolve(relativePath);
                    
                    if (Files.isDirectory(sourcePath)) {
                        Files.createDirectories(targetPath);
                    } else {
                        Files.createDirectories(targetPath.getParent());
                        Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    Logger.error("Failed to copy path: " + sourcePath, e);
                }
            });
        }
    }

    private void copyFile(Path sourceFile, Path targetDirectory) throws IOException {
        Path targetFile = targetDirectory.resolve(sourceFile.getFileName());
        Files.createDirectories(targetDirectory);
        Files.copy(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
    }

    private Path resolveTemplatePath(String templatePath) {
        if (templatePath.startsWith("s3://")) {
            String s3Path = templatePath.substring(5);
            if (this.s3Manager != null) {
                Optional<Path> downloadedPath = this.s3Manager.downloadTemplate(s3Path);
                return downloadedPath.orElse(null);
            }
            return null;
        }
        
        if (templatePath.startsWith("local://")) {
            templatePath = templatePath.substring(8);
        }
        
        if (this.s3Manager != null && this.s3Manager.templateExists(templatePath)) {
            Optional<Path> downloadedPath = this.s3Manager.downloadTemplate(templatePath);
            if (downloadedPath.isPresent()) {
                return downloadedPath.get();
            }
        }
        
        Path localPath = Paths.get(TEMPLATES_DIR, templatePath);
        return Files.exists(localPath) ? localPath : null;
    }

    public boolean clearCache() {
        if (this.s3Manager == null) {
            Logger.debug("S3 template manager not enabled, no cache to clear");
            return true;
        }
        
        return this.s3Manager.clearCache();
    }
    
    public void close() {
        if (this.s3Manager != null) {
            this.s3Manager.close();
        }
    }
}