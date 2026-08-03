package com.example.springbootapp.config;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public class S3ConfigResolver {

    public static final String MENIX_S3_SERVICE = "com.mendix.storage.s3";

    private static final Pattern AWS_REGION_PATTERN =
            Pattern.compile("^[a-z]{2}(?:-[a-z]+)+-\\d+$");

    public record S3Settings(
            String accessKeyId,
            String secretAccessKey,
            String bucket,
            String prefix,
            String endpoint,
            String region,
            boolean forcePathStyle,
            boolean performDelete,
            String serviceName
    ) {}

    public record BucketParts(String bucket, String prefix) {}

    public boolean parseBooleanEnv(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("1")
                || normalized.equals("true")
                || normalized.equals("yes")
                || normalized.equals("on");
    }

    public BucketParts parseBucketConfig(String raw) {
        String normalized = raw == null ? "" : raw.strip().replaceAll("^/+|/+$", "");
        if (normalized.isEmpty()) {
            return new BucketParts("", "");
        }
        int slashIndex = normalized.indexOf('/');
        if (slashIndex == -1) {
            return new BucketParts(normalized, "");
        }
        return new BucketParts(
                normalized.substring(0, slashIndex),
                normalized.substring(slashIndex + 1).replaceAll("^/+|/+$", "")
        );
    }

    public String normalizeEndpoint(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.strip().replaceAll("/+$", "");
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        if (trimmed.matches("(?i)^https?://.*")) {
            return trimmed;
        }
        return "https://" + trimmed;
    }

    public boolean isLikelyAwsRegion(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return AWS_REGION_PATTERN.matcher(value.strip()).matches();
    }

    public String extractRegionFromEndpoint(String endpoint) {
        try {
            String host = URI.create(normalizeEndpoint(endpoint)).getHost();
            if (host == null) {
                return null;
            }
            host = host.toLowerCase(Locale.ROOT);
            Matcher match = Pattern.compile("\\.s3[.-]([a-z0-9-]+)\\.amazonaws\\.com$").matcher(host);
            if (!match.find()) {
                match = Pattern.compile("^s3[.-]([a-z0-9-]+)\\.amazonaws\\.com$").matcher(host);
                if (!match.find()) {
                    if ("s3.amazonaws.com".equals(host) || host.endsWith(".s3.amazonaws.com")) {
                        return "us-east-1";
                    }
                    return null;
                }
            }
            String region = match.group(1);
            if (!"dualstack".equals(region) && isLikelyAwsRegion(region)) {
                return region;
            }
            if ("s3.amazonaws.com".equals(host) || host.endsWith(".s3.amazonaws.com")) {
                return "us-east-1";
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    public String resolveS3Region(String endpoint) {
        for (String candidate : new String[] {
                env("S3_REGION"),
                env("AWS_REGION"),
                env("AWS_DEFAULT_REGION"),
                env("S3_SERVICE_NAME")
        }) {
            if (isLikelyAwsRegion(candidate)) {
                return candidate.strip();
            }
        }
        String extracted = extractRegionFromEndpoint(endpoint);
        return extracted != null ? extracted : "us-east-1";
    }

    public boolean hasS3Config() {
        String serviceName = env("S3_SERVICE_NAME").strip();
        if (serviceName.startsWith("com.mendix.storage.") && !MENIX_S3_SERVICE.equals(serviceName)) {
            return false;
        }
        return !env("S3_ACCESS_KEY_ID").isBlank()
                && !env("S3_SECRET_ACCESS_KEY").isBlank()
                && !env("S3_BUCKET_NAME").isBlank()
                && !env("S3_ENDPOINT").isBlank();
    }

    public S3Settings resolveS3Config() {
        if (!hasS3Config()) {
            return null;
        }
        String endpoint = normalizeEndpoint(env("S3_ENDPOINT"));
        BucketParts bucketParts = parseBucketConfig(env("S3_BUCKET_NAME"));
        String region = resolveS3Region(endpoint);
        String serviceName = env("S3_SERVICE_NAME").strip();
        if (serviceName.isEmpty()) {
            serviceName = "s3";
        }
        return new S3Settings(
                env("S3_ACCESS_KEY_ID"),
                env("S3_SECRET_ACCESS_KEY"),
                bucketParts.bucket(),
                bucketParts.prefix(),
                endpoint,
                region,
                !endpoint.contains("amazonaws.com"),
                parseBooleanEnv(env("S3_PERFORM_DELETE")),
                serviceName
        );
    }

    private static String env(String key) {
        String value = System.getenv(key);
        return value == null ? "" : value;
    }
}
