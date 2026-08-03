package com.example.springbootapp.users;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.example.springbootapp.config.BackendSupport;
import com.example.springbootapp.config.DatabaseSupport;
import com.example.springbootapp.storage.S3StorageService;

@Service
public class UserStore {

    private static final Logger logger = LoggerFactory.getLogger("lowops.users");

    private final BackendSupport backendSupport;
    private final DatabaseSupport databaseSupport;
    private final S3StorageService s3StorageService;
    private final AvatarService avatarService;

    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicLong idCounter = new AtomicLong(4);
    private final Map<Long, UserData> users = new LinkedHashMap<>();

    public UserStore(
            BackendSupport backendSupport,
            DatabaseSupport databaseSupport,
            @Lazy S3StorageService s3StorageService,
            @Lazy AvatarService avatarService
    ) {
        this.backendSupport = backendSupport;
        this.databaseSupport = databaseSupport;
        this.s3StorageService = s3StorageService;
        this.avatarService = avatarService;
        seedMemoryUsers();
    }

    public void logBackendMode() {
        if (databaseSupport.isDatabaseAvailable()) {
            logger.info("Users CRUD is using PostgreSQL");
        } else {
            logger.warn("Users CRUD is using in-memory store");
        }

        if (s3StorageService.isS3Available()) {
            logger.info("User images are using S3 storage");
        } else {
            logger.warn("User images are using local storage");
        }
    }

