package com.example.springbootapp.users;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.example.springbootapp.config.BackendSupport;
import com.example.springbootapp.storage.S3StorageService;
import com.example.springbootapp.storage.S3StorageService.AvatarPayload;

@Service
public class AvatarService {

    private static final Logger logger = LoggerFactory.getLogger("lowops.avatars");
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".gif", ".webp");
    private static final Pattern DATA_URI_PATTERN =
            Pattern.compile("^data:([^;]+);base64,(.+)$", Pattern.DOTALL);

    private final BackendSupport backendSupport;
    private final S3StorageService s3StorageService;

    public AvatarService(BackendSupport backendSupport, @Lazy S3StorageService s3StorageService) {
        this.backendSupport = backendSupport;
        this.s3StorageService = s3StorageService;
    }

    public Path mediaRoot() {
        return Paths.get(System.getProperty("user.dir"), "media").toAbsolutePath().normalize();
    }

    public Map<String, String> saveAvatar(MultipartFile uploadedFile, long userId, String previousKey)
            throws IOException {
        backendSupport.ensureBackends();
        String ext = extension(uploadedFile);
        String contentType = uploadedFile.getContentType();
        if (contentType == null || contentType.isBlank()) {
            contentType = guessContentType(ext);
        }
        byte[] body = uploadedFile.getBytes();

        if (s3StorageService.isS3Available()) {
            String relativeKey = "avatars/" + userId + "/" + UUID.randomUUID().toString().replace("-", "") + ext;
            String key = s3StorageService.buildObjectKey(relativeKey);
            try {
                s3StorageService.uploadBytes(key, body, contentType);
                if (previousKey != null && !previousKey.isBlank() && !previousKey.equals(key)) {
                    s3StorageService.deleteObject(previousKey);
                }
                logger.info("Stored avatar for user {} in S3 key {}", userId, key);
                return Map.of(
                        "avatar", "/api/users/" + userId + "/avatar/",
                        "avatar_key", key
                );
            } catch (Exception exc) {
                logger.warn(
                        "S3 upload failed for user {}. Falling back to local storage. Reason: {}",
                        userId,
                        exc.toString(),
                        exc
                );
            }
        }

        Path avatarsDir = mediaRoot().resolve("avatars");
        Files.createDirectories(avatarsDir);
        String filename = UUID.randomUUID().toString().replace("-", "") + ext;
        Path path = avatarsDir.resolve(filename);
        Files.write(path, body);
        return Map.of(
                "avatar", "/media/avatars/" + filename,
                "avatar_key", ""
        );
    }

    public AvatarPayload loadAvatarPayload(UserData user) {
        backendSupport.ensureBackends();
        String avatarKey = user.getAvatarKey();
        String avatar = user.getAvatar();

        if (avatarKey != null && !avatarKey.isBlank() && s3StorageService.isS3Available()) {
            try {
                return s3StorageService.getObject(avatarKey);
            } catch (Exception exc) {
                logger.warn("Failed to load S3 avatar \"{}\": {}", avatarKey, exc.toString());
            }
        }

        if (avatar != null && avatar.startsWith("/media/")) {
            String relative = avatar.substring("/media/".length()).replaceAll("^/+", "");
            Path path = mediaRoot().resolve(relative).normalize();
            if (path.startsWith(mediaRoot()) && Files.isRegularFile(path)) {
                try {
                    byte[] body = Files.readAllBytes(path);
                    String contentType = Files.probeContentType(path);
                    if (contentType == null) {
                        contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
                    }
                    return new AvatarPayload(body, contentType, body.length);
                } catch (IOException exc) {
                    logger.warn("Failed to load local avatar \"{}\": {}", path, exc.toString());
                }
            }
        }

        if (avatar != null && avatar.startsWith("data:")) {
            Matcher match = DATA_URI_PATTERN.matcher(avatar);
            if (match.matches()) {
                byte[] body = Base64.getDecoder().decode(match.group(2));
                return new AvatarPayload(body, match.group(1), body.length);
            }
        }

        return null;
    }

    public void deleteAvatar(String avatarKey) {
        backendSupport.ensureBackends();
        if (avatarKey != null && !avatarKey.isBlank()) {
            s3StorageService.deleteObject(avatarKey);
        }
    }

    private String extension(MultipartFile uploadedFile) {
        String original = uploadedFile.getOriginalFilename();
        String ext = "";
        if (original != null) {
            int index = original.lastIndexOf('.');
            if (index >= 0) {
                ext = original.substring(index).toLowerCase(Locale.ROOT);
            }
        }
        if (ALLOWED_EXTENSIONS.contains(ext)) {
            return ext;
        }

        String contentType = uploadedFile.getContentType();
        if (contentType != null) {
            String guessed = switch (contentType.toLowerCase(Locale.ROOT)) {
                case "image/jpeg" -> ".jpg";
                case "image/png" -> ".png";
                case "image/gif" -> ".gif";
                case "image/webp" -> ".webp";
                default -> ".jpg";
            };
            return guessed;
        }
        return ".jpg";
    }

    private String guessContentType(String ext) {
        return switch (ext) {
            case ".jpg", ".jpeg" -> "image/jpeg";
            case ".png" -> "image/png";
            case ".gif" -> "image/gif";
            case ".webp" -> "image/webp";
            default -> MediaType.APPLICATION_OCTET_STREAM_VALUE;
        };
    }
}
