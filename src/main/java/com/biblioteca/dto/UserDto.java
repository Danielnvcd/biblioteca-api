package com.biblioteca.dto;

import java.time.LocalDateTime;

public class UserDto {
    private Integer id;
    private String username;
    private String role;
    private String fullName;
    private String area;
    private String position;
    private String factory;
    private String contactInfo;
    private String profilePic;
    private boolean totpEnabled;
    private LocalDateTime lastSeen;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
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
    public boolean isTotpEnabled() { return totpEnabled; }
    public void setTotpEnabled(boolean totpEnabled) { this.totpEnabled = totpEnabled; }
    public LocalDateTime getLastSeen() { return lastSeen; }
    public void setLastSeen(LocalDateTime lastSeen) { this.lastSeen = lastSeen; }
}