    public List<UserData> listUsers() {
        backendSupport.ensureBackends();
        if (databaseSupport.isDatabaseAvailable()) {
            return jdbc().query("SELECT id, name, email, avatar, avatar_key FROM users ORDER BY id", rowMapper());
        }
        lock.lock();
        try {
            return users.values().stream()
                    .sorted(Comparator.comparing(UserData::getId))
                    .map(this::publicCopy)
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    public UserData getUser(long userId, boolean includePrivate) {
        backendSupport.ensureBackends();
        if (databaseSupport.isDatabaseAvailable()) {
            List<UserData> found = jdbc().query(
                    "SELECT id, name, email, avatar, avatar_key FROM users WHERE id = ?",
                    rowMapper(),
                    userId
            );
            if (found.isEmpty()) {
                return null;
            }
            UserData data = found.getFirst();
            return includePrivate ? data : publicCopy(data);
        }

        lock.lock();
        try {
            UserData user = users.get(userId);
            if (user == null) {
                return null;
            }
            return includePrivate ? copy(user) : publicCopy(user);
        } finally {
            lock.unlock();
        }
    }

    public UserData createUser(UserData data) {
        backendSupport.ensureBackends();
        try {
            if (databaseSupport.isDatabaseAvailable()) {
                KeyHolder keyHolder = new GeneratedKeyHolder();
                jdbc().update(connection -> {
                    PreparedStatement ps = connection.prepareStatement(
                            "INSERT INTO users (name, email, avatar, avatar_key) VALUES (?, ?, ?, ?) RETURNING id"
                    );
                    ps.setString(1, data.getName());
                    ps.setString(2, data.getEmail());
                    ps.setString(3, data.getAvatar());
                    ps.setString(4, blankToNull(data.getAvatarKey()));
                    return ps;
                }, keyHolder);

                long userId = extractGeneratedId(keyHolder);
                if (data.getPendingUpload() != null) {
                    Map<String, String> saved = avatarService.saveAvatar(data.getPendingUpload(), userId, null);
                    jdbc().update(
                            "UPDATE users SET avatar = ?, avatar_key = ?, updated_at = NOW() WHERE id = ?",
                            saved.get("avatar"),
                            blankToNull(saved.get("avatar_key")),
                            userId
                    );
                }
                return getUser(userId, true);
            }

            lock.lock();
            try {
                long userId = idCounter.getAndIncrement();
                UserData user = new UserData();
                user.setId(userId);
                user.setName(data.getName());
                user.setEmail(data.getEmail());
                user.setAvatar(data.getAvatar());
                user.setAvatarKey(blankToNull(data.getAvatarKey()));
                if (data.getPendingUpload() != null) {
                    Map<String, String> saved = avatarService.saveAvatar(data.getPendingUpload(), userId, null);
                    user.setAvatar(saved.get("avatar"));
                    user.setAvatarKey(blankToNull(saved.get("avatar_key")));
                }
                users.put(userId, user);
                return publicCopy(user);
            } finally {
                lock.unlock();
            }
        } catch (DataIntegrityViolationException exc) {
            throw exc;
        } catch (Exception exc) {
            throw new IllegalStateException("Failed to create user: " + exc.getMessage(), exc);
        }
    }

    public UserData updateUser(long userId, UserData data, boolean partial) {
        backendSupport.ensureBackends();
        try {
            if (databaseSupport.isDatabaseAvailable()) {
                UserData existing = getUser(userId, true);
                if (existing == null) {
                    return null;
                }

                String name = partial && data.getName() == null ? existing.getName() : data.getName();
                String email = partial && data.getEmail() == null ? existing.getEmail() : data.getEmail();
                String avatar = existing.getAvatar();
                String avatarKey = existing.getAvatarKey();

                if (data.getAvatar() != null || (!partial && data.getAvatarKey() != null)) {
                    avatar = data.getAvatar();
                }
                if (data.getAvatarKey() != null) {
                    avatarKey = blankToNull(data.getAvatarKey());
                } else if (!partial && data.getAvatar() != null) {
                    // keep existing key unless explicitly replaced via saveAvatar
                }

                if (partial) {
                    if (data.getName() != null) {
                        name = data.getName();
                    }
                    if (data.getEmail() != null) {
                        email = data.getEmail();
                    }
                    if (data.getAvatar() != null) {
                        avatar = data.getAvatar();
                    }
                    if (data.getAvatarKey() != null) {
                        avatarKey = blankToNull(data.getAvatarKey());
                    }
                } else {
                    name = data.getName();
                    email = data.getEmail();
                    if (data.getAvatar() != null) {
                        avatar = data.getAvatar();
                    }
                    if (data.getAvatarKey() != null) {
                        avatarKey = blankToNull(data.getAvatarKey());
                    }
                }

                jdbc().update(
                        "UPDATE users SET name = ?, email = ?, avatar = ?, avatar_key = ?, updated_at = NOW() WHERE id = ?",
                        name,
                        email,
                        avatar,
                        avatarKey,
                        userId
                );
                return getUser(userId, true);
            }

            lock.lock();
            try {
                UserData user = users.get(userId);
                if (user == null) {
                    return null;
                }
                if (partial) {
                    if (data.getName() != null) {
                        user.setName(data.getName());
                    }
                    if (data.getEmail() != null) {
                        user.setEmail(data.getEmail());
                    }
                    if (data.getAvatar() != null) {
                        user.setAvatar(data.getAvatar());
                    }
                    if (data.getAvatarKey() != null) {
                        user.setAvatarKey(blankToNull(data.getAvatarKey()));
                    }
                } else {
                    user.setName(data.getName());
                    user.setEmail(data.getEmail());
                    if (data.getAvatar() != null) {
                        user.setAvatar(data.getAvatar());
                    }
                    if (data.getAvatarKey() != null) {
                        user.setAvatarKey(blankToNull(data.getAvatarKey()));
                    }
                }
                return publicCopy(user);
            } finally {
                lock.unlock();
            }
        } catch (Exception exc) {
            throw new IllegalStateException("Failed to update user: " + exc.getMessage(), exc);
        }
    }

    public boolean deleteUser(long userId) {
        backendSupport.ensureBackends();
        if (databaseSupport.isDatabaseAvailable()) {
            UserData existing = getUser(userId, true);
            if (existing == null) {
                return false;
            }
            avatarService.deleteAvatar(existing.getAvatarKey());
            int deleted = jdbc().update("DELETE FROM users WHERE id = ?", userId);
            return deleted > 0;
        }

        lock.lock();
        try {
            UserData user = users.remove(userId);
            if (user == null) {
                return false;
            }
            avatarService.deleteAvatar(user.getAvatarKey());
            return true;
        } finally {
            lock.unlock();
        }
    }

    public UserResponse toPublicResponse(UserData user) {
        if (user == null) {
            return null;
        }
        UserData publicUser = publicCopy(user);
        return new UserResponse(
                publicUser.getId(),
                publicUser.getName(),
                publicUser.getEmail(),
                publicUser.getAvatar()
        );
    }

    private void seedMemoryUsers() {
        users.put(1L, memoryUser(1L, "Alice Johnson", "alice@example.com"));
        users.put(2L, memoryUser(2L, "Bob Smith", "bob@example.com"));
        users.put(3L, memoryUser(3L, "Carol Lee", "carol@example.com"));
    }

    private UserData memoryUser(long id, String name, String email) {
        UserData user = new UserData();
        user.setId(id);
        user.setName(name);
        user.setEmail(email);
        user.setAvatar(null);
        user.setAvatarKey(null);
        return user;
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(databaseSupport.getDataSource());
    }

    private RowMapper<UserData> rowMapper() {
        return (rs, rowNum) -> {
            UserData user = new UserData();
            user.setId(rs.getLong("id"));
            user.setName(rs.getString("name"));
            user.setEmail(rs.getString("email"));
            user.setAvatar(rs.getString("avatar"));
            user.setAvatarKey(rs.getString("avatar_key"));
            if (user.getAvatarKey() != null && !user.getAvatarKey().isBlank()) {
                user.setAvatar("/api/users/" + user.getId() + "/avatar/");
            }
            return user;
        };
    }

    private UserData publicCopy(UserData user) {
        UserData data = new UserData();
        data.setId(user.getId());
        data.setName(user.getName());
        data.setEmail(user.getEmail());
        data.setAvatar(user.getAvatar());
        if (user.getAvatarKey() != null && !user.getAvatarKey().isBlank()) {
            data.setAvatar("/api/users/" + user.getId() + "/avatar/");
        }
        return data;
    }

    private UserData copy(UserData user) {
        UserData data = new UserData();
        data.setId(user.getId());
        data.setName(user.getName());
        data.setEmail(user.getEmail());
        data.setAvatar(user.getAvatar());
        data.setAvatarKey(user.getAvatarKey());
        return data;
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    private long extractGeneratedId(KeyHolder keyHolder) {
        List<Map<String, Object>> keyList = keyHolder.getKeyList();
        if (keyList == null || keyList.isEmpty()) {
            throw new IllegalStateException("Failed to create user");
        }
        Map<String, Object> keys = keyList.getFirst();
        Object id = keys.get("id");
        if (id == null) {
            id = keys.get("ID");
        }
        if (id instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalStateException("Failed to create user");
    }

    public UserData validatedUserData(
            String name,
            String email,
            MultipartFile avatarFile,
            Long userId,
            String previousKey
    ) {
        UserData data = new UserData();
        data.setName(name);
        data.setEmail(email);

        if (avatarFile != null && !avatarFile.isEmpty()) {
            if (userId == null) {
                data.setPendingUpload(avatarFile);
            } else {
                try {
                    Map<String, String> saved = avatarService.saveAvatar(avatarFile, userId, previousKey);
                    data.setAvatar(saved.get("avatar"));
                    data.setAvatarKey(blankToNull(saved.get("avatar_key")));
                } catch (Exception exc) {
                    throw new IllegalStateException("Failed to save avatar: " + exc.getMessage(), exc);
                }
            }
        }
        return data;
    }

    public List<UserResponse> listPublicUsers() {
        List<UserResponse> responses = new ArrayList<>();
        for (UserData user : listUsers()) {
            responses.add(toPublicResponse(user));
        }
        return responses;
    }
}
