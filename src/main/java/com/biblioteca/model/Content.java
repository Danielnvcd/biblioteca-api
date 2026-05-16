package com.biblioteca.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "content", indexes = {
    @Index(name = "idx_content_category", columnList = "category"),
    @Index(name = "idx_content_manual_category", columnList = "manual_category"),
    @Index(name = "idx_content_created_at", columnList = "created_at"),
    @Index(name = "idx_content_cat_date", columnList = "category,created_at")
})
public class Content {
    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 150)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, length = 255)
    private String filename;

    @Column(nullable = false, length = 50)
    private String category;

    @Column(name = "manual_category", length = 50)
    private String manualCategory;

    @Column(nullable = false, length = 80)
    private String author;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "cover_image", length = 255)
    private String coverImage;

    @Column(name = "link_url", columnDefinition = "TEXT")
    private String linkUrl;

    @OneToMany(mappedBy = "content", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Comment> comments = new ArrayList<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getManualCategory() { return manualCategory; }
    public void setManualCategory(String manualCategory) { this.manualCategory = manualCategory; }
    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public String getCoverImage() { return coverImage; }
    public void setCoverImage(String coverImage) { this.coverImage = coverImage; }
    public String getLinkUrl() { return linkUrl; }
    public void setLinkUrl(String linkUrl) { this.linkUrl = linkUrl; }
    public List<Comment> getComments() { return comments; }
    public void setComments(List<Comment> comments) { this.comments = comments; }
}
