package com.example.springbootapp.storage;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.springbootapp.config.S3ConfigResolver;
import com.example.springbootapp.config.S3ConfigResolver.S3Settings;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Service
public class S3StorageService {

    private static final Logger logger = LoggerFactory.getLogger("lowops.s3");

    private final S3ConfigResolver configResolver;

    private volatile boolean available;
    private volatile S3Client client;
    private volatile S3Settings config;

    public S3StorageService(S3ConfigResolver configResolver) {
        this.configResolver = configResolver;
    }

    public boolean isS3Available() {
        return available;
    }

    public S3Settings getConfig() {
        return config;
    }

    public boolean initS3() {
        S3Settings resolved = configResolver.resolveS3Config();
        if (resolved == null) {
            String serviceName = env("S3_SERVICE_NAME").strip();
            if (serviceName.startsWith("com.mendix.storage.")
                    && !S3ConfigResolver.MENIX_S3_SERVICE.equals(serviceName)) {
                logger.warn(
                        "Storage service \"{}\" is not S3. Image uploads will use local storage.",
                        serviceName
                );
            } else {
                logger.warn(
                        "S3 is not configured (S3_* env vars missing). "
                                + "Image uploads will use local storage."
                );
            }
            clear();
            return false;
        }

        if (resolved.bucket().isBlank()) {
            logger.warn("S3_BUCKET_NAME is empty after parsing. Image uploads will use local storage.");
            clear();
            return false;
        }

        S3Configuration s3Configuration = S3Configuration.builder()
                .pathStyleAccessEnabled(resolved.forcePathStyle())
                .build();

        ClientOverrideConfiguration overrideConfiguration = ClientOverrideConfiguration.builder()
                .apiCallTimeout(Duration.ofSeconds(8))
                .apiCallAttemptTimeout(Duration.ofSeconds(5))
                .build();

        S3Client candidate = S3Client.builder()
                .region(Region.of(resolved.region()))
                .endpointOverride(java.net.URI.create(resolved.endpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(resolved.accessKeyId(), resolved.secretAccessKey())
                ))
                .serviceConfiguration(s3Configuration)
                .overrideConfiguration(overrideConfiguration)
                .build();

        try {
            verifyConnection(candidate, resolved);
            client = candidate;
            config = resolved;
            available = true;
            String location = resolved.prefix().isBlank()
                    ? resolved.bucket()
                    : resolved.bucket() + "/" + resolved.prefix();
            logger.info("S3 connection established (bucket: {}, region: {})", location, resolved.region());
            return true;
        } catch (Exception exc) {
            try {
                candidate.close();
            } catch (Exception ignored) {
                // no-op
            }
            clear();
            logger.warn(
                    "S3 connection failed. Image uploads will use local storage. Reason: {}",
                    exc.toString()
            );
            return false;
        }
    }

    private void verifyConnection(S3Client candidate, S3Settings settings) {
        try {
            candidate.headBucket(HeadBucketRequest.builder().bucket(settings.bucket()).build());
        } catch (RuntimeException headError) {
            ListObjectsV2Request.Builder builder = ListObjectsV2Request.builder()
                    .bucket(settings.bucket())
                    .maxKeys(1);
            if (!settings.prefix().isBlank()) {
                builder.prefix(settings.prefix() + "/");
            }
            candidate.listObjectsV2(builder.build());
            logger.debug("HeadBucket failed but ListObjects succeeded ({})", headError.toString());
        }
    }

    public String buildObjectKey(String relativeKey) {
        if (config == null) {
            throw new IllegalStateException("S3 is not available");
        }
        String normalized = relativeKey.replaceAll("^/+", "");
        if (!config.prefix().isBlank()) {
            return config.prefix() + "/" + normalized;
        }
        return normalized;
    }

    public String uploadBytes(String key, byte[] body, String contentType) {
        if (!available || client == null || config == null) {
            throw new IllegalStateException("S3 is not available");
        }
        client.putObject(
                PutObjectRequest.builder()
                        .bucket(config.bucket())
                        .key(key)
                        .contentType(contentType)
                        .contentLength((long) body.length)
                        .build(),
                RequestBody.fromBytes(body)
        );
        return key;
    }

    public AvatarPayload getObject(String key) {
        if (!available || client == null || config == null) {
            throw new IllegalStateException("S3 is not available");
        }
        var response = client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(config.bucket()).key(key).build()
        );
        byte[] body = response.asByteArray();
        String contentType = response.response().contentType();
        if (contentType == null || contentType.isBlank()) {
            contentType = "application/octet-stream";
        }
        Long contentLength = response.response().contentLength();
        return new AvatarPayload(body, contentType, contentLength != null ? contentLength : body.length);
    }

    public void deleteObject(String key) {
        if (key == null || key.isBlank() || !available || client == null || config == null) {
            return;
        }
        if (!config.performDelete()) {
            return;
        }
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(config.bucket()).key(key).build());
        } catch (Exception exc) {
            logger.warn("Failed to delete S3 object \"{}\": {}", key, exc.toString());
        }
    }

    private void clear() {
        available = false;
        client = null;
        config = null;
    }

    private static String env(String key) {
        String value = System.getenv(key);
        return value == null ? "" : value;
    }

    public record AvatarPayload(byte[] body, String contentType, long contentLength) {}
}
