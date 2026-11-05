package com.dwgmind.domain;

import jakarta.persistence.*;

/**
 * 语义结论：应用层产物（可被检索、可被复核、可回溯）
 * 每条结论都带溯源坐标——这是「让评委看得见」的关键
 */
@Entity
@Table(name = "semantic_inference", indexes = {
        @Index(name = "idx_inf_drawing", columnList = "drawingId"),
        @Index(name = "idx_inf_part", columnList = "partId")
})
public class SemanticInference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long drawingId;

    /** PART_IDENTIFY / DIMENSION_ATTACH / CONTOUR_GROUP / PROCESS_NOTE / BOM_ITEM / RISK_NOTE */
    private String conclusionType;

    /** 关联的零件/区域标识 */
    private Long partId;

    /** 结论标题，例如「零件 P-001」 */
    private String title;

    /** 结论内容 */
    @Column(columnDefinition = "TEXT")
    private String content;

    /** 溯源坐标 JSON：[{x,y,w,h,entityId}] */
    @Column(columnDefinition = "TEXT")
    private String traceJson;

    /** 置信度 0-1；低于 0.6 进入人工复核队列 */
    private Double confidence;

    /** 产生引擎：RULE / LLM */
    private String engine;

    /** 复核状态：AUTO / PENDING_REVIEW / CONFIRMED / CORRECTED */
    private String reviewStatus;

    /** 人工修正后的内容 */
    @Column(columnDefinition = "TEXT")
    private String correctedContent;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDrawingId() { return drawingId; }
    public void setDrawingId(Long drawingId) { this.drawingId = drawingId; }
    public String getConclusionType() { return conclusionType; }
    public void setConclusionType(String conclusionType) { this.conclusionType = conclusionType; }
    public Long getPartId() { return partId; }
    public void setPartId(Long partId) { this.partId = partId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getTraceJson() { return traceJson; }
    public void setTraceJson(String traceJson) { this.traceJson = traceJson; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }
    public String getReviewStatus() { return reviewStatus; }
    public void setReviewStatus(String reviewStatus) { this.reviewStatus = reviewStatus; }
    public String getCorrectedContent() { return correctedContent; }
    public void setCorrectedContent(String correctedContent) { this.correctedContent = correctedContent; }
}
