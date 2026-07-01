package com.ragbrain.rag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A registered brain: its knowledge source, pack, and model (spec §6). */
@Entity
@Table(name = "brains")
public class Brain {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "slug", nullable = false, length = 100, unique = true)
    private String slug;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Column(name = "pack_ref", length = 500)
    private String packRef;

    @Column(name = "source_type", length = 20)
    private String sourceType;

    @Column(name = "s3_bucket", length = 255)
    private String s3Bucket;

    @Column(name = "s3_prefix", length = 500)
    private String s3Prefix;

    @Column(name = "s3_region", length = 64)
    private String s3Region;

    @Column(name = "local_path", length = 1000)
    private String localPath;

    @Column(name = "answer_provider", length = 50)
    private String answerProvider;

    @Column(name = "answer_model", length = 100)
    private String answerModel;

    @Column(name = "utility_provider", length = 50)
    private String utilityProvider;

    @Column(name = "utility_model", length = 100)
    private String utilityModel;

    @Column(name = "local_base_url", length = 500)
    private String localBaseUrl;

    @Column(name = "local_api_key_ref", length = 500)
    private String localApiKeyRef;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Brain() {}

    public Brain(UUID id, String slug, String displayName) {
        this.id = id;
        this.slug = slug;
        this.displayName = displayName;
    }

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getPackRef() { return packRef; }
    public void setPackRef(String packRef) { this.packRef = packRef; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getS3Bucket() { return s3Bucket; }
    public void setS3Bucket(String s3Bucket) { this.s3Bucket = s3Bucket; }
    public String getS3Prefix() { return s3Prefix; }
    public void setS3Prefix(String s3Prefix) { this.s3Prefix = s3Prefix; }
    public String getS3Region() { return s3Region; }
    public void setS3Region(String s3Region) { this.s3Region = s3Region; }
    public String getLocalPath() { return localPath; }
    public void setLocalPath(String localPath) { this.localPath = localPath; }
    public String getAnswerProvider() { return answerProvider; }
    public void setAnswerProvider(String answerProvider) { this.answerProvider = answerProvider; }
    public String getAnswerModel() { return answerModel; }
    public void setAnswerModel(String answerModel) { this.answerModel = answerModel; }
    public String getUtilityProvider() { return utilityProvider; }
    public void setUtilityProvider(String utilityProvider) { this.utilityProvider = utilityProvider; }
    public String getUtilityModel() { return utilityModel; }
    public void setUtilityModel(String utilityModel) { this.utilityModel = utilityModel; }
    public String getLocalBaseUrl() { return localBaseUrl; }
    public void setLocalBaseUrl(String localBaseUrl) { this.localBaseUrl = localBaseUrl; }
    public String getLocalApiKeyRef() { return localApiKeyRef; }
    public void setLocalApiKeyRef(String localApiKeyRef) { this.localApiKeyRef = localApiKeyRef; }
    public boolean isDefault() { return isDefault; }
    public void setDefault(boolean isDefault) { this.isDefault = isDefault; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
