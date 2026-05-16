package com.biblioteca.dto;

import java.time.LocalDateTime;
import java.util.List;

public class ContentDto {
    private String id;
    private String title;
    private String description;
    private String filename;
    private String category;
    private String manualCategory;
    private String author;
    private LocalDateTime createdAt;
    private String coverImage;
    private String linkUrl;
    private String fileUrl;
    private String coverUrl;
    private List<CommentDto> comments;

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
    public String getFileUrl() { return fileUrl; }
    public void setFileUrl(String fileUrl) { this.fileUrl = fileUrl; }
    public String getCoverUrl() { return coverUrl; }
    public void setCoverUrl(String coverUrl) { this.coverUrl = coverUrl; }
    public List<CommentDto> getComments() { return comments; }
    public void setComments(List<CommentDto> comments) { this.comments = comments; }
}
