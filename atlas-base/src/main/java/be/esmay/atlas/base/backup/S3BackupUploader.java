package be.esmay.atlas.base.backup;

import be.esmay.atlas.base.config.impl.AtlasConfig;
import be.esmay.atlas.base.utils.Logger;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.net.URI;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class S3BackupUploader {

    private final S3Client s3Client;

    public S3BackupUploader(AtlasConfig.S3 s3Config) {
        S3ClientBuilder builder = S3Client.builder();
        
        if (s3Config.getAccessKeyId() != null && s3Config.getSecretAccessKey() != null) {
            AwsBasicCredentials credentials = AwsBasicCredentials.create(s3Config.getAccessKeyId(), s3Config.getSecretAccessKey());
            builder.credentialsProvider(StaticCredentialsProvider.create(credentials));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }
        
        builder.region(Region.of(s3Config.getRegion()));
        
        if (s3Config.getEndpoint() != null && !s3Config.getEndpoint().equals("https://s3.amazonaws.com")) {
            builder.endpointOverride(URI.create(s3Config.getEndpoint()));
        }
        
        this.s3Client = builder.build();
        
        Logger.debug("S3BackupUploader initialized with endpoint: {}, region: {}", s3Config.getEndpoint(), s3Config.getRegion());
    }

    public CompletableFuture<Void> uploadFile(Path localFile, String bucket, String key) {
        return CompletableFuture.runAsync(() -> {
            try {
                PutObjectRequest putRequest = PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .build();

                RequestBody requestBody = RequestBody.fromFile(localFile);
                this.s3Client.putObject(putRequest, requestBody);

                Logger.info("Successfully uploaded backup to S3: s3://{}/{}", bucket, key);

            } catch (Exception e) {
                Logger.error("Failed to upload backup to S3: s3://{}/{}", bucket, key, e);
                throw new RuntimeException("S3 upload failed", e);
            }
        });
    }

    public void cleanupOldBackups(String bucket, String prefix, String serverIdentifier, int retention) {
        if (retention <= 0) {
            return;
        }

        try {
            String searchPrefix = prefix.endsWith("/") ? prefix + serverIdentifier : prefix + "/" + serverIdentifier;

            ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .prefix(searchPrefix)
                    .build();

            ListObjectsV2Response listResponse = this.s3Client.listObjectsV2(listRequest);
            List<S3Object> objects = listResponse.contents();

            if (objects.size() <= retention) {
                return;
            }

            List<S3Object> sortedObjects = objects.stream()
                    .sorted(Comparator.comparing(S3Object::lastModified).reversed())
                    .toList();

            for (int i = retention; i < sortedObjects.size(); i++) {
                S3Object objectToDelete = sortedObjects.get(i);
                
                DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                        .bucket(bucket)
                        .key(objectToDelete.key())
                        .build();

                this.s3Client.deleteObject(deleteRequest);
                Logger.info("Deleted old S3 backup: s3://{}/{}", bucket, objectToDelete.key());
            }

        } catch (Exception e) {
            Logger.error("Failed to cleanup old S3 backups for prefix: {}", prefix, e);
        }
    }

    public void shutdown() {
        if (this.s3Client != null) {
            this.s3Client.close();
        }
    }
}