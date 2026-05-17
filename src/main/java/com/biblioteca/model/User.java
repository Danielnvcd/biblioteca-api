package com.biblioteca.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(unique = true, nullable = false, length = 80)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 200)
    private String passwordHash;

    @Column(length = 20)
    private String role = "user";

    @Column(name = "totp_secret", columnDefinition = "TEXT")
    private String totpSecret;

    @Column(name = "full_name", length = 150)
    private String fullName;

    @Column(length = 100)
    private String area;

    @Column(length = 100)
    private String position;

    @Column(length = 100)
    private String factory;

    @Column(name = "contact_info", length = 200)
    private String contactInfo;

    @Column(name = "profile_pic", length = 255)
    private String profilePic = "default.png";

    @Column(name = "last_seen")
    private LocalDateTime lastSeen;

    /**
     * Updated every time the password is changed. JWTs issued BEFORE this
     * timestamp are rejected — invalidates all sessions on password change.
     */
    @Column(name = "password_changed_at")
    private LocalDateTime passwordChangedAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getTotpSecret() { return totpSecret; }
    public void setTotpSecret(String totpSecret) { this.totpSecret = totpSecret; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getArea() { return area; }
    public void setArea(String area) { this.area = area; }
    public String getPosition() { return position; }
    public void setPosition(String position) { this.position = position; }
    public String getFactory() { return factory; }
    public void setFactory(String factory) { this.factory = factory; }
    public String getContactInfo() { return contactInfo; }
    public void setContactInfo(String contactInfo) { this.contactInfo = contactInfo; }
    public String getProfilePic() { return profilePic; }
    public void setProfilePic(String profilePic) { this.profilePic = profilePic; }
    public LocalDateTime getLastSeen() { return lastSeen; }
    public void setLastSeen(LocalDateTime lastSeen) { this.lastSeen = lastSeen; }
    public LocalDateTime getPasswordChangedAt() { return passwordChangedAt; }
    public void setPasswordChangedAt(LocalDateTime passwordChangedAt) { this.passwordChangedAt = passwordChangedAt; }
}
