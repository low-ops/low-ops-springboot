package com.example.springbootapp.users;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.example.springbootapp.storage.S3StorageService.AvatarPayload;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserStore userStore;
    private final AvatarService avatarService;

    public UserController(@Lazy UserStore userStore, @Lazy AvatarService avatarService) {
        this.userStore = userStore;
        this.avatarService = avatarService;
    }

    @GetMapping({"", "/"})
    public List<UserResponse> listUsers() {
        return userStore.listPublicUsers();
    }

    @PostMapping(value = {"", "/"}, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UserResponse> createUserMultipart(
            @RequestParam("name") String name,
            @RequestParam("email") String email,
            @RequestParam(value = "avatar_file", required = false) MultipartFile avatarFile
    ) {
        validateRequired(name, email);
        UserData created = userStore.createUser(
                userStore.validatedUserData(name.trim(), email.trim(), emptyToNull(avatarFile), null, null)
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(userStore.toPublicResponse(created));
    }

    @PostMapping(value = {"", "/"}, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<UserResponse> createUserJson(@RequestBody Map<String, Object> body) {
        String name = stringValue(body.get("name"));
        String email = stringValue(body.get("email"));
        validateRequired(name, email);
        UserData created = userStore.createUser(
                userStore.validatedUserData(name.trim(), email.trim(), null, null, null)
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(userStore.toPublicResponse(created));
    }

    @GetMapping({"/{userId}", "/{userId}/"})
    public UserResponse getUser(@PathVariable long userId) {
        UserData user = userStore.getUser(userId, false);
        if (user == null) {
            throw notFound();
        }
        return userStore.toPublicResponse(user);
    }

    @PutMapping(value = {"/{userId}", "/{userId}/"}, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UserResponse putUserMultipart(
            @PathVariable long userId,
            @RequestParam("name") String name,
            @RequestParam("email") String email,
            @RequestParam(value = "avatar_file", required = false) MultipartFile avatarFile
    ) {
        return update(userId, name, email, emptyToNull(avatarFile), false);
    }

    @PutMapping(value = {"/{userId}", "/{userId}/"}, consumes = MediaType.APPLICATION_JSON_VALUE)
    public UserResponse putUserJson(@PathVariable long userId, @RequestBody Map<String, Object> body) {
        return update(userId, stringValue(body.get("name")), stringValue(body.get("email")), null, false);
    }

    @PatchMapping(value = {"/{userId}", "/{userId}/"}, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UserResponse patchUserMultipart(
            @PathVariable long userId,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "email", required = false) String email,
            @RequestParam(value = "avatar_file", required = false) MultipartFile avatarFile
    ) {
        return update(userId, name, email, emptyToNull(avatarFile), true);
    }

    @PatchMapping(value = {"/{userId}", "/{userId}/"}, consumes = MediaType.APPLICATION_JSON_VALUE)
    public UserResponse patchUserJson(@PathVariable long userId, @RequestBody Map<String, Object> body) {
        String name = body.containsKey("name") ? stringValue(body.get("name")) : null;
        String email = body.containsKey("email") ? stringValue(body.get("email")) : null;
        return update(userId, name, email, null, true);
    }

    @DeleteMapping({"/{userId}", "/{userId}/"})
    public ResponseEntity<Void> deleteUser(@PathVariable long userId) {
        if (!userStore.deleteUser(userId)) {
            throw notFound();
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping({"/{userId}/avatar", "/{userId}/avatar/"})
    public ResponseEntity<byte[]> getAvatar(@PathVariable long userId) {
        UserData user = userStore.getUser(userId, true);
        if (user == null) {
            throw notFound();
        }
        AvatarPayload payload = avatarService.loadAvatarPayload(user);
        if (payload == null) {
            throw notFound();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                .contentType(MediaType.parseMediaType(payload.contentType()))
                .contentLength(payload.contentLength())
                .body(payload.body());
    }

    private UserResponse update(
            long userId,
            String name,
            String email,
            MultipartFile avatarFile,
            boolean partial
    ) {
        UserData existing = userStore.getUser(userId, true);
        if (existing == null) {
            throw notFound();
        }

        if (!partial) {
            validateRequired(name, email);
        } else {
            if (name != null) {
                validateName(name);
            }
            if (email != null) {
                validateEmail(email);
            }
        }

        String resolvedName = name == null ? null : name.trim();
        String resolvedEmail = email == null ? null : email.trim();
        UserData payload = userStore.validatedUserData(
                resolvedName,
                resolvedEmail,
                avatarFile,
                userId,
                existing.getAvatarKey()
        );
        UserData updated = userStore.updateUser(userId, payload, partial);
        return userStore.toPublicResponse(updated);
    }

    private void validateRequired(String name, String email) {
        Map<String, Object> errors = new LinkedHashMap<>();
        if (name == null || name.isBlank()) {
            errors.put("name", List.of("This field is required."));
        } else {
            validateName(name, errors);
        }
        if (email == null || email.isBlank()) {
            errors.put("email", List.of("This field is required."));
        } else {
            validateEmail(email, errors);
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
    }

    private void validateName(String name) {
        Map<String, Object> errors = new LinkedHashMap<>();
        validateName(name, errors);
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
    }

    private void validateEmail(String email) {
        Map<String, Object> errors = new LinkedHashMap<>();
        validateEmail(email, errors);
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
    }

    private void validateName(String name, Map<String, Object> errors) {
        if (name.length() > 150) {
            errors.put("name", List.of("Ensure this field has no more than 150 characters."));
        }
    }

    private void validateEmail(String email, Map<String, Object> errors) {
        if (!email.contains("@") || email.length() > 254) {
            errors.put("email", List.of("Enter a valid email address."));
        }
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found.");
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private MultipartFile emptyToNull(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return null;
        }
        return file;
    }

    public static class ValidationException extends RuntimeException {
        private final Map<String, Object> errors;

        public ValidationException(Map<String, Object> errors) {
            this.errors = errors;
        }

        public Map<String, Object> getErrors() {
            return errors;
        }
    }
}
