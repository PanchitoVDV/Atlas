package be.esmay.atlas.base.template;

import be.esmay.atlas.base.config.impl.AtlasConfig;
import be.esmay.atlas.base.utils.Logger;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public final class S3TemplateManager {

    private final AtlasConfig.Templates templatesConfig;
    private final AtlasConfig.S3 s3Config;
    private final S3Client s3Client;
    private final Path cacheDirectory;

    public S3TemplateManager(AtlasConfig.Templates templatesConfig, AtlasConfig.S3 s3Config) {
        this.templatesConfig = templatesConfig;
        this.s3Config = s3Config;
        this.s3Client = this.createS3Client();
        this.cacheDirectory = templatesConfig.getS3Cache() != null && templatesConfig.getS3Cache().isEnabled() 
            ? Paths.get(templatesConfig.getS3Cache().getDirectory())
            : null;
        
        if (this.cacheDirectory != null) {
            try {
                Files.createDirectories(this.cacheDirectory);
            } catch (IOException e) {
                Logger.error("Failed to create cache directory: " + this.cacheDirectory, e);
            }
        }
    }

    public Optional<Path> downloadTemplate(String templatePath) {
        if (!this.templatesConfig.isS3Enabled()) {
            return Optional.empty();
        }

        try {
            String s3Key = this.templatesConfig.getS3PathPrefix() + templatePath;
            
            Optional<Path> cachedTemplate = this.getCachedTemplate(templatePath);
            if (cachedTemplate.isPresent() && this.isCacheValid(cachedTemplate.get(), s3Key)) {
                Logger.debug("Using cached template: " + templatePath);
                return cachedTemplate;
            }

            return this.downloadFromS3(s3Key, templatePath);
        } catch (Exception e) {
            Logger.error("Failed to download template from S3: " + templatePath, e);
            return Optional.empty();
        }
    }

    public boolean templateExists(String templatePath) {
        if (!this.templatesConfig.isS3Enabled()) {
            return false;
        }

        try {
            String s3Key = this.templatesConfig.getS3PathPrefix() + templatePath;
            HeadObjectRequest headRequest = HeadObjectRequest.builder()
                .bucket(this.templatesConfig.getS3Bucket())
                .key(s3Key)
                .build();
            
            this.s3Client.headObject(headRequest);
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            Logger.error("Failed to check if template exists in S3: " + templatePath, e);
            return false;
        }
    }

    public TemplateMetadata getTemplateMetadata(String templatePath) {
        if (!this.templatesConfig.isS3Enabled()) {
            return null;
        }

        try {
            String s3Key = this.templatesConfig.getS3PathPrefix() + templatePath;
            HeadObjectRequest headRequest = HeadObjectRequest.builder()
                .bucket(this.templatesConfig.getS3Bucket())
                .key(s3Key)
                .build();
            
            HeadObjectResponse response = this.s3Client.headObject(headRequest);
            
            Instant cacheExpiration = null;
            if (this.templatesConfig.getS3Cache() != null) {
                cacheExpiration = Instant.now().plusSeconds(this.templatesConfig.getS3Cache().getTtlSeconds());
            }
            
            return new TemplateMetadata(
                templatePath,
                TemplateSource.S3,
                response.eTag(),
                response.contentLength(),
                response.lastModified(),
                cacheExpiration
            );
        } catch (S3Exception e) {
            Logger.error("Failed to get template metadata from S3: " + templatePath, e);
            return null;
        }
    }

    public boolean clearCache() {
        if (this.cacheDirectory == null || !Files.exists(this.cacheDirectory)) {
            Logger.debug("No cache directory to clear");
            return true;
        }
        
        try {
            Files.walk(this.cacheDirectory)
                .filter(Files::isRegularFile)
                .forEach(path -> {
                    try {
                        Files.delete(path);
                        Logger.debug("Deleted cached template: " + path);
                    } catch (IOException e) {
                        Logger.warn("Failed to delete cached template: " + path, e);
                    }
                });
            Logger.info("Successfully cleared template cache directory: " + this.cacheDirectory);
            return true;
        } catch (IOException e) {
            Logger.error("Failed to clear template cache", e);
            return false;
        }
    }
    
    public void close() {
        if (this.s3Client != null) {
            this.s3Client.close();
        }
    }
    
    public List<String> listTemplates() {
        List<String> templates = new ArrayList<>();
        
        if (!this.templatesConfig.isS3Enabled()) {
            return templates;
        }
        
        try {
            ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                .bucket(this.templatesConfig.getS3Bucket())
                .prefix(this.templatesConfig.getS3PathPrefix())
                .delimiter("/")
                .build();
            
            ListObjectsV2Response response = this.s3Client.listObjectsV2(listRequest);
            
            templates = response.commonPrefixes().stream()
                .map(commonPrefix -> {
                    String prefix = commonPrefix.prefix();
                    if (prefix.startsWith(this.templatesConfig.getS3PathPrefix())) {
                        prefix = prefix.substring(this.templatesConfig.getS3PathPrefix().length());
                    }
                    if (prefix.endsWith("/")) {
                        prefix = prefix.substring(0, prefix.length() - 1);
                    }
                    return prefix;
                })
                .filter(template -> !template.isEmpty())
                .sorted()
                .collect(Collectors.toList());
                
        } catch (S3Exception e) {
            Logger.error("Failed to list templates from S3", e);
        }
        
        return templates;
    }

    private S3Client createS3Client() {
        S3ClientBuilder builder = S3Client.builder();
        
        if (this.s3Config.getRegion() != null) {
            builder.region(Region.of(this.s3Config.getRegion()));
        }
        
        if (this.s3Config.getEndpoint() != null && !this.s3Config.getEndpoint().equals("https://s3.amazonaws.com")) {
            builder.endpointOverride(URI.create(this.s3Config.getEndpoint()));
            builder.forcePathStyle(true);
        }
        
        AwsCredentialsProvider credentialsProvider = this.createCredentialsProvider();
        builder.credentialsProvider(credentialsProvider);
        
        return builder.build();
    }

    private AwsCredentialsProvider createCredentialsProvider() {
        if (this.s3Config.getAccessKeyId() != null && this.s3Config.getSecretAccessKey() != null) {
            AwsBasicCredentials credentials = AwsBasicCredentials.create(
                this.s3Config.getAccessKeyId(),
                this.s3Config.getSecretAccessKey()
            );
            return StaticCredentialsProvider.create(credentials);
        }
        
        return DefaultCredentialsProvider.create();
    }

    private Optional<Path> getCachedTemplate(String templatePath) {
        if (this.cacheDirectory == null) {
            return Optional.empty();
        }
        
        Path cachedFile = this.cacheDirectory.resolve(templatePath);
        return Files.exists(cachedFile) ? Optional.of(cachedFile) : Optional.empty();
    }

    private boolean isCacheValid(Path cachedFile, String s3Key) {
        if (this.templatesConfig.getS3Cache() == null || this.templatesConfig.getS3Cache().getTtlSeconds() <= 0) {
            return true;
        }
        
        try {
            Instant fileTime = Files.getLastModifiedTime(cachedFile).toInstant();
            Instant expiration = fileTime.plusSeconds(this.templatesConfig.getS3Cache().getTtlSeconds());
            
            if (Instant.now().isAfter(expiration)) {
                return false;
            }
            
            HeadObjectRequest headRequest = HeadObjectRequest.builder()
                .bucket(this.templatesConfig.getS3Bucket())
                .key(s3Key)
                .build();
            
            HeadObjectResponse response = this.s3Client.headObject(headRequest);
            return response.lastModified().isBefore(fileTime) || response.lastModified().equals(fileTime);
        } catch (Exception e) {
            Logger.warn("Failed to validate cache for template: " + cachedFile, e);
            return false;
        }
    }

    private Optional<Path> downloadFromS3(String s3Key, String templatePath) {
        try {
            GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(this.templatesConfig.getS3Bucket())
                .key(s3Key)
                .build();
            
            ResponseInputStream<GetObjectResponse> response = this.s3Client.getObject(getRequest);
            
            Path targetPath;
            if (this.cacheDirectory != null) {
                targetPath = this.cacheDirectory.resolve(templatePath);
                Files.createDirectories(targetPath.getParent());
            } else {
                targetPath = Files.createTempFile("atlas-template-", ".tmp");
            }
            
            Files.copy(response, targetPath, StandardCopyOption.REPLACE_EXISTING);
            Logger.debug("Downloaded template from S3: " + templatePath + " to " + targetPath);
            
            return Optional.of(targetPath);
        } catch (IOException | S3Exception e) {
            Logger.error("Failed to download template from S3: " + s3Key, e);
            return Optional.empty();
        }
    }
}