package be.esmay.atlas.base.template;

import be.esmay.atlas.base.config.impl.AtlasConfig;
import be.esmay.atlas.base.utils.Logger;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class TemplateManager {

    private static final String TEMPLATES_DIR = "templates";
    
    private final S3TemplateManager s3Manager;
    private final AtlasConfig.Templates templatesConfig;

    public TemplateManager(AtlasConfig.Templates templatesConfig) {
        this.templatesConfig = templatesConfig;
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
    
    public void applyTemplatesWithPluginCleanup(String serverDirectoryPath, List<String> templates, boolean isStaticServer) {
        if (templates == null || templates.isEmpty()) {
            Logger.debug("No templates to apply for directory: " + serverDirectoryPath);
            return;
        }

        Path serverPath = Paths.get(serverDirectoryPath);
        
        if (isStaticServer && this.templatesConfig.isCleanPluginsBeforeTemplates()) {
            this.cleanPluginJars(serverPath);
        }
        
        for (String template : templates) {
            this.applyTemplate(serverPath, template);
        }
    }
    
    private void cleanPluginJars(Path serverPath) {
        Path pluginsPath = serverPath.resolve("plugins");
        
        if (!Files.exists(pluginsPath) || !Files.isDirectory(pluginsPath)) {
            Logger.debug("Plugins directory does not exist at: " + pluginsPath);
            return;
        }
        
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(pluginsPath, "*.jar")) {
            int deletedCount = 0;
            for (Path jarFile : stream) {
                try {
                    Files.delete(jarFile);
                    deletedCount++;
                    Logger.debug("Deleted plugin JAR: " + jarFile.getFileName());
                } catch (IOException e) {
                    Logger.warn("Failed to delete plugin JAR: " + jarFile.getFileName() + " - " + e.getMessage());
                }
            }
            
            if (deletedCount > 0) {
                Logger.debug("Cleaned " + deletedCount + " plugin JAR files from " + pluginsPath);
            }
        } catch (IOException e) {
            Logger.error("Failed to clean plugin JARs from: " + pluginsPath, e);
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
    
    public List<String> getAvailableTemplates() {
        List<String> templates = new ArrayList<>();
        
        Path templatesPath = Paths.get(TEMPLATES_DIR);
        if (!Files.exists(templatesPath)) {
            return templates;
        }
        
        try (Stream<Path> paths = Files.walk(templatesPath)) {
            List<String> localTemplates = paths
                .filter(Files::isDirectory)
                .filter(path -> !path.equals(templatesPath))
                .map(path -> templatesPath.relativize(path).toString())
                .map(path -> path.replace(FileSystems.getDefault().getSeparator(), "/"))
                .sorted()
                .collect(Collectors.toList());
                
            templates.addAll(localTemplates);
                
            if (this.s3Manager != null) {
                List<String> s3Templates = this.s3Manager.listTemplates();
                s3Templates.stream()
                    .filter(s3Template -> !templates.contains(s3Template))
                    .forEach(templates::add);
            }
        } catch (IOException e) {
            Logger.error("Failed to list available templates", e);
        }
        
        return templates;
    }
}