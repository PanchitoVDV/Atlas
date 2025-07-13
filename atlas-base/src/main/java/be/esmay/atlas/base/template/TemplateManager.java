package be.esmay.atlas.base.template;

import be.esmay.atlas.base.utils.Logger;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.stream.Stream;

public final class TemplateManager {

    private static final String TEMPLATES_DIR = "templates";

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
            Path templateSourcePath = Paths.get(TEMPLATES_DIR, templatePath);
            
            if (!Files.exists(templateSourcePath)) {
                Logger.warn("Template not found: " + templateSourcePath);
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
}