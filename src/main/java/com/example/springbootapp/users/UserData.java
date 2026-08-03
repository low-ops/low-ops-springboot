package com.example.springbootapp.users;

import org.springframework.web.multipart.MultipartFile;

public class UserData {
    private Long id;
    private String name;
    private String email;
    private String avatar;
    private String avatarKey;
    private MultipartFile pendingUpload;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public String getAvatarKey() {
        return avatarKey;
    }

    public void setAvatarKey(String avatarKey) {
        this.avatarKey = avatarKey;
    }

    public MultipartFile getPendingUpload() {
        return pendingUpload;
    }

    public void setPendingUpload(MultipartFile pendingUpload) {
        this.pendingUpload = pendingUpload;
    }
}
