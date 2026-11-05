package com.dwgmind.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 图纸（上传的工程图文件）
 */
@Entity
@Table(name = "drawing", indexes = {
        @Index(name = "idx_drawing_sha", columnList = "sha256"),
        @Index(name = "idx_drawing_status", columnList = "status")
})
public class Drawing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 原始文件名 */
    private String fileName;

    /** 文件后缀：dxf / dwg / pdf / png / jpg */
    private String fileType;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 存储路径 */
    private String storagePath;

    /** SHA-256，用于缓存去重 */
    @Column(length = 64)
    private String sha256;

    /** 上传到解析完成的状态：UPLOADED / PARSING / PARSED / FAILED */
    private String status;

    /** 语义生成状态：NONE / DONE / LLM_DONE */
    private String semanticStatus;

    /** 解析耗时（毫秒） */
    private Long parseCostMs;

    /** 图元数量 */
    private Integer entityCount;

    /** 结论数量 */
    private Integer conclusionCount;

    /** 分析引擎：RULE / LLM_ENHANCED */
    private String engine;

    /** 图纸画布宽高（用于前端还原比例） */
    private Double canvasWidth;
    private Double canvasHeight;

    /** 缩略图（base64 或路径） */
    @Column(columnDefinition = "TEXT")
    private String thumbnail;

    /** 解析失败原因 */
    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
        if (this.status == null) this.status = "UPLOADED";
        if (this.semanticStatus == null) this.semanticStatus = "NONE";
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String storagePath) { this.storagePath = storagePath; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSemanticStatus() { return semanticStatus; }
    public void setSemanticStatus(String semanticStatus) { this.semanticStatus = semanticStatus; }
    public Long getParseCostMs() { return parseCostMs; }
    public void setParseCostMs(Long parseCostMs) { this.parseCostMs = parseCostMs; }
    public Integer getEntityCount() { return entityCount; }
    public void setEntityCount(Integer entityCount) { this.entityCount = entityCount; }
    public Integer getConclusionCount() { return conclusionCount; }
    public void setConclusionCount(Integer conclusionCount) { this.conclusionCount = conclusionCount; }
    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }
    public Double getCanvasWidth() { return canvasWidth; }
    public void setCanvasWidth(Double canvasWidth) { this.canvasWidth = canvasWidth; }
    public Double getCanvasHeight() { return canvasHeight; }
    public void setCanvasHeight(Double canvasHeight) { this.canvasHeight = canvasHeight; }
    public String getThumbnail() { return thumbnail; }
    public void setThumbnail(String thumbnail) { this.thumbnail = thumbnail; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
