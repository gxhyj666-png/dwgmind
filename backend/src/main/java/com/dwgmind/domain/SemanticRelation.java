package com.dwgmind.domain;

import jakarta.persistence.*;

/**
 * 语义关系：语义层产物（图元之间的归属/约束关系）
 * 这是 DwgMind 的核心资产：让「哪根线属于哪个零件」「这个尺寸约束谁」可被机器推理
 */
@Entity
@Table(name = "semantic_relation", indexes = {
        @Index(name = "idx_rel_drawing", columnList = "drawingId"),
        @Index(name = "idx_rel_type", columnList = "relationType")
})
public class SemanticRelation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long drawingId;

    /** OWNERSHIP（图元归属零件）/ DIMENSION_OF（尺寸约束零件）/ CONTAINMENT（轮廓包含）/ ADJACENCY（相邻） */
    private String relationType;

    private Long sourceEntityId;
    private Long targetEntityId;

    /** 关系强度 0-1 */
    private Double weight;

    /** 关系依据说明 */
    @Column(columnDefinition = "TEXT")
    private String evidence;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDrawingId() { return drawingId; }
    public void setDrawingId(Long drawingId) { this.drawingId = drawingId; }
    public String getRelationType() { return relationType; }
    public void setRelationType(String relationType) { this.relationType = relationType; }
    public Long getSourceEntityId() { return sourceEntityId; }
    public void setSourceEntityId(Long sourceEntityId) { this.sourceEntityId = sourceEntityId; }
    public Long getTargetEntityId() { return targetEntityId; }
    public void setTargetEntityId(Long targetEntityId) { this.targetEntityId = targetEntityId; }
    public Double getWeight() { return weight; }
    public void setWeight(Double weight) { this.weight = weight; }
    public String getEvidence() { return evidence; }
    public void setEvidence(String evidence) { this.evidence = evidence; }
}
